package io.github.dgavrikov.core.inbox.model;

/**
 * Streamlined finite state machine enums representing processing lifecycle states
 * of an inbox record, engineered to maintain a minimal write-amplification profile on PostgreSQL.
 */
public enum InboxStatus {

    /**
     * The initial state for newly ingested events on the happy path, and stale events locked by the recovery scheduler.
     * Eliminates the need for a legacy 'NEW' state, ensuring a Zero-DB-Read architecture.
     */
    PROCESSING,

    /** Terminal status indicating successful business logic execution, marking the row safe for archival or purging. */
    PROCESSED,

    /** Active failure state applied when an uncaught exception escapes a plugin, forcing backoff scheduling routines. */
    FAILED
}
