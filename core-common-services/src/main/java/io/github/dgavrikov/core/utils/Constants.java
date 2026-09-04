package io.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Constants {
    public static final String X_B3_TRACE_ID = "X-B3-TraceId";
    public static final String X_B3_SPAN_ID = "X-B3-SpanId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String X_INITIATOR_SERVICE = "x-initiator-service";
    public static final String X_INITIATOR_HOST = "x-initiator-host";
    @Deprecated(since = "use X_B3_TRACE_ID")
    public static final String TRACE_ID = "TRACE_ID";
    @Deprecated
    public static final String UBER_TRACE_ID = "uber-trace-id";
    public static final String SERVICE_TO = "service-to";
    public static final String X_MDM_ID = "x-mdm-id";
    @Deprecated(since = "use X_MDM_ID")
    public static final String MDM_ID = "mdmId";
    public static final String METHOD_NAME = "method-name";
    public static final String INVALID_TRACE_ID = "00000000000000000000000000000000";
    public static final String INVALID_SPAN_ID = "0000000000000000";
    public static final String TRACE_PARENT_ID = "traceparent";
    public static final int LENGTH_BODY = 6000;
    public static final char DEFAULT_REPLACEMENT_CHAR = '*';
    public static final String DEFAULT_REPLACEMENT = "%s%s%s".formatted(DEFAULT_REPLACEMENT_CHAR, DEFAULT_REPLACEMENT_CHAR, DEFAULT_REPLACEMENT_CHAR);
}
