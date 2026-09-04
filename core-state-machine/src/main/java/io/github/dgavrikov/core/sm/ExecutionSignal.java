package io.github.dgavrikov.core.sm;

/**
 * Represents the execution signals returned by event handlers to control
 * the State Machine engine's Run-To-Completion (RTC) loop.
 */
public enum ExecutionSignal {
    /**
     * The step completed successfully. The engine continues the RTC loop
     * in the same thread, instantly invoking the next handler.
     */
    SUCCESS,

    /**
     * An asynchronous operation was triggered (e.g., Kafka message sent).
     * Breaks the loop, flushes changes to the DB, and evicts the context from memory.
     */
    SEND,

    /**
     * The step was intentionally skipped based on business logic.
     * Equivalent to SUCCESS for loop control, but captures a different business metric.
     */
    SKIP,

    /**
     * The process is intentionally postponed for rate-limiting or throttling.
     * Updates the 'deferred_until' timestamp in the DB, breaks the loop, and clears memory.
     */
    DEFER,

    /**
     * A transient failure occurred (e.g., network timeout). Signals a retry attempt.
     * Increments the retry counter, breaks the loop, and awaits the next scheduler tick.
     */
    RETRY,

    /**
     * Forces an immediate stop of the RTC loop execution.
     * No state or data mutations are saved to the database.
     */
    STOP,

    /**
     * A critical business logic or terminal infrastructure error occurred on the step.
     * Moves the entity to an error state, records the reason, and terminates the loop.
     */
    FAIL
}
