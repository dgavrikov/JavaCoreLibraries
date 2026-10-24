alter table if exists outbox_events
    rename to outbox_events_old;

alter index if exists idx_outbox_purge_historical
    rename to idx_outbox_purge_historical_old;


