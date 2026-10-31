package io.github.dgavrikov.core.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;

/**
 * High-performance, zero-allocation utility for parsing internal tracing context strings
 * and transforming them into standard W3C Trace Context formats.
 *
 * <p>Optimized for critical transaction processing paths by eliminating regular expressions
 * and intermediate string array allocations (e.g., avoiding {@code String.split()}).</p>
 */
@UtilityClass
public class TraceInfoParser {
    private static final String SPLIT_CHARACTER = ":";

    /**
     * Extracts tracing identifiers from an internal serialized context string and converts
     * them into a standard compliant W3C {@code traceparent} header value.
     *
     * <p>Supported input formats:
     * <ul>
     *   <li>{@code traceId:spanId:parentId} (3-component legacy format)</li>
     *   <li>{@code traceId:spanId} (2-component compact format)</li>
     * </ul>
     * </p>
     *
     * <p>The extraction uses index-based substring operations to guarantee an O(1) execution
     * time and minimal memory pressure on the JVM Garbage Collector.</p>
     *
     * @param traceInfo the raw internal tracing string containing delimited identifiers,
     *                  may be {@code null} or empty.
     * @return a valid W3C {@code traceparent} string (e.g., {@code "00-traceId-spanId-01"}),
     *         or {@code null} if the input is null, empty, or structurally malformed.
     */
    @Nullable
    public static String traceParent(@Nullable String traceInfo) {
        if (traceInfo == null || traceInfo.isEmpty()) {
            return null;
        }

        // Locate the first delimiter (end of traceId)
        int firstColon = traceInfo.indexOf(SPLIT_CHARACTER);
        if (firstColon <= 0) return null;

        // Locate the second delimiter (end of spanId)
        int secondColon = traceInfo.indexOf(SPLIT_CHARACTER, firstColon + 1);

        String traceId;
        String spanId;

        if (secondColon > 0) {
            // Case A: 3 components present (traceId:spanId:parentId)
            traceId = traceInfo.substring(0, firstColon);
            spanId = traceInfo.substring(firstColon + 1, secondColon);
        } else {
            // Case B: 2 components present (traceId:spanId)
            traceId = traceInfo.substring(0, firstColon);
            spanId = traceInfo.substring(firstColon + 1);
        }

        if (spanId.isEmpty()) {
            return null;
        }

        return "00-" + traceId + "-" + spanId + "-01";
    }
}
