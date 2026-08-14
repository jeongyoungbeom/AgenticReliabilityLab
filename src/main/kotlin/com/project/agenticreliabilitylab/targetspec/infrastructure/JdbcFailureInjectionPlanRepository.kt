package com.project.agenticreliabilitylab.targetspec.infrastructure

import com.project.agenticreliabilitylab.targetspec.application.port.FailureInjectionPlanStore
import com.project.agenticreliabilitylab.targetspec.application.port.NewFailureInjectionPlan
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanRecord
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanStatus
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionRisk
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionType
import com.project.agenticreliabilitylab.targetspec.infrastructure.sql.FailureInjectionPlanSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcFailureInjectionPlanRepository(private val jdbcClient: JdbcClient) : FailureInjectionPlanStore {
    @Transactional
    override fun create(command: NewFailureInjectionPlan) {
        jdbcClient.sql(FailureInjectionPlanSql.INSERT_PLAN)
            .params(
                mapOf(
                    "id" to command.id,
                    "targetSystemId" to command.targetSystemId,
                    "profileVersionId" to command.profileVersionId,
                    "idempotencyKey" to command.idempotencyKey,
                    "requestHash" to command.requestHash,
                    "status" to FailureInjectionPlanStatus.PENDING_APPROVAL.name,
                    "createdAt" to Timestamp.from(command.createdAt),
                ),
            )
            .update()
        command.candidates.forEachIndexed { index, candidate ->
            jdbcClient.sql(FailureInjectionPlanSql.INSERT_ITEM)
                .params(mapOf("id" to candidate.id, "planId" to command.id, "sequence" to index + 1, "candidateId" to candidate.candidate.id,
                    "type" to candidate.candidate.type.name, "risk" to candidate.candidate.risk.name, "title" to candidate.candidate.title,
                    "recoveryExpectation" to candidate.candidate.recoveryExpectation)).update()
        }
    }

    override fun findById(id: UUID): FailureInjectionPlanRecord? = jdbcClient.sql(
        FailureInjectionPlanSql.FIND_PLAN_BY_ID,
    ).param("id", id).query { rs, _ -> rs.toPlan() }.optional().orElse(null)

    override fun findByTargetAndIdempotencyKey(
        targetId: String,
        key: String,
    ): FailureInjectionPlanRecord? = jdbcClient.sql(
        FailureInjectionPlanSql.FIND_PLAN_BY_TARGET_AND_IDEMPOTENCY_KEY,
    )
        .params(mapOf("targetId" to targetId, "key" to key)).query { rs, _ -> rs.toPlan() }.optional().orElse(null)
    override fun findItems(planId: UUID): List<FailureInjectionPlanItemRecord> = jdbcClient.sql(
        FailureInjectionPlanSql.FIND_ITEMS_BY_PLAN_ID,
    ).param("planId", planId).query { rs, _ ->
        FailureInjectionPlanItemRecord(rs.getObject("id", UUID::class.java), rs.getObject("plan_id", UUID::class.java), rs.getInt("sequence_number"), rs.getString("candidate_id"),
            FailureInjectionType.valueOf(rs.getString("injection_type")), FailureInjectionRisk.valueOf(rs.getString("risk")), rs.getString("title"), rs.getString("recovery_expectation"))
    }.list()
    override fun approve(planId: UUID, actor: String, correlationId: String, now: Instant): Boolean =
        jdbcClient.sql(FailureInjectionPlanSql.APPROVE)
        .params(
            mapOf(
                "id" to planId,
                "pending" to FailureInjectionPlanStatus.PENDING_APPROVAL.name,
                "approved" to FailureInjectionPlanStatus.APPROVED.name,
                "approvedAt" to Timestamp.from(now),
                "approvedBy" to actor,
                "approvalCorrelationId" to correlationId,
            ),
        ).update() == 1

    private fun ResultSet.toPlan() = FailureInjectionPlanRecord(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        idempotencyKey = getString("idempotency_key"),
        requestHash = getString("request_hash"),
        status = FailureInjectionPlanStatus.valueOf(getString("status")),
        approvedAt = getTimestamp("approved_at")?.toInstant(),
        approvedBy = getString("approved_by"),
        approvalCorrelationId = getString("approval_correlation_id"),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}
