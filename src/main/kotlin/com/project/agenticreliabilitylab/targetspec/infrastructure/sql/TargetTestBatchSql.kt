package com.project.agenticreliabilitylab.targetspec.infrastructure.sql

/** SQL owned by the target-test-batch JDBC adapter. */
object TargetTestBatchSql {
    private val SELECT_BATCH = """
        select id, target_system_id, profile_version_id, idempotency_key, request_hash, status, approved_at, approved_by,
               approval_correlation_id,
               queued_at, started_at, completed_at, failure_message
        from target_test_batch
    """.trimIndent()

    val FIND_BATCH_BY_ID = "$SELECT_BATCH where id = :id"
    val FIND_BATCH_BY_TARGET_AND_IDEMPOTENCY_KEY =
        "$SELECT_BATCH where target_system_id = :targetSystemId and idempotency_key = :idempotencyKey"

    val FIND_ITEMS_BY_BATCH_ID = """
        select id, batch_id, candidate_id, sequence_number, candidate_kind, title, method, path,
               expected_status_codes, timeout_millis, status, http_status, latency_millis,
               result_json, failure_message, started_at, completed_at
        from target_test_batch_item
        where batch_id = :batchId
        order by sequence_number, id
    """.trimIndent()

    val INSERT_BATCH = """
        insert into target_test_batch (
            id, target_system_id, profile_version_id, idempotency_key, request_hash, status, approved_at, approved_by,
            approval_correlation_id,
            queued_at, started_at, completed_at, failure_message
        ) values (
            :id, :targetSystemId, :profileVersionId, :idempotencyKey, :requestHash, :status, null, null,
            null,
            :queuedAt, null, null, null
        )
    """.trimIndent()

    val INSERT_ITEM = """
        insert into target_test_batch_item (
            id, batch_id, candidate_id, sequence_number, candidate_kind, title, method, path,
            expected_status_codes, timeout_millis, status, http_status, latency_millis,
            result_json, failure_message, started_at, completed_at
        ) values (
            :id, :batchId, :candidateId, :sequenceNumber, :candidateKind, :title, :method, :path,
            :expectedStatusCodes, :timeoutMillis, :status, null, null,
            null, null, null, null
        )
    """.trimIndent()

    val APPROVE = """
        update target_test_batch
        set status = :approved,
            approved_at = :approvedAt,
            approved_by = :approvedBy,
            approval_correlation_id = :approvalCorrelationId
        where id = :id and status = :pendingApproval
    """.trimIndent()

    val CANCEL_PENDING_APPROVAL = """
        update target_test_batch
        set status = :cancelled, failure_message = :message, completed_at = :completedAt
        where id = :id and status = :pendingApproval
    """.trimIndent()

    val CLAIM_FOR_EXECUTION = """
        update target_test_batch
        set status = :running, started_at = coalesce(started_at, :startedAt)
        where id = :id and status = :approved
    """.trimIndent()

    val CLAIM_ITEM = """
        update target_test_batch_item
        set status = :running, started_at = :startedAt
        where id = :id and status = :pending
    """.trimIndent()

    val COMPLETE_ITEM = """
        update target_test_batch_item
        set status = :status,
            http_status = :httpStatus,
            latency_millis = :latencyMillis,
            result_json = :resultJson,
            failure_message = :failureMessage,
            completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val COMPLETE_BATCH = """
        update target_test_batch
        set status = :status, failure_message = :failureMessage, completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val MARK_SCHEDULING_FAILED = """
        update target_test_batch
        set status = :failed, failure_message = :message, completed_at = :completedAt
        where id = :id and status = :approved
    """.trimIndent()

    val BLOCK_RUNNING_ITEMS_FOR_RECOVERY = """
        update target_test_batch_item
        set status = :blocked, failure_message = :message, completed_at = :completedAt
        where batch_id = :batchId and status = :running
    """.trimIndent()

    val MARK_BATCH_RECOVERY_REQUIRED = """
        update target_test_batch
        set status = :recoveryRequired, failure_message = :message, completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    const val FIND_APPROVED_BATCH_IDS = "select id from target_test_batch where status = :approved"
    const val FIND_RUNNING_BATCH_IDS = "select id from target_test_batch where status = :running"
}
