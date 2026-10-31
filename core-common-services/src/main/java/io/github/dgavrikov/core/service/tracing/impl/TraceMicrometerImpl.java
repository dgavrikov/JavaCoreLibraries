package io.github.dgavrikov.core.service.tracing.impl;

import io.github.dgavrikov.core.service.tracing.TraceMicrometer;
import io.github.dgavrikov.core.service.tracing.TraceScope;
import io.github.dgavrikov.core.utils.TraceInfoParser;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class TraceMicrometerImpl implements TraceMicrometer {
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final io.micrometer.tracing.propagation.Propagator propagator;

    private static final io.micrometer.observation.transport.Propagator.Getter<W3CTraceData> OBSERVATION_GETTER =
            W3CTraceData::getTrace;

    private static final io.micrometer.tracing.propagation.Propagator.Getter<W3CTraceData> TRACING_GETTER =
            W3CTraceData::getTrace;

    @Override
    @NotNull
    public TraceScope openScope(@NotNull String name) {
        Observation observation = Observation.createNotStarted(name, observationRegistry);
        observation.start();
        Observation.Scope scope = observation.openScope();
        return new TraceScope(observation, scope, getCurrentTraceInfo());
    }

    @Override
    @NotNull
    public TraceScope openScope(@NotNull String name, @Nullable String traceInfo) {
        Observation observation;

        String w3cTraceParent = TraceInfoParser.traceParent(traceInfo);

        if (w3cTraceParent != null) {
            W3CTraceData traceData = new W3CTraceData(w3cTraceParent);

            ReceiverContext<W3CTraceData> context = new ReceiverContext<>(OBSERVATION_GETTER, Kind.SERVER);
            context.setCarrier(traceData);

            this.propagator.extract(traceData, TRACING_GETTER);

            observation = Observation.createNotStarted(name, () -> context, observationRegistry);
        } else {
            observation = Observation.createNotStarted(name, observationRegistry);
        }

        observation.start();
        Observation.Scope scope = observation.openScope();

        return new TraceScope(observation, scope, getCurrentTraceInfo());
    }

    @Override
    @Nullable
    public String getCurrentTraceInfo() {
        var currentSpan = tracer.currentSpan();
        if (currentSpan == null) {
            return null;
        }
        var ctx = currentSpan.context();
        // формат: traceId:spanId:parentId
        return ctx.traceId() + ":" + ctx.spanId() + ":" + ctx.parentId();
    }

    @Override
    public void execute(@NotNull String name, @NotNull Consumer<TraceScope> action, @Nullable Consumer<Throwable> onError) {
        try (var traceScope = openScope(name)) {
            try {
                action.accept(traceScope);
            } catch (Throwable e) {
                traceScope.observation().error(e);
                if (onError != null) {
                    onError.accept(e);
                } else {
                    throw e;
                }
            }
        }
    }

    @Override
    public <R> R execute(@NotNull String name, @NotNull Function<TraceScope, R> action, @Nullable Consumer<Throwable> onError) {
        try (var traceScope = openScope(name)) {
            try {
                return action.apply(traceScope);
            } catch (Throwable e) {
                traceScope.observation().error(e);
                if (onError != null) {
                    onError.accept(e);
                    return null;
                } else {
                    throw e;
                }
            }
        }
    }

    @Override
    public void execute(@NotNull String name, @Nullable String traceInfo, @NotNull Consumer<TraceScope>  action, @Nullable Consumer<Throwable> onError) {
        try (var traceScope = openScope(name, traceInfo)) {
            try {
                action.accept(traceScope);
            } catch (Throwable e) {
                traceScope.observation().error(e);
                if (onError != null) {
                    onError.accept(e);
                } else {
                    throw e;
                }
            }
        }
    }

    @Override
    public <R> R execute(@NotNull String name, @Nullable String traceInfo, @NotNull Function<TraceScope, R> action, @Nullable Consumer<Throwable> onError) {
        try (var traceScope = openScope(name, traceInfo)) {
            try {
                return action.apply(traceScope);
            } catch (Throwable e) {
                traceScope.observation().error(e);
                if (onError != null) {
                    onError.accept(e);
                    return null;
                } else {
                    throw e;
                }
            }
        }
    }
}
