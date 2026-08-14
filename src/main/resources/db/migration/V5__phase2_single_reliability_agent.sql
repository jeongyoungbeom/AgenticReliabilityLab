create table analysis_run (
    id varchar(36) primary key,
    experiment_run_id varchar(36) not null,
    idempotency_key varchar(200) not null,
    agent_type varchar(100) not null,
    agent_version varchar(100) not null,
    model_id varchar(200) not null,
    prompt_version varchar(100) not null,
    input_checksum varchar(128),
    input_evidence_count integer,
    status varchar(40) not null,
    summary varchar(2000),
    output_json text,
    failure_code varchar(100),
    failure_message varchar(1000),
    requested_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    constraint fk_analysis_run_experiment
        foreign key (experiment_run_id) references experiment_run (id),
    constraint uq_analysis_run_idempotency
        unique (experiment_run_id, idempotency_key),
    constraint ck_analysis_run_status
        check (status in ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED'))
);

create table analysis_finding (
    id varchar(36) primary key,
    analysis_run_id varchar(36) not null,
    ordinal integer not null,
    severity varchar(20) not null,
    title varchar(300) not null,
    rationale varchar(4000) not null,
    evidence_refs_json text not null,
    constraint fk_analysis_finding_run
        foreign key (analysis_run_id) references analysis_run (id),
    constraint uq_analysis_finding_ordinal unique (analysis_run_id, ordinal),
    constraint ck_analysis_finding_severity
        check (severity in ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create table analysis_recommendation (
    id varchar(36) primary key,
    analysis_run_id varchar(36) not null,
    ordinal integer not null,
    priority varchar(20) not null,
    title varchar(300) not null,
    recommended_action varchar(2000) not null,
    rationale varchar(4000) not null,
    evidence_refs_json text not null,
    constraint fk_analysis_recommendation_run
        foreign key (analysis_run_id) references analysis_run (id),
    constraint uq_analysis_recommendation_ordinal unique (analysis_run_id, ordinal),
    constraint ck_analysis_recommendation_priority
        check (priority in ('P0', 'P1', 'P2', 'P3'))
);

create index idx_analysis_run_experiment on analysis_run (experiment_run_id, requested_at);
