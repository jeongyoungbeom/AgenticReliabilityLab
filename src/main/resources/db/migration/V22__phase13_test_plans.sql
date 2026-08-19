create table test_plan (
    id uuid primary key,
    target_system_id varchar(100) not null,
    knowledge_snapshot_id uuid not null,
    generation_id uuid not null,
    profile_version_id uuid not null,
    status varchar(30) not null,
    required_confirmation varchar(60) not null,
    idempotency_key varchar(200) not null,
    request_hash varchar(64) not null,
    created_by varchar(200) not null,
    created_correlation_id varchar(100) not null,
    created_at timestamp with time zone not null,
    approved_by varchar(200),
    approved_correlation_id varchar(100),
    approved_at timestamp with time zone,
    dispatched_at timestamp with time zone,
    terminal_reason varchar(200),
    constraint ck_test_plan_status
        check (status in ('PENDING_APPROVAL', 'APPROVED', 'DISPATCHED', 'CANCELLED', 'SUPERSEDED')),
    constraint fk_test_plan_generation
        foreign key (generation_id) references test_candidate_generation (id),
    constraint fk_test_plan_snapshot
        foreign key (knowledge_snapshot_id) references target_knowledge_snapshot (id),
    constraint fk_test_plan_profile_version
        foreign key (profile_version_id) references target_profile_version (id),
    constraint uq_test_plan_idempotency
        unique (target_system_id, idempotency_key)
);

create table test_plan_item (
    id uuid primary key,
    plan_id uuid not null,
    sequence_number integer not null,
    candidate_id uuid not null,
    category varchar(30) not null,
    risk varchar(20) not null,
    binding_kind varchar(30) not null,
    target_test_candidate_ids text not null,
    constraint fk_test_plan_item_plan
        foreign key (plan_id) references test_plan (id),
    constraint fk_test_plan_item_candidate
        foreign key (candidate_id) references test_candidate (id),
    constraint uq_test_plan_item_sequence
        unique (plan_id, sequence_number)
);

create table test_plan_execution_reference (
    id uuid primary key,
    plan_id uuid not null,
    kind varchar(30) not null,
    reference_id uuid not null,
    created_at timestamp with time zone not null,
    constraint ck_test_plan_execution_reference_kind
        check (kind in ('TARGET_TEST_BATCH', 'EXPERIMENT_RUN')),
    constraint fk_test_plan_execution_reference_plan
        foreign key (plan_id) references test_plan (id),
    constraint uq_test_plan_execution_reference
        unique (plan_id, kind, reference_id)
);

create index idx_test_plan_target
    on test_plan (target_system_id, created_at);
