package io.github.dgavrikov.core.service.tracing;

import io.micrometer.observation.Observation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        // Strict cleanup order: always close the scope boundary before stopping the observation
        scope.close();
        observation.stop();
    }
}
