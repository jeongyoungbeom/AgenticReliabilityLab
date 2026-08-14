package com.project.agenticreliabilitylab.analysis.infrastructure.sql

/** SQL owned by the follow-up-suggestion JDBC adapter. */
object FollowUpSuggestionSql {
    private val SELECT_RUN = """
        select id, analysis_run_id, target_test_batch_id, idempotency_key, configuration_hash, model_key, model_id,
               prompt_version, input_bundle_json, input_checksum, status, output_json, prompt_token_count,
               completion_token_count, duration_millis, failure_code, failure_message, requested_at, started_at,
               completed_at
        from follow_up_suggestion_run
    """.trimIndent()

    val INSERT_RUN = """
        insert into follow_up_suggestion_run (
            id, analysis_run_id, target_test_batch_id, idempotency_key, configuration_hash, model_key, model_id,
            prompt_version, input_bundle_json, input_checksum, status, requested_at
        ) values (
            :id, :analysisRunId, :targetTestBatchId, :idempotencyKey, :configurationHash, :modelKey, :modelId,
            :promptVersion, :inputBundleJson, :inputChecksum, :status, :requestedAt
        )
    """.trimIndent()

    val FIND_RUN_BY_ID = "$SELECT_RUN where id = :id"
    val FIND_RUN_BY_ANALYSIS_AND_IDEMPOTENCY_KEY =
        "$SELECT_RUN where analysis_run_id = :analysisRunId and idempotency_key = :idempotencyKey"

    val CLAIM = """
        update follow_up_suggestion_run
        set status = :running, started_at = :startedAt
        where id = :id and status = :requested
    """.trimIndent()

    val COMPLETE = """
        update follow_up_suggestion_run
        set status = :completed,
            output_json = :outputJson,
            prompt_token_count = :promptTokenCount,
            completion_token_count = :completionTokenCount,
            duration_millis = :durationMillis,
            failure_code = null,
            failure_message = null,
            completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val INSERT_SUGGESTION = """
        insert into follow_up_test_suggestion (
            id, suggestion_run_id, ordinal, candidate_id, candidate_title, rationale, evidence_refs_json
        ) values (
            :id, :suggestionRunId, :ordinal, :candidateId, :candidateTitle, :rationale, :evidenceIdsJson
        )
    """.trimIndent()

    val FAIL = """
        update follow_up_suggestion_run
        set status = :failed,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where id = :id and status in (:requested, :running)
    """.trimIndent()

    val FIND_IDS_BY_STATUS = """
        select id
        from follow_up_suggestion_run
        where status = :status
        order by requested_at
    """.trimIndent()

    val FIND_SUGGESTIONS_BY_RUN_ID = """
        select id, suggestion_run_id, ordinal, candidate_id, candidate_title, rationale, evidence_refs_json
        from follow_up_test_suggestion
        where suggestion_run_id = :runId
        order by ordinal
    """.trimIndent()
}
