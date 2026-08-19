package com.project.agenticreliabilitylab.testplan.infrastructure.sql

/** SQL owned by the test-plan JDBC adapter. */
object TestPlanSql {
    private val SELECT_PLAN = """
        select id, target_system_id, knowledge_snapshot_id, generation_id, profile_version_id, status,
               required_confirmation, idempotency_key, request_hash, created_by, created_correlation_id, created_at,
               approved_by, approved_correlation_id, approved_at, dispatched_at, terminal_reason
        from test_plan
    """.trimIndent()

    val INSERT_PLAN = """
        insert into test_plan (
            id, target_system_id, knowledge_snapshot_id, generation_id, profile_version_id, status,
            required_confirmation, idempotency_key, request_hash, created_by, created_correlation_id, created_at,
            approved_by, approved_correlation_id, approved_at, dispatched_at, terminal_reason
        ) values (
            :id, :targetSystemId, :knowledgeSnapshotId, :generationId, :profileVersionId, :status,
            :requiredConfirmation, :idempotencyKey, :requestHash, :createdBy, :createdCorrelationId, :createdAt,
            null, null, null, null, null
        )
    """.trimIndent()

    val INSERT_ITEM = """
        insert into test_plan_item (
            id, plan_id, sequence_number, candidate_id, category, risk, binding_kind, target_test_candidate_ids
        ) values (
            :id, :planId, :sequenceNumber, :candidateId, :category, :risk, :bindingKind, :targetTestCandidateIds
        )
    """.trimIndent()

    val INSERT_EXECUTION_REFERENCE = """
        insert into test_plan_execution_reference (id, plan_id, kind, reference_id, created_at)
        values (:id, :planId, :kind, :referenceId, :createdAt)
    """.trimIndent()

    val FIND_BY_ID = "$SELECT_PLAN where id = :id"

    val FIND_BY_TARGET_AND_IDEMPOTENCY_KEY =
        "$SELECT_PLAN where target_system_id = :targetSystemId and idempotency_key = :idempotencyKey"

    val FIND_BY_TARGET = """
        $SELECT_PLAN
        where target_system_id = :targetSystemId
        order by created_at desc
        limit :limit
    """.trimIndent()

    val FIND_ITEMS = """
        select id, plan_id, sequence_number, candidate_id, category, risk, binding_kind, target_test_candidate_ids
        from test_plan_item
        where plan_id = :planId
        order by sequence_number
    """.trimIndent()

    val FIND_EXECUTION_REFERENCES = """
        select id, plan_id, kind, reference_id, created_at
        from test_plan_execution_reference
        where plan_id = :planId
        order by created_at
    """.trimIndent()

    val APPROVE = """
        update test_plan
        set status = :approved, approved_by = :actor, approved_correlation_id = :correlationId,
            approved_at = :approvedAt
        where id = :id and status = :pending
    """.trimIndent()

    val MARK_DISPATCHED = """
        update test_plan
        set status = :dispatched, dispatched_at = :dispatchedAt
        where id = :id and status = :approved
    """.trimIndent()

    val MARK_TERMINAL = """
        update test_plan
        set status = :status, terminal_reason = :reason
        where id = :id and status in (:pending, :approved)
    """.trimIndent()
}
