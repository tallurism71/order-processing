create table orders (
    id              uuid primary key,
    customer_id     varchar(64)    not null,
    status          varchar(32)    not null,
    total_amount    numeric(19, 2) not null check (total_amount >= 0),
    currency        varchar(3)       not null,
    idempotency_key varchar(128)   not null,
    request_hash    varchar(64)    not null,
    failure_reason  varchar(255),
    version         bigint         not null default 0,
    created_at      timestamptz    not null,
    updated_at      timestamptz    not null,
    constraint uk_orders_customer_idempotency unique (customer_id, idempotency_key)
);
create index ix_orders_customer_created on orders (customer_id, created_at desc);
-- Supports reconciliation (find orders stuck in a non-terminal state) and ops dashboards.
create index ix_orders_status_updated on orders (status, updated_at);
create index ix_orders_created on orders (created_at);

create table order_line (
    id         bigserial primary key,
    order_id   uuid           not null references orders (id),
    sku        varchar(64)    not null,
    quantity   int            not null check (quantity > 0),
    unit_price numeric(19, 2) not null check (unit_price > 0)
);
create index ix_order_line_order on order_line (order_id);

create table order_status_history (
    id             bigserial primary key,
    order_id       uuid        not null references orders (id),
    from_status    varchar(32),
    to_status      varchar(32) not null,
    reason         varchar(255),
    correlation_id varchar(64),
    changed_at     timestamptz not null
);
create index ix_order_status_history_order on order_status_history (order_id);
