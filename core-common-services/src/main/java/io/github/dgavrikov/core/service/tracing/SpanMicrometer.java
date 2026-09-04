package io.github.dgavrikov.core.service.tracing;

import io.github.dgavrikov.core.service.tracing.impl.TraceData;
import io.micrometer.observation.Observation;
import io.micrometer.observation.transport.Kind;
import io.micrometer.tracing.Tracer;

public interface SpanMicrometer {
    Observation getObservation(TraceData traceData, String name);

    Observation getObservation(TraceData traceData, String name, Kind kind);

    Observation getObservation(String name);

    Observation getRestartObservation(String name);

    Observation getObservation(String traceInfo, String name, Kind kind);

    String getTraceInfo();

    Tracer getTracer();
}
