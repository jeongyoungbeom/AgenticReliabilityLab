create table analysis_dataset (
    id varchar(36) primary key,
    experiment_run_id varchar(36) not null,
    contract_version varchar(100) not null,
    evidence_bundle_json text not null,
    evidence_ids_json text not null,
    checksum varchar(128) not null,
    evidence_count integer not null,
    created_at timestamp with time zone not null,
    constraint fk_analysis_dataset_experiment
        foreign key (experiment_run_id) references experiment_run (id),
    constraint ck_analysis_dataset_evidence_count check (evidence_count > 0)
);

alter table analysis_run add column model_key varchar(40);
alter table analysis_run add column analysis_dataset_id varchar(36);
alter table analysis_run add column verdict varchar(40);
alter table analysis_run add column prompt_token_count integer;
alter table analysis_run add column completion_token_count integer;
alter table analysis_run add column duration_millis bigint;

update analysis_run set model_key = 'LEGACY' where model_key is null;
alter table analysis_run alter column model_key set not null;
alter table analysis_run add constraint fk_analysis_run_dataset
    foreign key (analysis_dataset_id) references analysis_dataset (id);
alter table analysis_run add constraint ck_analysis_run_verdict
    check (verdict is null or verdict in ('PASSED', 'FAILED', 'INCONCLUSIVE'));
alter table analysis_run add constraint ck_analysis_run_prompt_tokens
    check (prompt_token_count is null or prompt_token_count >= 0);
alter table analysis_run add constraint ck_analysis_run_completion_tokens
    check (completion_token_count is null or completion_token_count >= 0);
alter table analysis_run add constraint ck_analysis_run_duration
    check (duration_millis is null or duration_millis >= 0);

create index idx_analysis_dataset_experiment on analysis_dataset (experiment_run_id, created_at);
create index idx_analysis_run_dataset on analysis_run (analysis_dataset_id, requested_at);

create table analysis_comparison (
    id varchar(36) primary key,
    experiment_run_id varchar(36) not null,
    analysis_dataset_id varchar(36) not null,
    idempotency_key varchar(200) not null,
    model_keys_json text not null,
    requested_at timestamp with time zone not null,
    constraint fk_analysis_comparison_experiment
        foreign key (experiment_run_id) references experiment_run (id),
    constraint fk_analysis_comparison_dataset
        foreign key (analysis_dataset_id) references analysis_dataset (id),
    constraint uq_analysis_comparison_idempotency
        unique (experiment_run_id, idempotency_key)
);

create table analysis_comparison_run (
    analysis_comparison_id varchar(36) not null,
    model_key varchar(40) not null,
    analysis_run_id varchar(36) not null,
    primary key (analysis_comparison_id, model_key),
    constraint fk_analysis_comparison_run_comparison
        foreign key (analysis_comparison_id) references analysis_comparison (id),
    constraint fk_analysis_comparison_run_analysis
        foreign key (analysis_run_id) references analysis_run (id),
    constraint uq_analysis_comparison_run_analysis unique (analysis_run_id)
);

create table analysis_ground_truth (
    id varchar(36) primary key,
    analysis_dataset_id varchar(36) not null,
    ground_truth_version varchar(100) not null,
    expected_verdict varchar(40) not null,
    required_evidence_ids_json text not null,
    notes varchar(2000),
    created_at timestamp with time zone not null,
    constraint fk_analysis_ground_truth_dataset
        foreign key (analysis_dataset_id) references analysis_dataset (id),
    constraint uq_analysis_ground_truth_version
        unique (analysis_dataset_id, ground_truth_version),
    constraint ck_analysis_ground_truth_verdict
        check (expected_verdict in ('PASSED', 'FAILED', 'INCONCLUSIVE'))
);

create table analysis_evaluation (
    id varchar(36) primary key,
    analysis_run_id varchar(36) not null,
    analysis_ground_truth_id varchar(36) not null,
    evaluation_version varchar(100) not null,
    verdict_match boolean not null,
    cited_required_evidence_count integer not null,
    required_evidence_count integer not null,
    citation_recall double precision not null,
    score double precision not null,
    evaluated_at timestamp with time zone not null,
    constraint fk_analysis_evaluation_run
        foreign key (analysis_run_id) references analysis_run (id),
    constraint fk_analysis_evaluation_ground_truth
        foreign key (analysis_ground_truth_id) references analysis_ground_truth (id),
    constraint uq_analysis_evaluation_version
        unique (analysis_run_id, analysis_ground_truth_id, evaluation_version),
    constraint ck_analysis_evaluation_counts
        check (cited_required_evidence_count >= 0 and required_evidence_count >= 0 and cited_required_evidence_count <= required_evidence_count),
    constraint ck_analysis_evaluation_recall
        check (citation_recall >= 0 and citation_recall <= 1),
    constraint ck_analysis_evaluation_score
        check (score >= 0 and score <= 1)
);
