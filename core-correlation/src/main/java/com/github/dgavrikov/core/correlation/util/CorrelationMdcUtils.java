package com.github.dgavrikov.core.correlation.util;

import lombok.experimental.UtilityClass;
import org.slf4j.MDC;
import java.util.Map;

/**
 * Utility class for managing the distributed tracing context within Slf4j MDC.
 *
 * NOTE: This class replaces the old ThreadLocal-based context accessor.
 * Relying strictly on Slf4j MDC makes this code fully compatible and safe
 * for Java 21 Virtual Threads (Project Loom), preventing memory leaks.
 */
@UtilityClass
public class CorrelationMdcUtils {

    /**
     * Populates the MDC context with a predefined map of correlation values.
     * Usually called when extracting tracing metadata from incoming HTTP headers or Kafka records.
     *
     * @param contextMap map containing correlation keys and values
     */
    public static void setContextMap(Map<String, String> contextMap) {
        if (contextMap != null && !contextMap.isEmpty()) {
            MDC.setContextMap(contextMap);
        } else {
            clear();
        }
    }

    /**
     * Explicitly sets a single correlation key in the current thread log context.
     *
     * @param key the MDC key name (e.g., CorrelationMdcKeys.CORRELATION_ID)
     * @param value the tracing identifier value
     */
    public static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    /**
     * Completely wipes out the current MDC context.
     * Crucial to call at the end of the request/message lifecycle to prevent log contamination.
     */
    public static void clear() {
        MDC.clear();
    }
}
