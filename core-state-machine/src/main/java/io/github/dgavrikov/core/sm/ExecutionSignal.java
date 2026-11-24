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
     * Records a retry attempt (e.g., during polling or scheduled re-execution).
     * Increments the retry counter in the DB without changing the current state,
     * breaks the RTC loop, and clears memory.
     * Note: Does not require a transition entry in the workflow step definition (any configured target state is ignored).
     */
    RETRY,

    /**
     * Terminates the current step and exits the RTC State Machine instance immediately.
     * No state, counters, or data mutations are persisted to the database.
     * Note: Does not require a transition entry in the workflow step definition (any configured target state is ignored).
     */
    STOP,

    /**
     * A critical business logic or terminal infrastructure error occurred on the step.
     * Moves the entity to an error state, records the reason, and terminates the loop.
     */
    FAIL
}
