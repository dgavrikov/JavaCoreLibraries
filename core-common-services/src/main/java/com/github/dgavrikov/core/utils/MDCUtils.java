package com.github.dgavrikov.core.utils;

import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Span;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;

@UtilityClass
public class MDCUtils {
    private final static String SPLIT_CHARACTER = ":";

    public static String traceParent(String traceInfo) {
        if (traceInfo == null) {
            return null;
        }

        var splitTrace = traceInfo.split(SPLIT_CHARACTER);

        if (splitTrace.length >= 2) {
            return "00-" + splitTrace[0] + "-" + splitTrace[1] + "-01";
        }

        return null;
    }

    public static void setMDCTrace(String traceInfo, @Nullable Logger logger) {
        if (StringUtils.isEmpty(traceInfo)) {
            if (logger != null) logger.trace("setMDCTrace: traceInfo is empty");
            return;
        }

        var splitTrace = traceInfo.split(SPLIT_CHARACTER);

        setInternalMDCTrace(splitTrace[0], splitTrace.length > 1 ? splitTrace[1] : "", logger);
    }

    public static void setMDCTrace(Span span, @Nullable Logger logger) {
        if (span == null) {
            if (logger != null) logger.trace("spanTracing Span is null");
            return;
        }

        setInternalMDCTrace(span.context().traceId(), span.context().spanId(), logger);
    }

    public static void setMDCTrace(ScopedSpan span, Logger logger) {
        if (span == null) {
            logger.trace("ScopeSpan is null");
            return;
        }

        setInternalMDCTrace(span.context().traceId(), span.context().spanId(), logger);
    }

    public static void removeMDCTrace() {
        MDC.remove(Constants.MDC_TRACE_ID);
        MDC.remove(Constants.MDC_SPAN_ID);
    }

    public static void removeInvalidMDCTrace() {
        if (Constants.INVALID_TRACE_ID.equals(MDC.get(Constants.MDC_TRACE_ID)))
            MDC.remove(Constants.MDC_TRACE_ID);
    }

    public static String getMDCTraceInfo() {
        var traceId = MDC.get(Constants.MDC_TRACE_ID);
        var spanId = MDC.get(Constants.MDC_SPAN_ID);
        if (traceId != null && spanId != null) {
            return traceId + SPLIT_CHARACTER + spanId;
        }
        return null;
    }

    public static void setMDCTrace(io.opentelemetry.api.trace.Span span, @Nullable Logger logger) {
        if (span == null || span.getSpanContext() == null) {
            if (logger != null) logger.trace("SpanTracingOtel is null");
            return;
        }

        setInternalMDCTrace(span.getSpanContext().getTraceId(), span.getSpanContext().getTraceId(), logger);
    }

    public static void setMDCTrace(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        processTraceField(headers, Constants.X_B3_TRACE_ID, Constants.MDC_TRACE_ID, Constants.INVALID_TRACE_ID);
        processTraceField(headers, Constants.X_B3_SPAN_ID, Constants.MDC_SPAN_ID, Constants.INVALID_SPAN_ID);
    }

    public static void setHeaderOfMDC(HttpHeaders headers) {
        if(!headers.containsKey(Constants.X_B3_TRACE_ID) && MDC.get(Constants.MDC_TRACE_ID) != null) {
            headers.add(Constants.X_B3_TRACE_ID, MDC.get(Constants.MDC_TRACE_ID));
        }

        if(!headers.containsKey(Constants.X_B3_SPAN_ID) && MDC.get(Constants.MDC_SPAN_ID) != null) {
            headers.add(Constants.X_B3_SPAN_ID, MDC.get(Constants.MDC_SPAN_ID));
        }
    }

    private static void processTraceField(HttpHeaders headers, String xB3Key, String mdcKey, String invalidId) {
        if (!headers.containsKey(xB3Key)) {
            return;
        }

        String idFromHeader = headers.getFirst(xB3Key);
        String idFromMdc = MDC.get(mdcKey);

        if (idFromMdc != null && !idFromMdc.equals(invalidId) && !idFromMdc.equals(idFromHeader)) {
            headers.set(xB3Key, idFromMdc);
        } else if (idFromHeader != null) {
            MDC.put(mdcKey, idFromHeader);
        }
    }

    private static void setInternalMDCTrace(String traceId, String spanId, @Nullable Logger logger) {
        MDC.put(Constants.MDC_TRACE_ID, traceId);
        MDC.put(Constants.MDC_SPAN_ID, spanId);
        if (logger != null && logger.isTraceEnabled()) {
            logger.trace("Span traceId/spanId: {}/{}", traceId, spanId);
        }
    }
}
