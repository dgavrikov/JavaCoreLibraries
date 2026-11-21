package io.github.dgavrikov.core.service.tracing;

import io.github.dgavrikov.core.utils.Constants;
import io.micrometer.observation.Observation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;

/**
 * A lightweight immutable record designed for deterministic tracing context lifecycle management
 * via {@code try-with-resources}.
 *
 * <p>Encapsulates the current {@link Observation} and its active {@link Observation.Scope},
 * strictly guaranteeing proper cleanup order to eliminate {@code ThreadLocal} state leaks.</p>
 *
 * @param observation the active micrometer observation instance tracking the execution.
 * @param scope       the thread-bound scope boundary controlling the active logging MDC context.
 * @param traceInfo   the serialized internal state string containing specific tracing identifiers.
 */
public record TraceScope(
        @NotNull Observation observation,
        @NotNull Observation.Scope scope,
        @Nullable String traceInfo
) implements AutoCloseable {

    @Override
    public void close() {
        // 1. Standard Micrometer scope degradation
        scope.close();
        observation.stop();

        // 2. Structural safety: Forcibly cure the "pain" of external byte-code weaving agents
        String currentTraceId = MDC.get(Constants.MDC_TRACE_ID);
        if (Constants.INVALID_TRACE_ID.equals(currentTraceId)) {
            MDC.remove(Constants.MDC_TRACE_ID);
        }
    }
}
