create table target_system (
    id varchar(100) primary key,
    name varchar(200) not null,
    adapter_type varchar(100) not null,
    environment varchar(20) not null,
    base_url varchar(500) not null,
    allowed_origin varchar(500) not null,
    health_path varchar(300) not null,
    source_repository varchar(500) not null,
    identity_verification varchar(40) not null,
    capabilities varchar(1000) not null,
    enabled boolean not null default true,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint ck_target_environment
        check (environment in ('LOCAL', 'TEST', 'STAGING', 'PRODUCTION')),
    constraint ck_target_identity_verification
        check (identity_verification in ('VERIFIED', 'CONFIGURATION_ONLY', 'UNVERIFIED'))
);

create table campaign_definition (
    id varchar(36) primary key,
    name varchar(200) not null,
    definition_version integer not null,
    timezone varchar(100) not null,
    status varchar(30) not null,
    definition_json text not null,
    created_at timestamp with time zone not null,
    constraint uq_campaign_definition_name_version
        unique (name, definition_version)
);

create table campaign_run (
    id varchar(36) primary key,
    campaign_definition_id varchar(36) not null,
    campaign_definition_version integer not null,
    status varchar(30) not null,
    failure_budget integer not null default 0,
    created_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    constraint fk_campaign_run_definition
        foreign key (campaign_definition_id) references campaign_definition (id)
);

create table campaign_step_run (
    id varchar(36) primary key,
    campaign_run_id varchar(36) not null,
    step_key varchar(120) not null,
    step_definition_version integer not null,
    sequence_number integer not null,
    logical_attempt integer not null,
    status varchar(40) not null,
    experiment_run_id varchar(36),
    dependency_result varchar(40),
    cooldown_until timestamp with time zone,
    lease_owner varchar(200),
    lease_expires_at timestamp with time zone,
    fencing_token bigint not null default 0,
    queued_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    failure_code varchar(100),
    failure_message varchar(1000),
    constraint fk_campaign_step_run_campaign
        foreign key (campaign_run_id) references campaign_run (id),
    constraint uq_campaign_step_run_logical_attempt
        unique (campaign_run_id, step_key, logical_attempt)
);

create table risk_assessment (
    id varchar(36) primary key,
    policy_version varchar(100) not null,
    base_risk varchar(30) not null,
    effective_risk varchar(30) not null,
    matched_rules_json text not null,
    capacity_snapshot_json text not null,
    decision varchar(40) not null,
    created_at timestamp with time zone not null
);

create table planned_run_spec (
    id varchar(36) primary key,
    target_system_id varchar(100) not null,
    experiment_definition_version varchar(100) not null,
    normalized_parameters_json text not null,
    load_profile_json text not null,
    fixture_plan_json text not null,
    expected_target_revision varchar(200),
    expected_service_count integer,
    host_resource_group varchar(120) not null,
    risk_assessment_id varchar(36),
    spec_hash varchar(128) not null,
    created_at timestamp with time zone not null,
    constraint fk_planned_run_spec_target
        foreign key (target_system_id) references target_system (id),
    constraint fk_planned_run_spec_risk
        foreign key (risk_assessment_id) references risk_assessment (id),
    constraint uq_planned_run_spec_hash unique (spec_hash)
);

create table run_manifest (
    id varchar(36) primary key,
    planned_run_spec_id varchar(36) not null,
    phase varchar(20) not null,
    payload_json text not null,
    manifest_hash varchar(128) not null,
    observed_at timestamp with time zone not null,
    constraint fk_run_manifest_spec
        foreign key (planned_run_spec_id) references planned_run_spec (id),
    constraint ck_run_manifest_phase check (phase in ('PRE_RUN', 'POST_RUN')),
    constraint uq_run_manifest_phase unique (planned_run_spec_id, phase)
);

create table experiment_definition (
    id varchar(36) primary key,
    experiment_type varchar(100) not null,
    definition_version varchar(100) not null,
    definition_json text not null,
    enabled boolean not null default false,
    created_at timestamp with time zone not null,
    constraint uq_experiment_definition_type_version
        unique (experiment_type, definition_version)
);

