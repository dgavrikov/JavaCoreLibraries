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

import java.sql.ResultSet;
import java.sql.SQLException;
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
            INSERT INTO inbox_messages (message_id, event_type, payload, headers, status)
                VALUES (:message_id, :event_type, :payload::jsonb, :headers::jsonb, 'NEW')
                ON CONFLICT (message_id) DO NOTHING;
            """;

    @Language("SQL")
    private static final String SQL_FETCH_RECOVERY = """
            UPDATE inbox_messages
            SET status = 'PROCESSING', updated_at = NOW()
            WHERE message_id IN (
                SELECT message_id FROM inbox_messages
                WHERE status IN ('NEW', 'FAILED')
                  AND next_execution_at <= NOW() - CAST(:time_depth || ' second' AS INTERVAL)
                ORDER BY next_execution_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :batch_size
            )
            RETURNING message_id, event_type, payload, headers;
            """;

    @Language("SQL")
    private static final String SQL_PURGE_PROCESSED = """
            DELETE FROM inbox_messages
            WHERE message_id IN (
                SELECT message_id FROM inbox_messages
                WHERE status = 'PROCESSED' AND updated_at < NOW() - CAST(:hours || ' hour' AS INTERVAL)
                LIMIT :batch_size
            )
            """;

    private static final String SQL_UPDATE_STATUS = """
            UPDATE inbox_messages
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
    public boolean saveStrictly(InboxEvent event) {
        try {
            String jsonHeaders = objectMapper.writeValueAsString(event.headers());

            SqlParameterSource params = new MapSqlParameterSource()
                    .addValue("message_id", event.messageId())
                    .addValue("event_type", event.eventType().asString())
                    .addValue("payload", event.payload())
                    .addValue("headers", jsonHeaders);

            int affected = jdbcTemplate.update(SQL_INSERT, params);
            return affected > 0; // false означает дедупликацию на входе
        } catch (DuplicateKeyException e) {
            return false;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing headers for InboxEvent", e);
        }
    }

    @Override
    public List<InboxEvent> fetchBatchForRecovery(int batchSize, long timeDepthSec) {


        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("time_depth", timeDepthSec)
                .addValue("batch_size", batchSize);

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
    public void purgeProcessed(int hoursDepth, int batchSize) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("hours", hoursDepth)
                .addValue("batch_size", batchSize);
        jdbcTemplate.update(SQL_PURGE_PROCESSED, params);
    }

    private InboxEvent mapRowToEvent(ResultSet rs, int rowNum) throws SQLException {
        String typeStr = rs.getString("event_type");
        InboxEventType eventType = () -> typeStr;

        String rawHeaders = rs.getString("headers");

        try {
            return InboxEvent.builder()
                    .messageId(rs.getString("message_id"))
                    .eventType(eventType)
                    .payload(rs.getString("payload"))
                    .headers(objectMapper.readValue(rawHeaders, HEADERS_TYPE_REF))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error de-serializing headers for InboxEvent", e);
        }
    }
}
