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

@RequiredArgsConstructor
public class InboxRepositoryDefault implements InboxRepository {
    // High-performance optimization: caching the type token as a static constant
    // completely eliminates short-lived inner class allocations in the JVM Eden space
    // during high-throughput de-serialization, significantly reducing GC pressure under load.
    private static final TypeReference<Map<String, String>> HEADERS_TYPE_REF = new TypeReference<>() {
    };

    @Language("SQL")
    private static final String SQL_INSERT = """
            INSERT INTO inbox_events (message_id, event_type, payload, headers, status)
                VALUES (:messageId, :eventType, :payload::jsonb, :headers::jsonb, 'PROCESSING')
                ON CONFLICT (message_id) DO NOTHING;
            """;

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

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

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

    @Override
    public List<InboxEvent<?>> fetchBatchForRecovery(OffsetDateTime timeBoundary, int batchSize) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("timeBoundary", timeBoundary)
                .addValue("batchSize", batchSize);

        return jdbcTemplate.query(SQL_FETCH_RECOVERY, params, this::mapRowToEvent);
    }

    @Override
    public void changeStatusInBatch(List<String> messageIds, InboxStatus status, String reason) {
        if (messageIds.isEmpty()) return;

        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("reason", reason)
                .addValue("ids", messageIds);

        jdbcTemplate.update(SQL_UPDATE_STATUS, params);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long purgeProcessed(OffsetDateTime retentionBoundary, int batchSize) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("retentionBoundary", retentionBoundary)
                .addValue("batchSize", batchSize);
        Long deletedCount = jdbcTemplate.queryForObject(SQL_PURGE_PROCESSED, params, Long.class);
        return deletedCount != null ? deletedCount : 0L;
    }

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
