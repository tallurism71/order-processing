create table reconciliation_result (
    business_date    date           not null,
    order_id         uuid           not null,
    status           varchar(32)    not null,
    total_amount     numeric(19, 2) not null,
    currency         varchar(3)       not null,
    issue            varchar(32)    not null,
    job_execution_id bigint         not null,
    reconciled_at    timestamptz    not null,
    primary key (business_date, order_id)
);
create index ix_recon_issue on reconciliation_result (business_date, issue) where issue <> 'NONE';

-- Proof of delivery for every extract: what was sent, where, and how to verify it.
create table extract_audit (
    id               bigserial primary key,
    business_date    date           not null,
    job_execution_id bigint         not null,
    record_count     bigint         not null,
    control_total    numeric(21, 2) not null,
    sha256           char(64)       not null,
    location         varchar(500)   not null,
    published_at     timestamptz    not null
);
