-- Phase 9 persists evidence-grounded advisory reports only. There is no command,
-- approval, Target write, deployment, or before/after verification state here.
create table root_cause_report_run (
    id varchar(36) primary key,
    analysis_run_id varchar(36) not null,
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
    constraint fk_root_cause_report_run_analysis foreign key (analysis_run_id) references analysis_run (id),
    constraint uq_root_cause_report_run_idempotency unique (analysis_run_id, idempotency_key),
    constraint ck_root_cause_report_run_status check (status in ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED')),
    constraint ck_root_cause_report_run_metrics check (
        (prompt_token_count is null or prompt_token_count >= 0)
        and (completion_token_count is null or completion_token_count >= 0)
        and (duration_millis is null or duration_millis >= 0)
    )
);

create table root_cause_hypothesis (
    id varchar(36) primary key,
    report_run_id varchar(36) not null,
    ordinal integer not null,
    title varchar(300) not null,
    confidence varchar(20) not null,
    rationale varchar(4000) not null,
    falsifiability varchar(2000) not null,
    evidence_refs_json text not null,
    constraint fk_root_cause_hypothesis_report foreign key (report_run_id) references root_cause_report_run (id),
    constraint uq_root_cause_hypothesis_ordinal unique (report_run_id, ordinal),
    constraint ck_root_cause_hypothesis_ordinal check (ordinal > 0),
    constraint ck_root_cause_hypothesis_confidence check (confidence in ('LOW', 'MEDIUM', 'HIGH'))
);

create table improvement_proposal (
    id varchar(36) primary key,
    report_run_id varchar(36) not null,
    ordinal integer not null,
    hypothesis_ordinal integer not null,
    title varchar(300) not null,
    proposed_change varchar(4000) not null,
    expected_effect varchar(2000) not null,
    risk varchar(2000) not null,
    evidence_refs_json text not null,
    constraint fk_improvement_proposal_report foreign key (report_run_id) references root_cause_report_run (id),
    constraint uq_improvement_proposal_ordinal unique (report_run_id, ordinal),
    constraint ck_improvement_proposal_ordinal check (ordinal > 0),
    constraint ck_improvement_proposal_hypothesis_ordinal check (hypothesis_ordinal > 0)
);

create index idx_root_cause_report_run_status on root_cause_report_run (status, requested_at);
