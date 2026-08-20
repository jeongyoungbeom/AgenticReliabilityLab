create table test_specification (
    id uuid primary key,
    target_system_id varchar(100) not null,
    spec_key varchar(200) not null,
    version integer not null,
    title varchar(500) not null,
    profile_version_id uuid not null,
    source varchar(30) not null,
    category varchar(30) not null,
    risk varchar(30) not null,
    status varchar(30) not null,
    document_json text not null,
    checksum varchar(64) not null,
    created_by varchar(200) not null,
    created_correlation_id varchar(100) not null,
    created_at timestamp with time zone not null,
    approved_by varchar(200),
    approved_correlation_id varchar(100),
    approved_at timestamp with time zone,
    terminal_reason varchar(500),
    constraint ck_test_specification_version check (version > 0),
    constraint ck_test_specification_source
        check (source in ('RULE_GENERATED', 'MODEL_PROPOSED', 'USER_REQUESTED')),
    constraint ck_test_specification_category
        check (category in (
            'AVAILABILITY', 'CONTRACT_INPUT', 'WORKFLOW', 'RETRY_RECOVERY',
            'IDEMPOTENCY', 'CONCURRENCY', 'CONSISTENCY'
        )),
    constraint ck_test_specification_risk check (risk in ('SAFE', 'MODERATE', 'DESTRUCTIVE')),
    constraint ck_test_specification_status
        check (status in ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SUPERSEDED')),
    constraint fk_test_specification_target
        foreign key (target_system_id) references target_system (id),
    constraint fk_test_specification_profile_version
        foreign key (profile_version_id) references target_profile_version (id),
    constraint uq_test_specification_version unique (target_system_id, spec_key, version),
    constraint uq_test_specification_checksum
        unique (target_system_id, spec_key, profile_version_id, checksum)
);

create table test_spec_run (
    id uuid primary key,
    specification_id uuid not null,
    target_system_id varchar(100) not null,
    profile_version_id uuid not null,
    status varchar(30) not null,
    idempotency_key varchar(200) not null,
    request_hash varchar(64) not null,
    requested_trials integer not null,
    result_outcome varchar(30),
    trials_run integer,
    trials_violated integer,
    trials_inconclusive integer,
    cleanup_verified boolean,
    created_by varchar(200) not null,
    created_correlation_id varchar(100) not null,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    failure varchar(1000),
    constraint ck_test_spec_run_requested_trials check (requested_trials > 0),
    constraint ck_test_spec_run_status
        check (status in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'RECOVERY_REQUIRED')),
    constraint ck_test_spec_run_result_outcome
        check (result_outcome is null or result_outcome in ('PASSED', 'VIOLATED', 'INCONCLUSIVE')),
    constraint fk_test_spec_run_specification
        foreign key (specification_id) references test_specification (id),
    constraint fk_test_spec_run_target
        foreign key (target_system_id) references target_system (id),
    constraint fk_test_spec_run_profile_version
        foreign key (profile_version_id) references target_profile_version (id),
    constraint uq_test_spec_run_idempotency unique (target_system_id, idempotency_key)
);

create table test_spec_trial_result (
    run_id uuid not null,
    trial_number integer not null,
    outcome varchar(30) not null,
    state_changed boolean not null,
    completed boolean not null,
    failure varchar(1000),
    verdicts_json text not null,
    timings_json text not null,
    primary key (run_id, trial_number),
    constraint ck_test_spec_trial_number check (trial_number > 0),
    constraint ck_test_spec_trial_outcome check (outcome in ('PASSED', 'VIOLATED', 'INCONCLUSIVE')),
    constraint fk_test_spec_trial_run foreign key (run_id) references test_spec_run (id)
);

create table test_spec_reset_result (
    run_id uuid not null,
    sequence_number integer not null,
    performed boolean not null,
    verified boolean not null,
    checks_json text not null,
    failure varchar(1000),
    primary key (run_id, sequence_number),
    constraint ck_test_spec_reset_sequence check (sequence_number > 0),
    constraint fk_test_spec_reset_run foreign key (run_id) references test_spec_run (id)
);

create index idx_test_specification_target on test_specification (target_system_id, created_at);
create index idx_test_spec_run_specification on test_spec_run (specification_id, created_at);
create index idx_test_spec_run_target on test_spec_run (target_system_id, created_at);
