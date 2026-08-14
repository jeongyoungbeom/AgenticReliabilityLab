package com.project.agenticreliabilitylab.analysis.infrastructure.sql

/** SQL owned by the multi-agent analysis JDBC adapter. */
object MultiAgentAnalysisSql {
    val INSERT_CONFIGURATION = """
        insert into multi_agent_analysis (analysis_run_id, configuration_json, configuration_hash, created_at)
        values (:analysisRunId, :configurationJson, :configurationHash, :createdAt)
    """.trimIndent()

    val FIND_CONFIGURATION = """
        select analysis_run_id, configuration_json, configuration_hash, created_at
        from multi_agent_analysis
        where analysis_run_id = :analysisRunId
    """.trimIndent()

    val FIND_STEPS = """
        select id, analysis_run_id, sequence_number, agent_role, status, model_key, model_id, prompt_version,
               tool_policy, input_checksum, input_context_json, output_json, output_checksum,
               prompt_token_count, completion_token_count, duration_millis, failure_code, failure_message,
               requested_at, started_at, completed_at
        from agent_step_run
        where analysis_run_id = :analysisRunId
        order by sequence_number
    """.trimIndent()

    val FIND_INVOCATIONS = """
        select invocation.id, invocation.agent_step_run_id, invocation.invocation_ordinal, invocation.status,
               invocation.model_key, invocation.model_id, invocation.prompt_version, invocation.tool_call_count,
               invocation.input_checksum, invocation.output_checksum, invocation.prompt_token_count,
               invocation.completion_token_count, invocation.duration_millis, invocation.failure_code,
               invocation.failure_message, invocation.started_at, invocation.completed_at
        from llm_invocation invocation
        join agent_step_run step on step.id = invocation.agent_step_run_id
        where step.analysis_run_id = :analysisRunId
        order by step.sequence_number, invocation.invocation_ordinal
    """.trimIndent()

    val CLAIM_STEP = """
        update agent_step_run
        set status = :running,
            input_checksum = :inputChecksum,
            input_context_json = :inputContextJson,
            started_at = :startedAt
        where id = :id and status = :requested
    """.trimIndent()

    val INSERT_INVOCATION = """
        insert into llm_invocation (
            id, agent_step_run_id, invocation_ordinal, status, model_key, model_id, prompt_version,
            tool_call_count, input_checksum, output_checksum, prompt_token_count, completion_token_count,
            duration_millis, failure_code, failure_message, started_at, completed_at
        ) values (
            :id, :agentStepRunId, :ordinal, :status, :modelKey, :modelId, :promptVersion,
            0, :inputChecksum, null, null, null, null, null, null, :startedAt, null
        )
    """.trimIndent()

    val COMPLETE_INVOCATION = """
        update llm_invocation
        set status = :completed,
            output_checksum = :outputChecksum,
            prompt_token_count = :promptTokenCount,
            completion_token_count = :completionTokenCount,
            duration_millis = :durationMillis,
            completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val FAIL_INVOCATION = """
        update llm_invocation
        set status = :failed,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val COMPLETE_STEP = """
        update agent_step_run
        set status = :completed,
            output_json = :outputJson,
            output_checksum = :outputChecksum,
            prompt_token_count = :promptTokenCount,
            completion_token_count = :completionTokenCount,
            duration_millis = :durationMillis,
            failure_code = null,
            failure_message = null,
            completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val FAIL_STEP = """
        update agent_step_run
        set status = :failed,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where id = :id and status in (:requested, :running)
    """.trimIndent()

    val FAIL_INCOMPLETE_STEPS = """
        update agent_step_run
        set status = :failed,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where analysis_run_id = :analysisRunId and status in (:requested, :running)
    """.trimIndent()

    val INSERT_STEP = """
        insert into agent_step_run (
            id, analysis_run_id, sequence_number, agent_role, status, model_key, model_id, prompt_version,
            tool_policy, input_checksum, input_context_json, output_json, output_checksum,
            prompt_token_count, completion_token_count, duration_millis, failure_code, failure_message,
            requested_at, started_at, completed_at
        ) values (
            :id, :analysisRunId, :sequenceNumber, :role, :status, :modelKey, :modelId, :promptVersion,
            'NO_TOOLS', null, null, null, null, null, null, null, null, null, :requestedAt, null, null
        )
    """.trimIndent()

    val FIND_ANALYSIS_RUN_IDS_BY_STATUS = """
        select run.id
        from analysis_run run
        join multi_agent_analysis multi on multi.analysis_run_id = run.id
        where run.status = :status
        order by run.requested_at
    """.trimIndent()
}
