package com.project.agenticreliabilitylab.analysis.infrastructure.sql

/** SQL owned by the single-analysis-run JDBC adapter. */
object AnalysisRunSql {
    private val SELECT_ANALYSIS_RUN = """
        select id, experiment_run_id, target_test_batch_id, idempotency_key, agent_type, agent_version, model_key,
               model_id, prompt_version, analysis_dataset_id, input_checksum, input_evidence_count, status, verdict,
               summary, output_json, prompt_token_count, completion_token_count, duration_millis, failure_code,
               failure_message, requested_at, started_at, completed_at
        from analysis_run
    """.trimIndent()

    val FIND_BY_ID = "$SELECT_ANALYSIS_RUN where id = :id"
    val FIND_BY_EXPERIMENT_AND_IDEMPOTENCY_KEY =
        "$SELECT_ANALYSIS_RUN where experiment_run_id = :experimentRunId and idempotency_key = :idempotencyKey"
    val FIND_BY_BATCH_AND_IDEMPOTENCY_KEY =
        "$SELECT_ANALYSIS_RUN where target_test_batch_id = :targetTestBatchId and idempotency_key = :idempotencyKey"
    const val FIND_IDS_BY_AGENT_TYPE_AND_STATUS =
        "select id from analysis_run where agent_type = :agentType and status = :status order by requested_at, id"

    val INSERT_RUN = """
        insert into analysis_run (
            id, experiment_run_id, target_test_batch_id, idempotency_key, agent_type, agent_version, model_key,
            model_id, prompt_version, analysis_dataset_id, input_checksum, input_evidence_count, status, summary,
            output_json, failure_code, failure_message, prompt_token_count, completion_token_count, duration_millis,
            verdict, requested_at, started_at, completed_at
        ) values (
            :id, :experimentRunId, :targetTestBatchId, :idempotencyKey, :agentType, :agentVersion, :modelKey,
            :modelId, :promptVersion, :analysisDatasetId, :inputChecksum, :inputEvidenceCount, :status, null,
            null, null, null, null, null, null, null, :requestedAt, null, null
        )
    """.trimIndent()

    val CLAIM_FOR_EXECUTION = """
        update analysis_run
        set status = :running, started_at = :startedAt
        where id = :id and status = :requested
    """.trimIndent()

    val COMPLETE = """
        update analysis_run
        set status = :status,
            summary = :summary,
            output_json = :outputJson,
            verdict = :verdict,
            prompt_token_count = :promptTokenCount,
            completion_token_count = :completionTokenCount,
            duration_millis = :durationMillis,
            failure_code = null,
            failure_message = null,
            completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val INSERT_FINDING = """
        insert into analysis_finding (
            id, analysis_run_id, ordinal, severity, title, rationale, evidence_refs_json
        ) values (
            :id, :analysisRunId, :ordinal, :severity, :title, :rationale, :evidenceRefsJson
        )
    """.trimIndent()

    val INSERT_RECOMMENDATION = """
        insert into analysis_recommendation (
            id, analysis_run_id, ordinal, priority, title, recommended_action, rationale, evidence_refs_json
        ) values (
            :id, :analysisRunId, :ordinal, :priority, :title, :recommendedAction, :rationale, :evidenceRefsJson
        )
    """.trimIndent()

    val FAIL = """
        update analysis_run
        set status = :failed,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where id = :id and status in (:requested, :running)
    """.trimIndent()

    val FIND_FINDINGS = """
        select id, analysis_run_id, ordinal, severity, title, rationale, evidence_refs_json
        from analysis_finding
        where analysis_run_id = :analysisRunId
        order by ordinal
    """.trimIndent()

    val FIND_RECOMMENDATIONS = """
        select id, analysis_run_id, ordinal, priority, title, recommended_action, rationale, evidence_refs_json
        from analysis_recommendation
        where analysis_run_id = :analysisRunId
        order by ordinal
    """.trimIndent()
}
