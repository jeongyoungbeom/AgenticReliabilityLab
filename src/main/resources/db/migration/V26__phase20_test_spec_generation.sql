create table test_spec_generation_run (
    id uuid primary key,
    target_system_id varchar(100) not null,
    knowledge_snapshot_id uuid not null,
    profile_version_id uuid not null,
    idempotency_key varchar(200) not null,
    configuration_hash varchar(128) not null,
    model_key varchar(40) not null,
    model_id varchar(200) not null,
    prompt_version varchar(100) not null,
    input_bundle_json text not null,
    input_checksum varchar(128) not null,
    status varchar(40) not null,
    prompt_token_count integer,
    completion_token_count integer,
    duration_millis bigint,
    failure_code varchar(100),
    failure_message varchar(2000),
    requested_by varchar(200) not null,
    requested_correlation_id varchar(100) not null,
    requested_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    constraint fk_test_spec_generation_run_target foreign key (target_system_id) references target_system (id),
    constraint fk_test_spec_generation_run_snapshot
        foreign key (knowledge_snapshot_id) references target_knowledge_snapshot (id),
    constraint fk_test_spec_generation_run_profile_version
        foreign key (profile_version_id) references target_profile_version (id),
    constraint uq_test_spec_generation_run_idempotency unique (target_system_id, idempotency_key),
    constraint ck_test_spec_generation_run_status check (status in ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED')),
    constraint ck_test_spec_generation_run_metrics check (
        (prompt_token_count is null or prompt_token_count >= 0)
        and (completion_token_count is null or completion_token_count >= 0)
        and (duration_millis is null or duration_millis >= 0)
    )
);

create table test_spec_generation_candidate (
    id uuid primary key,
    run_id uuid not null,
    ordinal integer not null,
    outcome varchar(20) not null,
    spec_key varchar(200) not null,
    title varchar(500) not null,
    document_json text not null,
    rejection_reason varchar(2000),
    specification_id uuid,
    constraint fk_test_spec_generation_candidate_run foreign key (run_id) references test_spec_generation_run (id),
    constraint fk_test_spec_generation_candidate_specification
        foreign key (specification_id) references test_specification (id),
    constraint uq_test_spec_generation_candidate_ordinal unique (run_id, ordinal),
    constraint ck_test_spec_generation_candidate_ordinal check (ordinal > 0),
    constraint ck_test_spec_generation_candidate_outcome check (outcome in ('ACCEPTED', 'REJECTED')),
    constraint ck_test_spec_generation_candidate_specification check (
        (outcome = 'ACCEPTED' and specification_id is not null)
        or (outcome = 'REJECTED' and specification_id is null)
    )
);

create index idx_test_spec_generation_run_status on test_spec_generation_run (status, requested_at);
create index idx_test_spec_generation_run_target on test_spec_generation_run (target_system_id, requested_at);
