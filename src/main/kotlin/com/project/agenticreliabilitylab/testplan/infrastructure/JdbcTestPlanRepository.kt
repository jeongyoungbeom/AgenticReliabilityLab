package com.project.agenticreliabilitylab.testplan.infrastructure

import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBindingKind
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateCategory
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateRisk
import com.project.agenticreliabilitylab.testplan.application.port.TestPlanStore
import com.project.agenticreliabilitylab.testplan.domain.TestPlan
import com.project.agenticreliabilitylab.testplan.domain.TestPlanConfirmation
import com.project.agenticreliabilitylab.testplan.domain.TestPlanExecutionKind
import com.project.agenticreliabilitylab.testplan.domain.TestPlanExecutionReference
import com.project.agenticreliabilitylab.testplan.domain.TestPlanItem
import com.project.agenticreliabilitylab.testplan.domain.TestPlanStatus
import com.project.agenticreliabilitylab.testplan.infrastructure.sql.TestPlanSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // One adapter persists the plan aggregate with its items and execution references.
class JdbcTestPlanRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) : TestPlanStore {
    @Transactional
    override fun create(plan: TestPlan, items: List<TestPlanItem>) {
        jdbcClient.sql(TestPlanSql.INSERT_PLAN)
            .params(
                mapOf(
                    "id" to plan.id,
                    "targetSystemId" to plan.targetSystemId,
                    "knowledgeSnapshotId" to plan.knowledgeSnapshotId,
                    "generationId" to plan.generationId,
                    "profileVersionId" to plan.profileVersionId,
                    "status" to plan.status.name,
                    "requiredConfirmation" to plan.requiredConfirmation.name,
                    "idempotencyKey" to plan.idempotencyKey,
                    "requestHash" to plan.requestHash,
                    "createdBy" to plan.createdBy,
                    "createdCorrelationId" to plan.createdCorrelationId,
                    "createdAt" to Timestamp.from(plan.createdAt),
                ),
            )
            .update()
        items.forEach(::insertItem)
    }

    override fun findById(id: UUID): TestPlan? = jdbcClient.sql(TestPlanSql.FIND_BY_ID)
        .param("id", id)
        .query { resultSet, _ -> resultSet.toPlan() }
        .optional()
        .orElse(null)

