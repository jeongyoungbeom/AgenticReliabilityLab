create table pilot_test_session (
    id uuid primary key,
    target_system_id varchar(100) not null,
    profile_version_id uuid not null,
    status varchar(30) not null,
    idempotency_key varchar(120) not null,
    request_hash varchar(64) not null,
    result_outcome varchar(30),
    cleanup_verified boolean,
    created_by varchar(200) not null,
    created_correlation_id varchar(100) not null,
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    failure varchar(1000),
    constraint ck_pilot_test_session_status check (status in ('RUNNING', 'COMPLETED', 'RECOVERY_REQUIRED')),
    constraint ck_pilot_test_session_outcome check (result_outcome is null or result_outcome in ('PASSED', 'VIOLATED', 'INCONCLUSIVE')),
    constraint fk_pilot_test_session_target foreign key (target_system_id) references target_system (id),
    constraint fk_pilot_test_session_profile foreign key (profile_version_id) references target_profile_version (id),
    constraint uq_pilot_test_session_idempotency unique (target_system_id, idempotency_key)
);

create table pilot_test_session_item (
    session_id uuid not null,
    sequence_number integer not null,
    candidate_id varchar(100) not null,
    specification_id uuid,
    test_spec_run_id uuid,
    status varchar(30) not null,
    result_outcome varchar(30),
    cleanup_verified boolean,
    failure_code varchar(100),
    failure_message varchar(1000),
    completed_at timestamp with time zone not null,
    primary key (session_id, sequence_number),
    constraint ck_pilot_test_session_item_sequence check (sequence_number > 0),
    constraint ck_pilot_test_session_item_status check (status in ('COMPLETED', 'FAILED', 'RECOVERY_REQUIRED')),
    constraint ck_pilot_test_session_item_outcome check (result_outcome is null or result_outcome in ('PASSED', 'VIOLATED', 'INCONCLUSIVE')),
    constraint fk_pilot_test_session_item_session foreign key (session_id) references pilot_test_session (id),
    constraint fk_pilot_test_session_item_specification foreign key (specification_id) references test_specification (id),
    constraint fk_pilot_test_session_item_run foreign key (test_spec_run_id) references test_spec_run (id),
    constraint uq_pilot_test_session_item_candidate unique (session_id, candidate_id)
);

create index idx_pilot_test_session_target on pilot_test_session (target_system_id, created_at desc);
