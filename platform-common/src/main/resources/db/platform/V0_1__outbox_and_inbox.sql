-- Shared reliability tables, applied to every service database by Flyway.

create table outbox_event (
    id             uuid primary key,
    topic          varchar(200) not null,
    message_key    varchar(200) not null,
    event_type     varchar(100) not null,
    correlation_id varchar(64),
    payload        jsonb        not null,
    created_at     timestamptz  not null,
    published_at   timestamptz
);
-- Partial index: the relay only ever scans the (small) unpublished set.
create index ix_outbox_unpublished on outbox_event (created_at) where published_at is null;
create index ix_outbox_key on outbox_event (message_key);

create table processed_event (
    event_id     uuid         not null,
    consumer     varchar(100) not null,
    processed_at timestamptz  not null,
    primary key (event_id, consumer)
);
