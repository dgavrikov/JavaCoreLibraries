package io.github.dgavrikov.core.inbox.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxEventType;
import io.github.dgavrikov.core.inbox.model.InboxStatus;
import lombok.RequiredArgsConstructor;
import org.intellij.lang.annotations.Language;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Default production-ready implementation of {@link InboxRepository} built on top of {@link NamedParameterJdbcTemplate}.
 * Leverages native PostgreSQL performance features such as jsonb type casting, partial indexes alignment,
 * and concurrent row-locking strategies to support ultra-low-latency transaction execution loops.
 */

@RequiredArgsConstructor
public class InboxRepositoryDefault implements InboxRepository {
    /**
     * Reusable type reference mapping token used to parse flat JSON metadata headers.
     * Cached statically to eliminate short-lived inner class allocations inside JVM Eden Space under load.
     */
    private static final TypeReference<Map<String, String>> HEADERS_TYPE_REF = new TypeReference<>() {
    };

    /**
     * High-speed raw insertion statement enforcing immediate storage-level idempotent deduplication.
     * Bypasses the traditional 'NEW' phase by committing directly as 'PROCESSING' to maintain Zero-DB-Read limits.
     */
    @Language("SQL")
    private static final String SQL_INSERT = """
            INSERT INTO inbox_events (message_id, event_type, payload, headers, status)
                VALUES (:messageId, :eventType, :payload::jsonb, :headers::jsonb, 'PROCESSING')
                ON CONFLICT (message_id) DO NOTHING;
            """;

    /**
     * Distributed concurrency recovery block selecting stale processing traces and failed exponential backoffs.
     * Utilizes a highly optimized UNION ALL execution plan to map directly onto PostgreSQL partial indexes,
     * isolating target message records via a non-blocking FOR UPDATE SKIP LOCKED query segment.
     */
    @Language("SQL")
    private static final String SQL_FETCH_RECOVERY = """
            UPDATE inbox_events
            SET updated_at = NOW()
            WHERE message_id IN (
                SELECT message_id FROM (
                    SELECT message_id, next_execution_at FROM inbox_events
                    WHERE status = 'PROCESSING' AND updated_at <= :timeBoundary
            
                    UNION ALL
            
                    SELECT message_id, next_execution_at FROM inbox_events
                    WHERE status = 'FAILED' AND next_execution_at <= NOW()
                ) h
                ORDER BY next_execution_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            RETURNING message_id, event_type, payload, headers;
            """;

    /**
     * Partitioned transaction log deletion segment targeting old historical data entries.
     * Wraps execution blocks inside a localized Common Table Expression (CTE) with low row limits
     * and FOR NO KEY UPDATE SKIP LOCKED boundaries to safely wipe indexes without risking lock escalations.
     */
    @Language("SQL")
    private static final String SQL_PURGE_PROCESSED = """
            WITH rows_to_delete as (
                SELECT ie.message_id
                FROM inbox_events ie
                WHERE ie.status = 'PROCESSED' AND ie.updated_at < :retentionBoundary
                LIMIT :batchSize
                FOR NO KEY UPDATE SKIP LOCKED
            ),
            deleted_rows AS (
                DELETE FROM inbox_events ie
                USING rows_to_delete rtd
                WHERE rtd.message_id = ie.message_id
                RETURNING ie.message_id
            )
            SELECT count(*) from deleted_rows;
            """;

    /**
     * Batch state transformation update tracking statement.
     * Consolidates large multi-row mutations into a single network round-trip, dynamically
     * incrementing failure thresholds and calculating a strict base-2 exponential delay timing window.
     */
    @Language("SQL")
    private static final String SQL_UPDATE_STATUS = """
            UPDATE inbox_events
            SET status = :status,
                reason = :reason,
                updated_at = NOW(),
                retry_count = CASE WHEN :status = 'FAILED' THEN retry_count + 1 ELSE retry_count END,
                next_execution_at = CASE WHEN :status = 'FAILED' THEN NOW() + CAST(POWER(2, retry_count) || ' minutes' AS INTERVAL) ELSE next_execution_at END
            WHERE message_id IN (:ids)
            """;

    /**
     * Internal Spring NamedParameterJdbcTemplate engine driver.
     */
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * High-performance Jackson object mapper used for flat metadata map transformations.
     */
    private final ObjectMapper objectMapper;

    /**
     * {@inheritDoc}
     * Maps flat headers into serialized string formats before processing insertions. Catches key integrity
     * conflicts natively at СУБД level to return clean boolean feedback signals without breaking transactions.
     */
    @Override
    public boolean save(InboxEvent<?> event) {
        try {
            String jsonHeaders = objectMapper.writeValueAsString(event.headers());

            SqlParameterSource params = new MapSqlParameterSource()
                    .addValue("messageId", event.messageId())
                    .addValue("eventType", event.eventType().asString())
                    .addValue("payload", event.payload())
                    .addValue("headers", jsonHeaders);

            int affected = jdbcTemplate.update(SQL_INSERT, params);
            return affected > 0;
        } catch (DuplicateKeyException e) {
            return false;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing headers for InboxEvent", e);
        }
    }

    /**
     * {@inheritDoc}
     * Binds parameters into the explicit split UNION ALL schema block to fetch locked event logs.
     */
    @Override
    public List<InboxEvent<?>> fetchBatchForRecovery(OffsetDateTime timeBoundary, int batchSize) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("timeBoundary", timeBoundary)
                .addValue("batchSize", batchSize);

        return jdbcTemplate.query(SQL_FETCH_RECOVERY, params, this::mapRowToEvent);
    }

    /**
     * {@inheritDoc}
     * Guards execution traps by verifying input size metrics before pushing status updates to the СУБД runtime.
     */
    @Override
    public void changeStatusInBatch(List<String> messageIds, InboxStatus status, String reason) {
        if (messageIds.isEmpty()) return;

        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("reason", reason)
                .addValue("ids", messageIds);

        jdbcTemplate.update(SQL_UPDATE_STATUS, params);
    }

    /**
     * {@inheritDoc}
     * Executes targeted cleanup operations within an isolated transaction layer (PROPAGATION_REQUIRES_NEW)
     * to guarantee index space is recovered even if parent business components experience transaction rollbacks.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long purgeProcessed(OffsetDateTime retentionBoundary, int batchSize) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("retentionBoundary", retentionBoundary)
                .addValue("batchSize", batchSize);
        Long deletedCount = jdbcTemplate.queryForObject(SQL_PURGE_PROCESSED, params, Long.class);
        return deletedCount != null ? deletedCount : 0L;
    }

    /**
     * High-speed database mapping extraction function translating raw relational ResultSet structures
     * back into type-safe immutable wildcard {@link InboxEvent} objects.
     * Note: domainContext is mapped explicitly as null here, as it requires background re-validation.
     */
    private InboxEvent<?> mapRowToEvent(ResultSet rs, int rowNum) throws SQLException {
        String typeStr = rs.getString("event_type");
        InboxEventType eventType = () -> typeStr;
        String rawHeaders = rs.getString("headers");

        try {
            return InboxEvent.builder()
                    .messageId(rs.getString("message_id"))
                    .eventType(eventType)
                    .payload(rs.getString("payload")) // Маппим в rawPayload
                    .domainContext(null) // На этапе вычитки из БД контекста еще нет, его восстановит MaintenanceWorker
                    .headers(objectMapper.readValue(rawHeaders, HEADERS_TYPE_REF))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error de-serializing headers for InboxEvent", e);
        }
    }
}
