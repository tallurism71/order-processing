create table stock_item (
    sku        varchar(64) primary key,
    available  int         not null check (available >= 0),
    reserved   int         not null default 0 check (reserved >= 0),
    version    bigint      not null default 0,
    updated_at timestamptz not null
);

create table reservation (
    id         bigserial primary key,
    order_id   uuid        not null,
    sku        varchar(64) not null references stock_item (sku),
    quantity   int         not null check (quantity > 0),
    status     varchar(16) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_reservation_order_sku unique (order_id, sku)
);
create index ix_reservation_order_status on reservation (order_id, status);
