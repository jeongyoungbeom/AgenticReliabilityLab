create table test_spec_misjudgment_report (
    id uuid primary key,
    target_system_id varchar(100) not null,
    specification_id uuid not null,
    run_id uuid not null,
    trial_number integer not null,
    invariant_id varchar(200) not null,
    reason varchar(2000) not null,
    idempotency_key varchar(200) not null,
    request_hash varchar(64) not null,
    model_key varchar(40) not null,
    model_id varchar(200) not null,
    prompt_version varchar(100) not null,
    status varchar(20) not null,
    drafted_condition varchar(2000),
    drafted_description varchar(2000),
    resulting_specification_id uuid,
    rejection_reason varchar(2000),
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
    constraint fk_test_spec_misjudgment_report_target
        foreign key (target_system_id) references target_system (id),
    constraint fk_test_spec_misjudgment_report_specification
        foreign key (specification_id) references test_specification (id),
    constraint fk_test_spec_misjudgment_report_run foreign key (run_id) references test_spec_run (id),
    constraint fk_test_spec_misjudgment_report_resulting_specification
        foreign key (resulting_specification_id) references test_specification (id),
    constraint uq_test_spec_misjudgment_report_idempotency unique (target_system_id, idempotency_key),
    constraint ck_test_spec_misjudgment_report_status
        check (status in ('REQUESTED', 'RUNNING', 'DRAFTED', 'REJECTED', 'FAILED')),
    constraint ck_test_spec_misjudgment_report_outcome check (
        (status = 'DRAFTED' and drafted_condition is not null and drafted_description is not null
            and resulting_specification_id is not null and rejection_reason is null)
        or (status = 'REJECTED' and drafted_condition is not null and drafted_description is not null
            and rejection_reason is not null and resulting_specification_id is null)
        or (status in ('REQUESTED', 'RUNNING', 'FAILED')
            and resulting_specification_id is null and rejection_reason is null)
    ),
    constraint ck_test_spec_misjudgment_report_metrics check (
        (prompt_token_count is null or prompt_token_count >= 0)
        and (completion_token_count is null or completion_token_count >= 0)
        and (duration_millis is null or duration_millis >= 0)
    ),
    constraint ck_test_spec_misjudgment_report_trial_number check (trial_number >= 1)
);

create index idx_test_spec_misjudgment_report_status on test_spec_misjudgment_report (status, requested_at);
create index idx_test_spec_misjudgment_report_target on test_spec_misjudgment_report (target_system_id, requested_at);
