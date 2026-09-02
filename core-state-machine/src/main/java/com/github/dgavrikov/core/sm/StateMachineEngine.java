package com.github.dgavrikov.core.sm;

import com.github.dgavrikov.core.service.tracing.SpanMicrometer;
import com.github.dgavrikov.core.sm.exception.SmInvalidStateException;
import com.github.dgavrikov.core.sm.exception.SmStateTransmissionNotSupportException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.observation.transport.Kind;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

@RequiredArgsConstructor
@Slf4j
public class StateMachineEngine<ID, S extends Enum<S>> {
    private final static Set<ExecutionSignal> CONTINUOUS_SIGNALS = Set.of(
            ExecutionSignal.SUCCESS, ExecutionSignal.SKIP
    );

    private final SmNotifyService<ID, S, ? extends ContextData<ID, S>> notifyService;
    private final SpanMicrometer spanMicrometer;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    private void init(){
        registerCounters();
    }

    private void registerCounters() {
        if(this.meterRegistry == null)
            return;
        for(var counter: SmMeterCounters.values()) {
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
        var span = spanMicrometer.getObservation(
                contextData.getTraceInfo(),
                "StateMachineEngine_" + registry.workflowName(),
                Kind.SERVER).start();
        var scope = span.openScope();
        boolean hasError = false;
        StepDefinition<ID, S, T> step = null;

        try {
            var runtimeCtx = new SmRuntimeContext<>(contextData);
            boolean runToCompletion = true;

            while (runToCompletion && contextData.getState() != null) {
                var currentState = contextData.getState();
                step = registry.getStep(currentState);
                if (step == null) {
                    log.warn("There is no step handler for status {}. Terminating execution.", currentState.name());
                    break;
                }

                var handlerName = step.eventHandler().getName();

                log.info("Processing of request #{} has been launched. Status: {}, Event: {}",
                        contextData.getId(), currentState, handlerName);

                if (step.notifyBefore())
                    ((SmNotifyService<ID, S, T>) notifyService).notifyBefore(contextData, registry.workflowName(), handlerName);

                step.eventHandler().handle(runtimeCtx);

                var signal = runtimeCtx.getSignal();
                if (signal == null) {
                    throw new SmInvalidStateException("Handler " + step.eventHandler().getName() + "did not set the completion signal.");
                }

                switch (signal) {
                    case SUCCESS, SKIP -> {
                        var nextState = step.getTransmission(signal);
                        if (nextState == null)
                            throw new SmStateTransmissionNotSupportException(
                                    "No transition found for signal " + signal + " at step " + currentState);

                        storageAdapter.changeState(contextData, nextState, null, true);
                    }
                    case SEND -> {
                        var nextStatus = step.getTransmission(ExecutionSignal.SEND);
                        storageAdapter.changeState(contextData, nextStatus, null, false);
                    }
                    case DEFER -> {
                        storageAdapter.changeDeferTime(contextData, runtimeCtx.getDeferUntil());
                    }
                    case RETRY -> {
                        storageAdapter.incrementRetryCount(contextData);
                    }
                    case FAIL -> {
                        var failStatus = step.getTransmission(ExecutionSignal.FAIL);
                        storageAdapter.changeState(contextData, failStatus, runtimeCtx.getFailReason(), false);
                        hasError = true;
                        incrementMeterCounter(SmMeterCounters.SIGNAL_FAIL);
                    }
                    case STOP -> {

                    }
                }

                if (step.notifyAfter())
                    ((SmNotifyService<ID, S, T>) notifyService).notifyAfter(contextData, registry.workflowName() ,handlerName, signal, runtimeCtx.getMetadata());

                if (CONTINUOUS_SIGNALS.contains(signal))
                    runtimeCtx.resetSignal();
                else
                    runToCompletion = false;
            }
        } catch (RuntimeException e) {
            hasError = true;
            log.error("Critical engine failure of the State Machine for ID#{}", contextData.getId());
            S errorState = (step != null && step.getTransmission(ExecutionSignal.FAIL) != null)
                    ? step.getTransmission(ExecutionSignal.FAIL)
                    : contextData.getState();

            storageAdapter.changeState(contextData,
                    errorState,
                    "Engine Crash: " + e.getLocalizedMessage(),
                    false);
            incrementMeterCounter(SmMeterCounters.ENGINE_CRASH);
        } finally {
            scope.close();
            span.stop();
        }

        return !hasError;
    }

    private void incrementMeterCounter(@NotNull SmMeterCounters counter) {
        if(this.meterRegistry == null)
            return;

        meterRegistry.counter(counter.counterName, Tags.empty()).increment();
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
