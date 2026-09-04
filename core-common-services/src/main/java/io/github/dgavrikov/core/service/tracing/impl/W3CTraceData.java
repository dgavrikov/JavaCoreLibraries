package io.github.dgavrikov.core.service.tracing.impl;

public class W3CTraceData implements TraceData {
    private final String traceParent;
    private final static String TRACE_PARENT = "praceparent";

    public W3CTraceData(String traceParent) {
        this.traceParent = traceParent;
    }


    @Override
    public String getTrace(String name) {
        return TRACE_PARENT.equalsIgnoreCase(name) ? traceParent : null;
    }
}
