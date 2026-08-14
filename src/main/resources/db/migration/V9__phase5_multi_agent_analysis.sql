create table multi_agent_analysis (
    analysis_run_id varchar(36) primary key,
    configuration_json text not null,
    configuration_hash varchar(128) not null,
    created_at timestamp with time zone not null,
    constraint fk_multi_agent_analysis_run
        foreign key (analysis_run_id) references analysis_run (id)
);

create table agent_step_run (
    id varchar(36) primary key,
    analysis_run_id varchar(36) not null,
    sequence_number integer not null,
    agent_role varchar(40) not null,
    status varchar(40) not null,
    model_key varchar(40) not null,
    model_id varchar(200) not null,
    prompt_version varchar(100) not null,
    tool_policy varchar(40) not null,
    input_checksum varchar(128),
    input_context_json text,
    output_json text,
    output_checksum varchar(128),
    prompt_token_count integer,
    completion_token_count integer,
    duration_millis bigint,
    failure_code varchar(100),
    failure_message varchar(1000),
    requested_at timestamp with time zone not null,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    constraint fk_agent_step_run_analysis
        foreign key (analysis_run_id) references analysis_run (id),
    constraint uq_agent_step_run_sequence unique (analysis_run_id, sequence_number),
    constraint ck_agent_step_run_role
        check (agent_role in ('SUPERVISOR', 'PLANNER', 'ANALYST', 'REVIEWER')),
    constraint ck_agent_step_run_status
        check (status in ('REQUESTED', 'RUNNING', 'COMPLETED', 'FAILED')),
    constraint ck_agent_step_run_tool_policy
        check (tool_policy = 'NO_TOOLS'),
    constraint ck_agent_step_run_prompt_tokens
        check (prompt_token_count is null or prompt_token_count >= 0),
    constraint ck_agent_step_run_completion_tokens
        check (completion_token_count is null or completion_token_count >= 0),
    constraint ck_agent_step_run_duration
        check (duration_millis is null or duration_millis >= 0)
);

create table llm_invocation (
    id varchar(36) primary key,
    agent_step_run_id varchar(36) not null,
    invocation_ordinal integer not null,
    status varchar(40) not null,
    model_key varchar(40) not null,
    model_id varchar(200) not null,
    prompt_version varchar(100) not null,
    tool_call_count integer not null,
    input_checksum varchar(128) not null,
    output_checksum varchar(128),
    prompt_token_count integer,
    completion_token_count integer,
    duration_millis bigint,
    failure_code varchar(100),
    failure_message varchar(1000),
    started_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    constraint fk_llm_invocation_step
        foreign key (agent_step_run_id) references agent_step_run (id),
    constraint uq_llm_invocation_ordinal unique (agent_step_run_id, invocation_ordinal),
    constraint ck_llm_invocation_status
        check (status in ('RUNNING', 'COMPLETED', 'FAILED')),
    constraint ck_llm_invocation_no_tools
        check (tool_call_count = 0),
    constraint ck_llm_invocation_prompt_tokens
        check (prompt_token_count is null or prompt_token_count >= 0),
    constraint ck_llm_invocation_completion_tokens
        check (completion_token_count is null or completion_token_count >= 0),
    constraint ck_llm_invocation_duration
        check (duration_millis is null or duration_millis >= 0)
);

create index idx_agent_step_run_analysis on agent_step_run (analysis_run_id, sequence_number);
create index idx_llm_invocation_step on llm_invocation (agent_step_run_id, invocation_ordinal);