create table experiment_run (
    id varchar(36) primary key,
    target_system_id varchar(100) not null,
    campaign_run_id varchar(36),
    campaign_step_run_id varchar(36),
    experiment_type varchar(100) not null,
    experiment_definition_version varchar(100) not null,
    parameters_json text not null,
    planned_run_spec_id varchar(36) not null,
    pre_run_manifest_id varchar(36),
    post_run_manifest_id varchar(36),
    idempotency_key varchar(200) not null,
    run_status varchar(40) not null,
    system_outcome varchar(40) not null,
    invariant_result_json text,
    outcome_reason varchar(1000),
    evaluated_definition_version varchar(100),
    execution_failure_phase varchar(40),
    execution_failure_owner varchar(40),
    execution_failure_code varchar(100),
    execution_failure_message varchar(1000),
    cleanup_status varchar(40) not null,
    cleanup_failure_code varchar(100),
    cleanup_failure_message varchar(1000),
    cleanup_attempt integer not null default 0,
    queued_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    lease_owner varchar(200),
    lease_expires_at timestamp with time zone,
    last_heartbeat_at timestamp with time zone,
    baseline_experiment_id varchar(36),
    constraint fk_experiment_run_target
        foreign key (target_system_id) references target_system (id),
    constraint fk_experiment_run_campaign
        foreign key (campaign_run_id) references campaign_run (id),
    constraint fk_experiment_run_campaign_step
        foreign key (campaign_step_run_id) references campaign_step_run (id),
    constraint fk_experiment_run_planned_spec
        foreign key (planned_run_spec_id) references planned_run_spec (id),
    constraint fk_experiment_run_pre_manifest
        foreign key (pre_run_manifest_id) references run_manifest (id),
    constraint fk_experiment_run_post_manifest
        foreign key (post_run_manifest_id) references run_manifest (id),
    constraint fk_experiment_run_baseline
        foreign key (baseline_experiment_id) references experiment_run (id),
    constraint uq_experiment_run_idempotency unique (target_system_id, idempotency_key)
);

alter table campaign_step_run
    add constraint fk_campaign_step_run_experiment
    foreign key (experiment_run_id) references experiment_run (id);

create table experiment_action (
    id varchar(36) primary key,
    experiment_run_id varchar(36) not null,
    action_id varchar(120) not null,
    action_type varchar(100) not null,
    request_hash varchar(128) not null,
    status varchar(40) not null,
    target_operation_id varchar(200),
    fencing_token bigint not null,
    attempt integer not null,
    dispatched_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    last_error varchar(1000),
    constraint fk_experiment_action_run
        foreign key (experiment_run_id) references experiment_run (id),
    constraint uq_experiment_action_id unique (experiment_run_id, action_id)
);

create table experiment_resource (
    id varchar(36) primary key,
    experiment_run_id varchar(36) not null,
    action_id varchar(120) not null,
    resource_type varchar(100) not null,
    resource_id varchar(300) not null,
    namespace varchar(200) not null,
    cleanup_status varchar(40) not null,
    cleanup_attempt integer not null default 0,
    last_cleanup_error varchar(1000),
    constraint fk_experiment_resource_run
        foreign key (experiment_run_id) references experiment_run (id),
    constraint uq_experiment_resource unique (experiment_run_id, resource_type, resource_id)
);

create table experiment_evidence (
    id varchar(36) primary key,
    experiment_run_id varchar(36) not null,
    evidence_type varchar(100) not null,
    schema_version varchar(100) not null,
    source varchar(200) not null,
    collector_version varchar(100) not null,
    observed_at timestamp with time zone,
    window_start timestamp with time zone,
    window_end timestamp with time zone,
    unit varchar(80),
    aggregation_method varchar(100),
    sample_count bigint,
    completeness varchar(30) not null,
    payload_json text not null,
    artifact_refs_json text not null,
    checksum varchar(128) not null,
    created_at timestamp with time zone not null,
    constraint fk_experiment_evidence_run
        foreign key (experiment_run_id) references experiment_run (id)
);

create table evidence_artifact (
    id varchar(36) primary key,
    experiment_run_id varchar(36) not null,
    artifact_type varchar(100) not null,
    storage_reference varchar(1000) not null,
    checksum varchar(128) not null,
    content_length bigint,
    retention_until timestamp with time zone,
    created_at timestamp with time zone not null,
    constraint fk_evidence_artifact_run
        foreign key (experiment_run_id) references experiment_run (id)
);

create table workload_lease (
    host_resource_group varchar(120) primary key,
    mode varchar(40) not null,
    owner_type varchar(100) not null,
    owner_id varchar(100) not null,
    lease_owner varchar(200) not null,
    lease_expires_at timestamp with time zone not null,
    fencing_token bigint not null,
    last_heartbeat_at timestamp with time zone not null,
    constraint ck_workload_lease_mode
        check (mode in ('EXPERIMENT_WINDOW', 'LOCAL_LLM_WINDOW'))
);

create index idx_campaign_step_run_status
    on campaign_step_run (campaign_run_id, status, sequence_number);

create index idx_experiment_run_status
    on experiment_run (target_system_id, run_status, queued_at);

create index idx_experiment_action_status
    on experiment_action (experiment_run_id, status);

create index idx_experiment_evidence_run
    on experiment_evidence (experiment_run_id, evidence_type);
