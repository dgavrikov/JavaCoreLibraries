DO
$$
    DECLARE
        rec RECORD;
        partition_date
            TEXT;
        partition_name
            TEXT;
        start_range
            TEXT;
        end_range
            TEXT;
    BEGIN
        -- Находим все уникальные дни в старых данных
        FOR rec IN
            SELECT DISTINCT created_at::date AS msg_date
            FROM outbox_events_old
            WHERE status != 'SENT'
            ORDER BY msg_date
            LOOP
                partition_date := to_char(rec.msg_date, 'YYYY_MM_DD');
                partition_name
                    := 'outbox_events_' || partition_date;
                start_range
                    := rec.msg_date::text;
                end_range
                    := (rec.msg_date + INTERVAL '1 day')::date::text;

                -- Динамически создаем секцию под конкретный исторический день, если её ещё нет
                IF
                    NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = partition_name) THEN
                    EXECUTE format(
                            'CREATE TABLE %I PARTITION OF outbox_events FOR VALUES FROM (%L) TO (%L);',
                            partition_name, start_range, end_range
                            );
                END IF;
            END LOOP;

        -- Переливаем данные атомарно
        IF
            EXISTS (SELECT 1 FROM pg_tables WHERE tablename = 'outbox_events_old') THEN
            INSERT INTO outbox_events (id, event_type, key_id, payload, headers, status, reason, created_at, updated_at)
            SELECT id,
                   event_type,
                   key_id,
                   payload,
                   headers,
                   status,
                   reason,
                   created_at,
                   updated_at
            FROM outbox_events_old
            WHERE status != 'SENT';
        END IF;
    END
$$;
