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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class OutboxRepositoryDefault implements OutboxRepository {

    private final static Pattern SQL_TABLE_IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    // High-performance optimization: caching the type token as a static constant
    // completely eliminates short-lived inner class allocations in the JVM Eden space
    // during high-throughput de-serialization, significantly reducing GC pressure under load.
    private static final TypeReference<Map<String, String>> HEADERS_TYPE_REF = new TypeReference<>() {};

    private static final String BASE_TABLE_NAME = "outbox_events";

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
    private static final String SQL_GET_ACTIVE_PARTITIONS = """
            SELECT child.relname AS partition_name
            FROM pg_inherits
            JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
            JOIN pg_class child  ON pg_inherits.inhrelid = child.oid
            WHERE parent.relname = :masterTable AND child.relname != 'outbox_events_default'
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

    @Override
    public Partition createPartitionIfNotExists(LocalDate fromDate, LocalDate toDate) {
        int year = fromDate.getYear();
        int month = fromDate.getMonthValue();
        int day = fromDate.getDayOfMonth();

        StringBuilder sb = new StringBuilder(32);
        sb.append(BASE_TABLE_NAME).append("_").append(year).append("_");
        if (month < 10) sb.append('0');
        sb.append(month).append("_");
        if (day < 10) sb.append('0');
        sb.append(day);

        String partitionName = sb.toString();

        if (!SQL_TABLE_IDENTIFIER_PATTERN.matcher(partitionName).matches()) {
            throw new IllegalArgumentException("Invalid partition table identifier format built from dates");
        }

        String ddl = "CREATE TABLE IF NOT EXISTS \"" + partitionName + "\" " +
                "PARTITION OF \"" + BASE_TABLE_NAME + "\" " +
                "FOR VALUES FROM ('" + fromDate + "') TO ('" + toDate + "');";

        try {
            namedParameterJdbcTemplate.update(ddl, Map.of());
            log.info("Partition '{}' successfully verified or created.", partitionName);
        } catch (org.springframework.dao.DataAccessException e) {
            Throwable rootCause = e.getRootCause();
            if (rootCause instanceof java.sql.SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();

                // 42P07 — table_already_exists, 23505 — unique_violation (конфликт в системном каталоге pg_class)
                if ("42P07".equals(sqlState) || "23505".equals(sqlState)) {
                    log.info("Partition '{}' creation skipped: already created by a concurrent node (SQLState: {}).", partitionName, sqlState);
                    return new Partition(partitionName, fromDate, toDate);
                }
            }
            throw e;
        }

        return new Partition(partitionName, fromDate, toDate);
    }

    @Override
    public List<Partition> getActivePartitions() {

        var params = new MapSqlParameterSource().addValue("masterTable", BASE_TABLE_NAME);

        List<String> partitionNames = namedParameterJdbcTemplate.queryForList(
                SQL_GET_ACTIVE_PARTITIONS,
                params,
                String.class
        );

        if (partitionNames.isEmpty()) {
            return List.of();
        }

        String expectedPrefix = BASE_TABLE_NAME + "_";
        int prefixLength = expectedPrefix.length();

        List<Partition> partitions = new ArrayList<>(partitionNames.size());

        for (String name : partitionNames) {
            if (name == null || !name.startsWith(expectedPrefix) || name.length() == prefixLength) {
                continue;
            }

            try {
                String datePart = name.substring(prefixLength);
                String isoFormatDate = datePart.replace('_', '-');

                LocalDate startDate = LocalDate.parse(isoFormatDate);
                LocalDate endDate = startDate.plusDays(1);

                partitions.add(new Partition(name, startDate, endDate));
            } catch (Exception e) {
                log.warn("Skipping dynamic partition extraction path. Table relational naming layout '{}' does not match ISO chronological format parameters.", name);
            }
        }

        return partitions;
    }

    @Override
    public boolean hasUnsentEventsInPartition(Partition partition) {
        String partitionName = partition.name();
        if (!SQL_TABLE_IDENTIFIER_PATTERN.matcher(partitionName).matches()) {
            throw new IllegalArgumentException("Dangerous partition identifier format detected");
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append("SELECT EXISTS (SELECT 1 FROM \"").append(partitionName).append("\" ")
                .append("WHERE status = 'NEW' LIMIT 1);");

        Boolean hasUnsent = namedParameterJdbcTemplate.queryForObject(
                sb.toString(),
                new MapSqlParameterSource(),
                Boolean.class
        );

        return hasUnsent != null && hasUnsent;
    }


    @Override
    public void dropPartition(Partition partition) {
        String partitionName = partition.name();
        if (!SQL_TABLE_IDENTIFIER_PATTERN.matcher(partitionName).matches()) {
            throw new IllegalArgumentException("Dangerous partition identifier format detected");
        }

        StringBuilder sb = new StringBuilder(64);
        sb.append("DROP TABLE IF EXISTS \"").append(partitionName).append("\";");

        log.info("Executing hard/soft partition purge. Drop physical table: {}", partitionName);
        namedParameterJdbcTemplate.getJdbcOperations().execute(sb.toString());
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
                    .headers(objectMapper.readValue(rawHeaders, HEADERS_TYPE_REF))
                    .payload(rs.getString("payload"))
                    .build();
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error de-serializing headers for OutboxEvent", e);
        }
    }
}
