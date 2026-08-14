create table follow_up_suggestion_run (
    id varchar(36) primary key,
    analysis_run_id varchar(36) not null,
    target_test_batch_id varchar(36) not null,
    idempotency_key varchar(200) not null,
    configuration_hash varchar(128) not null,
    model_key varchar(40) not null,
    model_id varchar(200) not null,
    prompt_version varchar(100) not null,
    input_bundle_json text not null,
    input_checksum varchar(128) not null,
    status varchar(40) not null,
    output_json text,
    prompt_token_count integer,
    completion_token_count integer,
    duration_millis bigint,
    failure_code varchar(100),
    failure_message varchar(2000),
    requested_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    constraint fk_follow_up_suggestion_run_analysis foreign key (analysis_run_id) references analysis_run (id),
    constraint fk_follow_up_suggestion_run_batch foreign key (target_test_batch_id) references target_test_batch (id),
    constraint uq_follow_up_suggestion_run_idempotency unique (analysis_run_id, idempotency_key),
    constraint ck_follow_up_suggestion_run_status check (status in ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED')),
    constraint ck_follow_up_suggestion_run_metrics check (
        (prompt_token_count is null or prompt_token_count >= 0)
        and (completion_token_count is null or completion_token_count >= 0)
        and (duration_millis is null or duration_millis >= 0)
    )
);

create table follow_up_test_suggestion (
    id varchar(36) primary key,
    suggestion_run_id varchar(36) not null,
    ordinal integer not null,
    candidate_id varchar(100) not null,
    candidate_title varchar(500) not null,
    rationale varchar(2000) not null,
    evidence_refs_json text not null,
    constraint fk_follow_up_test_suggestion_run foreign key (suggestion_run_id) references follow_up_suggestion_run (id),
    constraint uq_follow_up_test_suggestion_ordinal unique (suggestion_run_id, ordinal),
    constraint uq_follow_up_test_suggestion_candidate unique (suggestion_run_id, candidate_id),
    constraint ck_follow_up_test_suggestion_ordinal check (ordinal > 0)
);

create index idx_follow_up_suggestion_run_status on follow_up_suggestion_run (status, requested_at);
