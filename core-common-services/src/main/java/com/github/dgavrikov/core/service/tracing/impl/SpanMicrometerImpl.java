package com.github.dgavrikov.core.service.tracing.impl;

import com.github.dgavrikov.core.service.tracing.SpanMicrometer;
import com.github.dgavrikov.core.utils.MDCUtils;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class SpanMicrometerImpl implements SpanMicrometer {
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public Observation getObservation(TraceData traceData, String name) {
        return getObservation(traceData, name, Kind.SERVER);
    }

    @Override
    public Observation getObservation(TraceData traceData, String name, Kind kind) {
        var context = new TraceReceiverContext(traceData, kind);
        propagator.extract(traceData, TraceData::getTrace);
        return Observation.createNotStarted(name, () -> context, observationRegistry);
    }

    @Override
    public Observation getObservation(String name) {
        return Observation.createNotStarted(name, observationRegistry)
                .parentObservation(observationRegistry.getCurrentObservation());
    }

    @Override
    public Observation getRestartObservation(String name) {
        var currentScope = observationRegistry.getCurrentObservationScope();
        if (currentScope != null)
            currentScope.close();

        return Observation.createNotStarted(name, observationRegistry)
                .parentObservation(null);
    }

    @Override
    public Observation getObservation(String traceInfo, String name, Kind kind) {
        var traceData = new W3CTraceData((MDCUtils.traceParent(traceInfo)));
        return getObservation(traceData, name, kind);
    }

    @Override
    public String getTraceInfo() {
        var currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            var ctx = currentSpan.context();
            return ctx.traceId() + ":" + ctx.spanId() + ":" + ctx.parentId();
        }
        return null;
    }

    @Override
    public Tracer getTracer() {
        return this.tracer;
    }


}
