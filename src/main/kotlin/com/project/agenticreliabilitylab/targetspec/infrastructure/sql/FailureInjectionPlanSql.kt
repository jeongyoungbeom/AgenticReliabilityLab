package com.project.agenticreliabilitylab.targetspec.infrastructure.sql

/** SQL owned by the failure-injection-plan JDBC adapter. */
object FailureInjectionPlanSql {
    private val SELECT_PLAN = """
        select id, target_system_id, profile_version_id, idempotency_key, request_hash, status, approved_at, approved_by,
               approval_correlation_id, created_at
        from failure_injection_plan
    """.trimIndent()

    val INSERT_PLAN = """
        insert into failure_injection_plan (
            id, target_system_id, profile_version_id, idempotency_key, request_hash, status, approved_at, approved_by,
            approval_correlation_id, created_at
        ) values (
            :id, :targetSystemId, :profileVersionId, :idempotencyKey, :requestHash, :status, null, null, null, :createdAt
        )
    """.trimIndent()

    val INSERT_ITEM = """
        insert into failure_injection_plan_item (
            id, plan_id, sequence_number, candidate_id, injection_type, risk, title, recovery_expectation
        ) values (
            :id, :planId, :sequence, :candidateId, :type, :risk, :title, :recoveryExpectation
        )
    """.trimIndent()

    val FIND_PLAN_BY_ID = "$SELECT_PLAN where id = :id"
    val FIND_PLAN_BY_TARGET_AND_IDEMPOTENCY_KEY =
        "$SELECT_PLAN where target_system_id = :targetId and idempotency_key = :key"

    val FIND_ITEMS_BY_PLAN_ID = """
        select id, plan_id, sequence_number, candidate_id, injection_type, risk, title, recovery_expectation
        from failure_injection_plan_item
        where plan_id = :planId
        order by sequence_number
    """.trimIndent()

    val APPROVE = """
        update failure_injection_plan
        set status = :approved,
            approved_at = :approvedAt,
            approved_by = :approvedBy,
            approval_correlation_id = :approvalCorrelationId
        where id = :id and status = :pending
    """.trimIndent()
}
