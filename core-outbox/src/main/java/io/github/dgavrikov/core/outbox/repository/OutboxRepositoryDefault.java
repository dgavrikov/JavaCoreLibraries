package io.github.dgavrikov.core.outbox.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxEventType;
import io.github.dgavrikov.core.outbox.model.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.intellij.lang.annotations.Language;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
@Slf4j
@RequiredArgsConstructor
public class OutboxRepositoryDefault implements OutboxRepository {

    @Language("SQL")
    private static final String SQL_INSERT = """
            INSERT INTO outbox_events(event_type, key_id, payload, headers, status, created_at, updated_at)
            VALUES (:type, :key_id, :payload::jsonb, :headers::jsonb, :status, NOW(), NOW())
            """;

    @Language("SQL")
    private static final String SQL_UPDATE_STATUS = """
            UPDATE outbox_events 
            SET status = :status, 
                reason = :reason, 
                updated_at = NOW() 
            WHERE id = ANY (CAST(:ids AS BIGINT[]))
            """;

    @Language("SQL")
    private static final String SQL_FIND_ABANDONED = """
            WITH targets AS (
                        SELECT id
                        FROM outbox_events
                        WHERE status = 'NEW'
                          AND updated_at < :timeBoundary
                        ORDER BY id ASC
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                    ),
                    updated AS (
                        UPDATE outbox_events oe
                        SET updated_at = NOW()
                        FROM targets t
                        WHERE oe.id = t.id
                        RETURNING oe.id, oe.event_type, oe.key_id, oe.payload, oe.headers
                    )
                    SELECT id, event_type, key_id, payload, headers FROM updated
            """;

    @Language("SQL")
    private static final String SQL_PURGE_HISTORICAL = """
            WITH rows_to_delete AS (
                SELECT oe.id
                FROM outbox_events oe
                WHERE oe.status = :status
                  AND oe.created_at < :retentionBoundary
                LIMIT :batchSize
                FOR NO KEY UPDATE SKIP LOCKED
            ),
            deleted_rows AS (
                DELETE FROM outbox_events oe
                USING rows_to_delete rtd
                WHERE rtd.id = oe.id
                RETURNING oe.id
            )
            SELECT count(*) FROM deleted_rows
            """;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void updateEventStatus(Long eventId, OutboxStatus outboxStatus, String reason) {
        String sanitizedReason = StringUtils.isBlank(reason) ? null : reason.trim();

        var params = new MapSqlParameterSource()
                .addValue("ids", new Long[]{eventId})
                .addValue("status", outboxStatus.name())
                .addValue("reason", sanitizedReason);

        namedParameterJdbcTemplate.update(SQL_UPDATE_STATUS, params);
    }

    @Override
    public OutboxEvent save(OutboxEventType eventType, String keyId, String payload, Map<String, String> headers, OutboxStatus outboxStatus) {

        try {
            String jsonHeaders = objectMapper.writeValueAsString(headers);

            var params = new MapSqlParameterSource()
                    .addValue("type", eventType.name())
                    .addValue("key_id", keyId)
                    .addValue("payload", payload)
                    .addValue("headers", jsonHeaders)
                    .addValue("status", outboxStatus.name());

            var keyHolder = new GeneratedKeyHolder();
            namedParameterJdbcTemplate.update(SQL_INSERT, params, keyHolder, new String[]{"id"});
            Long outboxEventId = Objects.requireNonNull(keyHolder.getKeyAs(Long.class), "Generated ID cannot be null");

            return OutboxEvent.builder()
                    .id(outboxEventId)
                    .eventType(eventType)
                    .keyId(keyId)
                    .payload(payload)
                    .headers(headers != null ? headers : Map.of())
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing headers for OutboxEvent",e);
        }
    }

    public List<OutboxEvent> findAbandonedEventsForUpdate(OffsetDateTime timeBoundary, int batchSize) {
        var params = new MapSqlParameterSource()
                .addValue("timeBoundary", timeBoundary)
                .addValue("batchSize", batchSize);

        return namedParameterJdbcTemplate.query(SQL_FIND_ABANDONED, params, this::mapRowToEvent);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override
    public long deleteSentEventsOlderThan(OffsetDateTime retentionBoundary, int batchSize) {
        var params = new MapSqlParameterSource()
                .addValue("status", OutboxStatus.SENT.name())
                .addValue("retentionBoundary", retentionBoundary)
                .addValue("batchSize", batchSize);

        Long deletedCount = namedParameterJdbcTemplate.queryForObject(SQL_PURGE_HISTORICAL, params, Long.class);
        return deletedCount != null ? deletedCount : 0L;
    }

    @Override
    public void updateEventStatusBatch(List<Long> eventIds, OutboxStatus outboxStatus) {
        if (eventIds == null || eventIds.isEmpty()) return;

        var params = new MapSqlParameterSource()
                .addValue("ids", eventIds.toArray(new Long[0]))
                .addValue("status", outboxStatus.name())
                .addValue("reason", null);

        namedParameterJdbcTemplate.update(SQL_UPDATE_STATUS, params);
    }

    private OutboxEvent mapRowToEvent(ResultSet rs, int rowNum) throws SQLException {
        String typeStr = rs.getString("event_type");
        OutboxEventType eventType = () -> typeStr;

        String rawHeaders = rs.getString("headers");

        try {
            return OutboxEvent.builder()
                    .id(rs.getLong("id"))
                    .eventType(eventType)
                    .keyId(rs.getString("key_id"))
                    .headers(objectMapper.readValue(rawHeaders, new TypeReference<>() {
                    }))
                    .payload(rs.getString("payload"))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error de-serializing headers for OutboxEvent", e);
        }
    }
}
