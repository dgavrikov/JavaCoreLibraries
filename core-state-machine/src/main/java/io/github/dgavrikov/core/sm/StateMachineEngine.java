package io.github.dgavrikov.core.sm;

import io.github.dgavrikov.core.service.tracing.TraceMicrometer;
import io.github.dgavrikov.core.sm.exception.SmInvalidStateException;
import io.github.dgavrikov.core.sm.exception.SmStateTransmissionNotSupportException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
public class StateMachineEngine<ID, S extends SmState> {
    private final static int MAX_STATE_REVISITS_DEFAULT = 3;
    private final static Set<ExecutionSignal> CONTINUOUS_SIGNALS = Set.of(
            ExecutionSignal.SUCCESS, ExecutionSignal.SKIP
    );

    private final static Set<ExecutionSignal> EXCLUDED_SIGNAL = Set.of(
            ExecutionSignal.STOP, ExecutionSignal.RETRY
    );

    private final SmNotifyService<ID, S, ? extends ContextData<ID, S>> notifyService;
    private final TraceMicrometer traceMicrometer;
    private final MeterRegistry meterRegistry;
    private final int maxStateRevisits;

    public StateMachineEngine(SmNotifyService<ID, S, ? extends ContextData<ID, S>> notifyService,
                              TraceMicrometer traceMicrometer,
                              MeterRegistry meterRegistry,
                              Integer maxStateRevisits) {
        this.notifyService = notifyService;
        this.traceMicrometer = traceMicrometer;
        this.meterRegistry = meterRegistry;
        this.maxStateRevisits = maxStateRevisits == null || maxStateRevisits < MAX_STATE_REVISITS_DEFAULT
                ? MAX_STATE_REVISITS_DEFAULT
                : maxStateRevisits;
    }

    @PostConstruct
    private void init() {
        registerCounters();
    }

