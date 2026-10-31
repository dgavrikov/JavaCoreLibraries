package io.github.dgavrikov.core.service.tracing.impl;

public class W3CTraceData implements TraceData{
    private final static String TRACE_PARENT = "traceparent";
    private final String traceParent;

    public W3CTraceData(String traceParent) {
        this.traceParent = traceParent;
    }

    @Override
    public String getTrace(String name) {
        if(TRACE_PARENT.equalsIgnoreCase(name))
            return traceParent;
        return null;
    }
}
