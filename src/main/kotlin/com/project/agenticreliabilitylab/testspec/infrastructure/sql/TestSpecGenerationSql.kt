package com.project.agenticreliabilitylab.testspec.infrastructure.sql

/** SQL owned by the test specification generation JDBC adapter. */
object TestSpecGenerationSql {
    private val SELECT_RUN = """
        select id, target_system_id, knowledge_snapshot_id, profile_version_id, idempotency_key, configuration_hash,
               model_key, model_id, prompt_version, input_bundle_json, input_checksum, status, prompt_token_count,
               completion_token_count, duration_millis, failure_code, failure_message, requested_by,
               requested_correlation_id, requested_at, started_at, completed_at
        from test_spec_generation_run
    """.trimIndent()

    val INSERT_RUN = """
        insert into test_spec_generation_run (
            id, target_system_id, knowledge_snapshot_id, profile_version_id, idempotency_key, configuration_hash,
            model_key, model_id, prompt_version, input_bundle_json, input_checksum, status, requested_by,
            requested_correlation_id, requested_at
        ) values (
            :id, :targetSystemId, :knowledgeSnapshotId, :profileVersionId, :idempotencyKey, :configurationHash,
            :modelKey, :modelId, :promptVersion, :inputBundleJson, :inputChecksum, :status, :requestedBy,
            :requestedCorrelationId, :requestedAt
        )
    """.trimIndent()

    val FIND_RUN_BY_ID = "$SELECT_RUN where id = :id"
    val FIND_RUN_BY_TARGET_AND_IDEMPOTENCY_KEY =
        "$SELECT_RUN where target_system_id = :targetSystemId and idempotency_key = :idempotencyKey"

    val CLAIM = """
        update test_spec_generation_run
        set status = :running, started_at = :startedAt
        where id = :id and status = :requested
    """.trimIndent()

    val COMPLETE = """
        update test_spec_generation_run
        set status = :completed,
            prompt_token_count = :promptTokenCount,
            completion_token_count = :completionTokenCount,
            duration_millis = :durationMillis,
            failure_code = null,
            failure_message = null,
            completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val INSERT_CANDIDATE = """
        insert into test_spec_generation_candidate (
            id, run_id, ordinal, outcome, spec_key, title, document_json, rejection_reason, specification_id
        ) values (
            :id, :runId, :ordinal, :outcome, :specKey, :title, :documentJson, :rejectionReason, :specificationId
        )
    """.trimIndent()

    val FAIL = """
        update test_spec_generation_run
        set status = :failed,
            failure_code = :failureCode,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where id = :id and status in (:requested, :running)
    """.trimIndent()

    val FIND_IDS_BY_STATUS = """
        select id
        from test_spec_generation_run
        where status = :status
        order by requested_at
    """.trimIndent()

    val FIND_CANDIDATES_BY_RUN_ID = """
        select id, run_id, ordinal, outcome, spec_key, title, document_json, rejection_reason, specification_id
        from test_spec_generation_candidate
        where run_id = :runId
        order by ordinal
    """.trimIndent()
}