    private void registerCounters() {
        if (this.meterRegistry == null)
            return;
        for (var counter : SmMeterCounters.values()) {
            Counter.builder(counter.counterName)
                    .description(counter.description)
                    .register(meterRegistry);
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends ContextData<ID, S>> boolean execute(
            T contextData,
            SmWorkflowRegistry<ID, S, T> registry,
            SmStorageAdapter<ID, S, T> storageAdapter
    ) {

        boolean hasError = false;
        StepDefinition<ID, S, T> step = null;

        try (var traceScope = traceMicrometer.openScope(
                "state_machine.execute",
                contextData.getTraceInfo())
        ) {
            traceScope.observation()
                    .lowCardinalityKeyValue("sm.workflow", registry.workflowName())
                    .lowCardinalityKeyValue("sm.initial_state",
                            contextData.getState() != null ? contextData.getState().name() : "NULL")
                    .highCardinalityKeyValue("sm.entity.id", String.valueOf(contextData.getId()));

            try {
                var runtimeCtx = new SmRuntimeContext<>(contextData);
                boolean runToCompletion = true;

                Map<S, Integer> stateVisits = new HashMap<>(16);

                while (runToCompletion && contextData.getState() != null) {
                    var currentState = contextData.getState();

                    int visits = stateVisits.merge(currentState, 1, Integer::sum);
                    if (visits > maxStateRevisits) {
                        throw new SmInvalidStateException(
                                "Infinite loop detected: state " + currentState + " visited " + visits + " times in a single RTC cycle"
                        );
                    }

                    step = null;
                    step = registry.getStep(currentState);
                    if (step == null) {
                        log.warn("There is no step handler for status {}. Terminating execution.", currentState.name());
                        break;
                    }

                    var handlerName = step.eventHandler().getName();

                    try (var stepScope = traceMicrometer.openScope("state_machine.step")) {
                        stepScope.observation()
                                .lowCardinalityKeyValue("sm.workflow", registry.workflowName())
                                .lowCardinalityKeyValue("sm.state", currentState.name())
                                .lowCardinalityKeyValue("sm.handler", handlerName);
                        try {
                            log.info("Processing of request #{} has been launched. Status: {}, Event: {}",
                                    contextData.getId(), currentState, handlerName);

                            if (step.notifyBefore())
                                ((SmNotifyService<ID, S, T>) notifyService).notifyBefore(contextData, registry.workflowName(), handlerName);

                            step.eventHandler().handle(runtimeCtx);

                            var signal = runtimeCtx.getSignal();
                            if (signal == null) {
                                throw new SmInvalidStateException("Handler " + step.eventHandler().getName()
                                        + " did not set the completion signal.");
                            }

                            stepScope.observation().lowCardinalityKeyValue("sm.signal", signal.name());

                            var nextState = step.getTransmission(signal);
                            if (nextState == null && !EXCLUDED_SIGNAL.contains(signal))
                                throw new SmStateTransmissionNotSupportException(
                                        "No transition found for signal " + signal + " at step " + currentState);

                            switch (signal) {
                                case SUCCESS, SKIP, SEND -> storageAdapter.changeState(contextData, nextState, null);
                                case DEFER ->
                                        storageAdapter.changeDeferTime(contextData, nextState, runtimeCtx.getDeferUntil());
                                case RETRY -> storageAdapter.incrementRetryCount(contextData);
                                case FAIL -> {
                                    storageAdapter.changeState(contextData, nextState, runtimeCtx.getFailReason());
                                    hasError = true;
                                    incrementMeterCounter(SmMeterCounters.SIGNAL_FAIL, registry.workflowName(), currentState);
                                }
                                case STOP -> {
                                }
                            }

                            if (step.notifyAfter())
                                ((SmNotifyService<ID, S, T>) notifyService).notifyAfter(
                                        contextData,
                                        registry.workflowName(),
                                        handlerName,
                                        signal,
                                        runtimeCtx.getMetadata());

                            if (CONTINUOUS_SIGNALS.contains(signal))
                                runtimeCtx.resetSignal();
                            else
                                runToCompletion = false;
                        } catch (RuntimeException e) {
                            hasError = true;
                            stepScope.observation().error(e);
                            log.error("Critical step failure of the State Machine for ID#{}. State: {}, Handler: {}",
                                    contextData.getId(), currentState, handlerName, e);
                            handleCrash(contextData, registry.workflowName(), step, handlerName, e, storageAdapter);
                            break;
                        }
                    }
                }
            } catch (RuntimeException e) {
                hasError = true;
                traceScope.observation().error(e);
                log.error("Critical engine failure of the State Machine for ID#{}", contextData.getId(), e);
                handleCrash(contextData, registry.workflowName(), step, null, e, storageAdapter);
            }
        }

        return !hasError;
    }

    private void incrementMeterCounter(@NotNull SmMeterCounters counter, String workflow, @Nullable S state) {
        if (this.meterRegistry == null) return;

        meterRegistry.counter(
                counter.counterName,
                "workflow", workflow,
                "state", state != null ? state.name() : "UNKNOWN"
        ).increment();
    }

    private <T extends ContextData<ID, S>> void handleCrash(
            T contextData,
            @NotNull String workflow,
            @Nullable StepDefinition<ID, S, T> step,
            @Nullable String handlerName,
            RuntimeException e,
            SmStorageAdapter<ID, S, T> storageAdapter
    ) {
        incrementMeterCounter(SmMeterCounters.ENGINE_CRASH,workflow, contextData.getState());
        S errorState = (step != null && step.getTransmission(ExecutionSignal.FAIL) != null)
                ? step.getTransmission(ExecutionSignal.FAIL)
                : contextData.getState();

        String errorMsg = Objects.requireNonNullElse(e.getLocalizedMessage(), e.getClass().getSimpleName());
        String reason = (handlerName != null)
                ? "Step Crash [" + handlerName + "]: " + errorMsg
                : "Engine Crash: " + errorMsg;
        try {
            storageAdapter.changeState(contextData, errorState, reason);
        } catch (RuntimeException ex) {
            log.error("Failed to persist crash state for ID#{}. Target error state: {}, Original reason: [{}]",
                    contextData.getId(), errorState, reason, ex);
        }
    }

    @RequiredArgsConstructor
    private enum SmMeterCounters {
        SIGNAL_FAIL(
                "sm_signal_fail",
                "State machine fail signal"),
        ENGINE_CRASH(
                "sm_engine_crash",
                "State machine engine crash");

        private final String counterName;
        private final String description;
    }
}
