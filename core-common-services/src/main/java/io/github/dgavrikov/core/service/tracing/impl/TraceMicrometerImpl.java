package io.github.dgavrikov.core.service.tracing.impl;

import io.github.dgavrikov.core.service.tracing.TraceMicrometer;
import io.github.dgavrikov.core.service.tracing.TraceScope;
import io.github.dgavrikov.core.utils.TraceInfoParser;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.Propagator.Getter;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Slf4j
@RequiredArgsConstructor
public class TraceMicrometerImpl implements TraceMicrometer {
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    // 1. Геттер для Observation (исправляет твою ошибку компиляции)
    private static final Getter<String> OBSERVATION_W3C_GETTER = (carrier, key) ->
            "traceparent".equalsIgnoreCase(key) ? carrier : null;

    // 2. Геттер для Tracing (нужен для метода extract)
    private static final io.micrometer.tracing.propagation.Propagator.Getter<String> TRACING_W3C_GETTER = (carrier, key) ->
            "traceparent".equalsIgnoreCase(key) ? carrier : null;

    @Override
    @NotNull
    public TraceScope openScope(@NotNull String name) {
        Observation observation = Observation.createNotStarted(name, observationRegistry);
        // Micrometer сам подхватит текущий observation как parent, если он есть в текущем потоке
        observation.start();
        Observation.Scope scope = observation.openScope();

        return new TraceScope(observation, scope, getCurrentTraceInfo());
    }

    @Override
    @NotNull
    public TraceScope openScope(@NotNull String name, @Nullable String traceInfo) {
        /* old
        Observation observation;

        if (traceInfo != null && !traceInfo.isBlank()) {
            // Передаем правильный геттер из пакета observation.transport
            ReceiverContext<String> context = new ReceiverContext<>(OBSERVATION_W3C_GETTER, Kind.SERVER);
            context.setCarrier(traceInfo);

            // Извлекаем контекст трассировки с помощью трассировочного геттера
            propagator.extract(traceInfo, TRACING_W3C_GETTER);

            observation = Observation.createNotStarted(name, () -> context, observationRegistry);
        } else {
            observation = Observation.createNotStarted(name, observationRegistry);
        }

        observation.start();
        Observation.Scope scope = observation.openScope();

        return new TraceScope(observation, scope, getCurrentTraceInfo());

         */
        Observation observation;

        // Превращаем наш внутренний "traceId:spanId" в стандартный W3C traceparent на лету и быстро
        String w3cTraceParent = TraceInfoParser.toW3CTraceParent(traceInfo);

        if (w3cTraceParent != null) {
            ReceiverContext<String> context = new ReceiverContext<>(OBSERVATION_W3C_GETTER, Kind.SERVER);
            context.setCarrier(w3cTraceParent);

            // Пропогатор Micrometer ожидает чистый W3C формат
            this.propagator.extract(w3cTraceParent, TRACING_W3C_GETTER);

            observation = Observation.createNotStarted(name, () -> context, observationRegistry);
        } else {
            // Если traceInfo пустой или битый — стартуем чистый контекст (или подхватываем текущий родительский thread-bound трейс)
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
        return ctx.traceId() + ":" + ctx.spanId();
    }
}
