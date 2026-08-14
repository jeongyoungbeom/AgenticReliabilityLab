alter table failure_injection_plan
    add column profile_version_id uuid;

alter table failure_injection_plan
    add constraint fk_failure_injection_plan_profile_version
    foreign key (profile_version_id) references target_profile_version (id);

create table target_approval_audit_event (
    id uuid primary key,
    target_system_id varchar(100) not null,
    profile_version_id uuid not null,
    aggregate_type varchar(40) not null,
    aggregate_id uuid not null,
    actor varchar(200) not null,
    correlation_id varchar(100) not null,
    occurred_at timestamp with time zone not null,
    constraint ck_target_approval_audit_aggregate_type
        check (aggregate_type in ('TARGET_TEST_BATCH', 'FAILURE_INJECTION_PLAN')),
    constraint fk_target_approval_audit_profile_version
        foreign key (profile_version_id) references target_profile_version (id)
);

create index idx_target_approval_audit_aggregate
    on target_approval_audit_event (aggregate_type, aggregate_id, occurred_at);
