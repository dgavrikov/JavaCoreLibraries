package io.github.dgavrikov.core.service.tracing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * High-performance infrastructure service for distributed tracing and context propagation
 * built on top of Micrometer Observation and OpenTelemetry.
 * Provides both low-level imperative scopes via {@link AutoCloseable} and functional
 * pipelines for zero-allocation, flat corporate processing.
 */
public interface TraceMicrometer {

    /**
     * Starts a new or nested tracing scope. The parent context is automatically
     * captured from the current thread-bound storage.
     *
     * @param name the unique technical name of the tracing operation/span.
     * @return a {@link TraceScope} wrapper for try-with-resources context management.
     */
    @NotNull
    TraceScope openScope(@NotNull String name);

    /**
     * Starts a tracing scope by forcibly extracting and restoring the distributed
     * context from a formatted trace info string.
     *
     * @param name      the unique technical name of the tracing operation/span.
     * @param traceInfo the internal tracing state string containing identifiers.
     * @return a {@link TraceScope} wrapper for try-with-resources context management.
     */
    @NotNull
    TraceScope openScope(@NotNull String name, @Nullable String traceInfo);

    /**
     * Extracts the active distributed tracing context identifiers from the current span.
     *
     * @return the serialized tracing state string for persistence or outbound IPC transport,
     *         or {@code null} if no active tracing context is present in the current thread.
     */
    @Nullable
    String getCurrentTraceInfo();

    /**
     * Executes a synchronous side-effect action within a newly created tracing scope.
     * Automatically captures context and manages resource lifecycle.
     *
     * @param name    the unique technical name of the tracing operation/span.
     * @param action  the synchronous business execution flow accepting the active scope.
     * @param onError the custom error callback executed inside the active context before scope closure.
     *                If {@code null}, any intercepted exception will be rethrown immediately.
     */
    void execute(
            @NotNull String name,
            @NotNull Consumer<TraceScope> action,
            @Nullable Consumer<Throwable> onError
    );

    /**
     * Executes a synchronous value-returning calculation within a newly created tracing scope.
     * Automatically captures context and manages resource lifecycle.
     *
     * @param <R>     the return type of the functional action.
     * @param name    the unique technical name of the tracing operation/span.
     * @param action  the synchronous business computation flow accepting the active scope.
     * @param onError the custom error callback executed inside the active context before scope closure.
     *                If {@code null}, any intercepted exception will be rethrown immediately.
     * @return the computation result, or {@code null} if an exception is handled and suppressed by the callback.
     */
    <R> R execute(
            @NotNull String name,
            @NotNull Function<TraceScope, R> action,
            @Nullable Consumer<Throwable> onError
    );

    /**
     * Executes a synchronous side-effect action within a restored tracing scope.
     * Extracts distributed context identifiers before execution.
     *
     * @param name      the unique technical name of the tracing operation/span.
     * @param traceInfo the internal tracing state string containing identifiers.
     * @param action    the synchronous business execution flow accepting the active scope.
     * @param onError   the custom error callback executed inside the active context before scope closure.
     *                  If {@code null}, any intercepted exception will be rethrown immediately.
     */
    void execute(
            @NotNull String name,
            @Nullable String traceInfo,
            @NotNull Consumer<TraceScope> action,
            @Nullable Consumer<Throwable> onError
    );

    /**
     * Executes a synchronous value-returning calculation within a restored tracing scope.
     * Extracts distributed context identifiers before execution.
     *
     * @param <R>       the return type of the functional action.
     * @param name      the unique technical name of the tracing operation/span.
     * @param traceInfo the internal tracing state string containing identifiers.
     * @param action    the synchronous business computation flow accepting the active scope.
     * @param onError   the custom error callback executed inside the active context before scope closure.
     *                  If {@code null}, any intercepted exception will be rethrown immediately.
     * @return the computation result, or {@code null} if an exception is handled and suppressed by the callback.
     */
    <R> R execute(
            @NotNull String name,
            @Nullable String traceInfo,
            @NotNull Function<TraceScope, R> action,
            @Nullable Consumer<Throwable> onError
    );
}
