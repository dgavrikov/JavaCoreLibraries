package io.github.dgavrikov.core.outbox.model;

/**
 * State machine profiles defining the strict lifecycle phases of an outbox event.
 */
public enum OutboxStatus {
    /** The event has been written to the database but has not yet been processed by the pipeline worker. */
    NEW,

    /** The event was successfully dispatched to the broker and acknowledged by the transport layer. */
    SENT,

    /**
     * Processing failed permanently (e.g., infrastructure configuration mismatch, unresolvable plugin).
     * Left intentionally intact for manual operations or custom DLQ alerting.
     */
    ERROR
}
