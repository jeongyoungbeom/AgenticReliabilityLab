-- Phase 8 stores approval-gated plans only. There is deliberately no execution table, command, or worker.
create table failure_injection_plan (
    id varchar(36) primary key,
    target_system_id varchar(100) not null,
    idempotency_key varchar(200) not null,
    request_hash varchar(128) not null,
    status varchar(40) not null,
    approved_at timestamp with time zone,
    created_at timestamp with time zone not null,
    constraint fk_failure_injection_plan_target foreign key (target_system_id) references target_system (id),
    constraint uq_failure_injection_plan_idempotency unique (target_system_id, idempotency_key),
    constraint ck_failure_injection_plan_status check (status in ('PENDING_APPROVAL', 'APPROVED'))
);

create table failure_injection_plan_item (
    id varchar(36) primary key,
    plan_id varchar(36) not null,
    sequence_number integer not null,
    candidate_id varchar(100) not null,
    injection_type varchar(50) not null,
    risk varchar(40) not null,
    title varchar(200) not null,
    recovery_expectation varchar(1000) not null,
    constraint fk_failure_injection_plan_item_plan foreign key (plan_id) references failure_injection_plan (id),
    constraint uq_failure_injection_plan_item_sequence unique (plan_id, sequence_number),
    constraint uq_failure_injection_plan_item_candidate unique (plan_id, candidate_id),
    constraint ck_failure_injection_plan_item_sequence check (sequence_number > 0),
    constraint ck_failure_injection_plan_item_type check (injection_type in ('CONSUMER_RESTART', 'REDIS_FAILURE', 'SERVICE_RESTART', 'SHIPPING_SAGA_FAILURE')),
    constraint ck_failure_injection_plan_item_risk check (risk in ('MODERATE', 'DESTRUCTIVE'))
);
