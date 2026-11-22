package io.github.dgavrikov.core.service.tracing.impl;

import io.github.dgavrikov.core.utils.Constants;

public class W3CTraceData implements TraceData{
    private final String traceParent;

    public W3CTraceData(String traceParent) {
        this.traceParent = traceParent;
    }

    @Override
    public String getTrace(String name) {
        if(Constants.TRACE_PARENT_ID.equalsIgnoreCase(name))
            return traceParent;
        return null;
    }
}
