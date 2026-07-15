package com.github.dgavrikov.core.service.tracing.impl;

import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.Propagator;
import io.micrometer.observation.transport.ReceiverContext;

public class TraceReceiverContext extends ReceiverContext<TraceData> {

    public TraceReceiverContext(Propagator.Getter<TraceData> getter, Kind kind) {
        super(getter, kind);
    }

    public TraceReceiverContext(TraceData traceData) {
        this(TraceData::getTrace, Kind.SERVER);
        setCarrier(traceData);
    }

    public TraceReceiverContext(TraceData traceData, Kind kind) {
        this(TraceData::getTrace, kind);
        setCarrier(traceData);
    }
}
