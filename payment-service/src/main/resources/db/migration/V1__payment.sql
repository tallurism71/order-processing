create table payment (
    id                uuid primary key,
    order_id          uuid           not null,
    amount            numeric(19, 2) not null,
    currency          varchar(3)       not null,
    status            varchar(16)    not null,
    gateway_reference varchar(100),
    failure_reason    varchar(255),
    created_at        timestamptz    not null,
    constraint uk_payment_order unique (order_id)
);