    override fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): TestPlan? =
        jdbcClient.sql(TestPlanSql.FIND_BY_TARGET_AND_IDEMPOTENCY_KEY)
            .params(mapOf("targetSystemId" to targetSystemId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toPlan() }
            .optional()
            .orElse(null)

    override fun findByTarget(targetSystemId: String, limit: Int): List<TestPlan> =
        jdbcClient.sql(TestPlanSql.FIND_BY_TARGET)
            .params(mapOf("targetSystemId" to targetSystemId, "limit" to limit))
            .query { resultSet, _ -> resultSet.toPlan() }
            .list()

    override fun findItems(planId: UUID): List<TestPlanItem> = jdbcClient.sql(TestPlanSql.FIND_ITEMS)
        .param("planId", planId)
        .query { resultSet, _ -> resultSet.toItem() }
        .list()

    override fun findExecutionReferences(planId: UUID): List<TestPlanExecutionReference> =
        jdbcClient.sql(TestPlanSql.FIND_EXECUTION_REFERENCES)
            .param("planId", planId)
            .query { resultSet, _ -> resultSet.toExecutionReference() }
            .list()

    override fun approve(planId: UUID, actor: String, correlationId: String, approvedAt: Instant): Boolean =
        jdbcClient.sql(TestPlanSql.APPROVE)
            .params(
                mapOf(
                    "id" to planId,
                    "actor" to actor,
                    "correlationId" to correlationId,
                    "approvedAt" to Timestamp.from(approvedAt),
                    "approved" to TestPlanStatus.APPROVED.name,
                    "pending" to TestPlanStatus.PENDING_APPROVAL.name,
                ),
            )
            .update() == 1

    override fun markDispatched(planId: UUID, dispatchedAt: Instant): Boolean =
        jdbcClient.sql(TestPlanSql.MARK_DISPATCHED)
            .params(
                mapOf(
                    "id" to planId,
                    "dispatchedAt" to Timestamp.from(dispatchedAt),
                    "dispatched" to TestPlanStatus.DISPATCHED.name,
                    "approved" to TestPlanStatus.APPROVED.name,
                ),
            )
            .update() == 1

    override fun markTerminal(planId: UUID, status: TestPlanStatus, reason: String): Boolean =
        jdbcClient.sql(TestPlanSql.MARK_TERMINAL)
            .params(
                mapOf(
                    "id" to planId,
                    "status" to status.name,
                    "reason" to reason,
                    "pending" to TestPlanStatus.PENDING_APPROVAL.name,
                    "approved" to TestPlanStatus.APPROVED.name,
                ),
            )
            .update() == 1

    override fun appendExecutionReference(reference: TestPlanExecutionReference) {
        jdbcClient.sql(TestPlanSql.INSERT_EXECUTION_REFERENCE)
            .params(
                mapOf(
                    "id" to reference.id,
                    "planId" to reference.planId,
                    "kind" to reference.kind.name,
                    "referenceId" to reference.referenceId,
                    "createdAt" to Timestamp.from(reference.createdAt),
                ),
            )
            .update()
    }

    private fun insertItem(item: TestPlanItem) {
        jdbcClient.sql(TestPlanSql.INSERT_ITEM)
            .params(
                mapOf(
                    "id" to item.id,
                    "planId" to item.planId,
                    "sequenceNumber" to item.sequenceNumber,
                    "candidateId" to item.candidateId,
                    "category" to item.category.name,
                    "risk" to item.risk.name,
                    "bindingKind" to item.bindingKind.name,
                    "targetTestCandidateIds" to objectMapper.writeValueAsString(item.targetTestCandidateIds),
                ),
            )
            .update()
    }

    private fun ResultSet.toPlan() = TestPlan(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        knowledgeSnapshotId = getObject("knowledge_snapshot_id", UUID::class.java),
        generationId = getObject("generation_id", UUID::class.java),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        status = TestPlanStatus.valueOf(getString("status")),
        requiredConfirmation = TestPlanConfirmation.valueOf(getString("required_confirmation")),
        idempotencyKey = getString("idempotency_key"),
        requestHash = getString("request_hash"),
        createdBy = getString("created_by"),
        createdCorrelationId = getString("created_correlation_id"),
        createdAt = getTimestamp("created_at").toInstant(),
        approvedBy = getString("approved_by"),
        approvedCorrelationId = getString("approved_correlation_id"),
        approvedAt = getTimestamp("approved_at")?.toInstant(),
        dispatchedAt = getTimestamp("dispatched_at")?.toInstant(),
        terminalReason = getString("terminal_reason"),
    )

    private fun ResultSet.toItem() = TestPlanItem(
        id = getObject("id", UUID::class.java),
        planId = getObject("plan_id", UUID::class.java),
        sequenceNumber = getInt("sequence_number"),
        candidateId = getObject("candidate_id", UUID::class.java),
        category = TestCandidateCategory.valueOf(getString("category")),
        risk = TestCandidateRisk.valueOf(getString("risk")),
        bindingKind = ExecutionBindingKind.valueOf(getString("binding_kind")),
        targetTestCandidateIds = objectMapper.readValue(
            getString("target_test_candidate_ids"),
            object : TypeReference<List<String>>() {},
        ),
    )

    private fun ResultSet.toExecutionReference() = TestPlanExecutionReference(
        id = getObject("id", UUID::class.java),
        planId = getObject("plan_id", UUID::class.java),
        kind = TestPlanExecutionKind.valueOf(getString("kind")),
        referenceId = getObject("reference_id", UUID::class.java),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}
