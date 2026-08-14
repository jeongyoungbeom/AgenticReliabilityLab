package com.project.agenticreliabilitylab.analysis.infrastructure.sql

/** SQL owned by the root-cause-report JDBC adapter. */
object RootCauseReportSql {
    private val SELECT_RUN = """
        select id, analysis_run_id, idempotency_key, configuration_hash, model_key, model_id, prompt_version,
               input_bundle_json, input_checksum, output_checksum, status, output_json, prompt_token_count,
               completion_token_count, duration_millis, failure_code, failure_message, requested_at, started_at,
               completed_at
        from root_cause_report_run
    """.trimIndent()

    val INSERT_RUN = """
        insert into root_cause_report_run (
            id, analysis_run_id, idempotency_key, configuration_hash, model_key, model_id, prompt_version,
            input_bundle_json, input_checksum, status, requested_at
        ) values (
            :id, :analysisRunId, :idempotencyKey, :configurationHash, :modelKey, :modelId, :promptVersion,
            :inputBundleJson, :inputChecksum, :status, :requestedAt
        )
    """.trimIndent()

    val FIND_RUN_BY_ID = "$SELECT_RUN where id = :id"
    val FIND_RUN_BY_ANALYSIS_AND_IDEMPOTENCY_KEY =
        "$SELECT_RUN where analysis_run_id = :analysisRunId and idempotency_key = :idempotencyKey"

    val CLAIM = """
        update root_cause_report_run
        set status = :running, started_at = :startedAt
        where id = :id and status = :requested
    """.trimIndent()

    val COMPLETE = """
        update root_cause_report_run
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

    val INSERT_HYPOTHESIS = """
        insert into root_cause_hypothesis (
            id, report_run_id, ordinal, title, confidence, rationale, falsifiability, evidence_refs_json
        ) values (
            :id, :reportRunId, :ordinal, :title, :confidence, :rationale, :falsifiability, :evidenceIdsJson
        )
    """.trimIndent()

    val INSERT_IMPROVEMENT_PROPOSAL = """
        insert into improvement_proposal (
            id, report_run_id, ordinal, hypothesis_ordinal, title, proposed_change, expected_effect, risk,
            evidence_refs_json
        ) values (
            :id, :reportRunId, :ordinal, :hypothesisOrdinal, :title, :proposedChange, :expectedEffect, :risk,
            :evidenceIdsJson
        )
    """.trimIndent()

    val FAIL = """
        update root_cause_report_run
        set status = :failed,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where id = :id and status in (:requested, :running)
    """.trimIndent()

    const val FIND_IDS_BY_STATUS = "select id from root_cause_report_run where status = :status order by requested_at"

    val FIND_HYPOTHESES_BY_REPORT_ID = """
        select id, report_run_id, ordinal, title, confidence, rationale, falsifiability, evidence_refs_json
        from root_cause_hypothesis
        where report_run_id = :reportRunId
        order by ordinal
    """.trimIndent()

    val FIND_IMPROVEMENT_PROPOSALS_BY_REPORT_ID = """
        select id, report_run_id, ordinal, hypothesis_ordinal, title, proposed_change, expected_effect, risk,
               evidence_refs_json
        from improvement_proposal
        where report_run_id = :reportRunId
        order by ordinal
    """.trimIndent()
}
