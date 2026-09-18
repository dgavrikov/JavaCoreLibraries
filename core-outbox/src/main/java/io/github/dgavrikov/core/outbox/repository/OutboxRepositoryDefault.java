package io.github.dgavrikov.core.outbox.repository;

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
import java.util.List;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class OutboxRepositoryDefault implements OutboxRepository {

    @Language("SQL")
    private static final String SQL_INSERT = """
            INSERT INTO outbox_events(event_type, aggregate_id, payload, status, created_at, updated_at)
            VALUES (:type, :agr_id, :payload::jsonb, :status, NOW(), NOW())
            """;

    @Language("SQL")
    private static final String SQL_UPDATE_STATUS = """
            UPDATE outbox_events 
            SET status = :status, 
                reason = :reason, 
                updated_at = NOW() 
            WHERE id IN (:ids)
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
                        RETURNING oe.id, oe.event_type, oe.aggregate_id, oe.payload
                    )
                    SELECT id, event_type, aggregate_id, payload FROM updated
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

    @Override
    public void updateEventStatus(Long eventId, OutboxStatus outboxStatus, String reason) {
        String sanitizedReason = StringUtils.isBlank(reason) ? null : reason.trim();

        var params = new MapSqlParameterSource()
                .addValue("ids", eventId)
                .addValue("status", outboxStatus.name())
                .addValue("reason", sanitizedReason);

        namedParameterJdbcTemplate.update(SQL_UPDATE_STATUS, params);
    }

    @Override
    public OutboxEvent save(OutboxEventType eventType, String aggregateId, String payload, OutboxStatus outboxStatus) {

        var params = new MapSqlParameterSource()
                .addValue("type", eventType.name())
                .addValue("agr_id", aggregateId)
                .addValue("payload", payload)
                .addValue("status",outboxStatus.name());

        var keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update(SQL_INSERT, params, keyHolder, new String[]{"id"});
        Long outboxEventId = Objects.requireNonNull(keyHolder.getKeyAs(Long.class), "Generated ID cannot be null");

        return OutboxEvent.builder()
                .id(outboxEventId)
                .eventType(eventType)
                .aggregateId(aggregateId)
                .payload(payload)
                .build();
    }

    public List<OutboxEvent> findAbandonedEventsForUpdate(OffsetDateTime timeBoundary, int batchSize) {
        var params = new MapSqlParameterSource()
                .addValue("timeBoundary", timeBoundary)
                .addValue("batchSize", batchSize);

        // Использование кастомного маппера (Zero-allocation / Лямбда без создания лишних объектов на строку)
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
                .addValue("ids", eventIds)
                .addValue("status", outboxStatus.name())
                .addValue("reason", null);

        namedParameterJdbcTemplate.update(SQL_UPDATE_STATUS, params);
    }

    private OutboxEvent mapRowToEvent(ResultSet rs, int rowNum) throws SQLException {
        String typeStr = rs.getString("event_type");
        // Чтобы восстановить интерфейс OutboxEventType, оборачиваем его в анонимную структуру или простейшую record-обертку
        OutboxEventType eventType = () -> typeStr;

        return OutboxEvent.builder()
                .id(rs.getLong("id"))
                .eventType(eventType)
                .aggregateId(rs.getString("aggregate_id"))
                .payload(rs.getString("payload"))
                .build();
    }
}
