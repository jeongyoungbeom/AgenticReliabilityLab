package com.project.agenticreliabilitylab.testspec.infrastructure.sql

/** SQL owned by the test specification misjudgment report JDBC adapter. */
object TestSpecMisjudgmentReportSql {
    private val SELECT = """
        select id, target_system_id, specification_id, run_id, trial_number, invariant_id, reason,
               idempotency_key, request_hash, model_key, model_id, prompt_version, status, drafted_condition,
               drafted_description, resulting_specification_id, rejection_reason, prompt_token_count,
               completion_token_count, duration_millis, failure_code, failure_message, requested_by,
               requested_correlation_id, requested_at, started_at, completed_at
        from test_spec_misjudgment_report
    """.trimIndent()

    val INSERT = """
        insert into test_spec_misjudgment_report (
            id, target_system_id, specification_id, run_id, trial_number, invariant_id, reason, idempotency_key,
            request_hash, model_key, model_id, prompt_version, status, requested_by, requested_correlation_id,
            requested_at
        ) values (
            :id, :targetSystemId, :specificationId, :runId, :trialNumber, :invariantId, :reason, :idempotencyKey,
            :requestHash, :modelKey, :modelId, :promptVersion, :status, :requestedBy, :requestedCorrelationId,
            :requestedAt
        )
    """.trimIndent()

    val FIND_BY_ID = "$SELECT where id = :id"
    val FIND_BY_TARGET_AND_IDEMPOTENCY_KEY =
        "$SELECT where target_system_id = :targetSystemId and idempotency_key = :idempotencyKey"

    val CLAIM = """
        update test_spec_misjudgment_report
        set status = :running, started_at = :startedAt
        where id = :id and status = :requested
    """.trimIndent()

    val COMPLETE = """
        update test_spec_misjudgment_report
        set status = :status,
            drafted_condition = :draftedCondition,
            drafted_description = :draftedDescription,
            resulting_specification_id = :resultingSpecificationId,
            rejection_reason = :rejectionReason,
            prompt_token_count = :promptTokenCount,
            completion_token_count = :completionTokenCount,
            duration_millis = :durationMillis,
            failure_code = null,
            failure_message = null,
            completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val FAIL = """
        update test_spec_misjudgment_report
        set status = :failed,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where id = :id and status in (:requested, :running)
    """.trimIndent()

    val FIND_IDS_BY_STATUS = """
        select id
        from test_spec_misjudgment_report
        where status = :status
        order by requested_at
    """.trimIndent()
}
