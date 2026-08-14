create table arl_outbox_job (
    id varchar(36) primary key,
    job_type varchar(100) not null,
    aggregate_id varchar(100) not null,
    status varchar(20) not null,
    attempt_count integer not null,
    available_at timestamp with time zone not null,
    lease_owner varchar(200),
    lease_expires_at timestamp with time zone,
    last_error varchar(1000),
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    constraint uq_arl_outbox_job_type_aggregate unique (job_type, aggregate_id)
);

create index idx_arl_outbox_job_poll
    on arl_outbox_job (status, available_at, created_at);
