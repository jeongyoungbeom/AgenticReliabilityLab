package com.project.agenticreliabilitylab.targetdiscovery.infrastructure

import com.project.agenticreliabilitylab.targetdiscovery.application.port.PilotTestSessionStore
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSession
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItem
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItemStatus
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionStatus
import com.project.agenticreliabilitylab.targetdiscovery.infrastructure.sql.PilotTestSessionSql
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcPilotTestSessionRepository(
    private val jdbcClient: JdbcClient,
) : PilotTestSessionStore {
    override fun create(session: PilotTestSession) {
        require(session.status == PilotTestSessionStatus.RUNNING) { "A new Pilot test session must be RUNNING" }
        jdbcClient.sql(PilotTestSessionSql.INSERT_SESSION)
            .params(
                mapOf(
                    "id" to session.id,
                    "targetSystemId" to session.targetSystemId,
                    "profileVersionId" to session.profileVersionId,
                    "status" to session.status.name,
                    "idempotencyKey" to session.idempotencyKey,
                    "requestHash" to session.requestHash,
                    "createdBy" to session.createdBy,
                    "createdCorrelationId" to session.createdCorrelationId,
                    "createdAt" to Timestamp.from(session.createdAt),
                ),
            )
            .update()
    }

    override fun findById(id: UUID): PilotTestSession? = jdbcClient.sql(PilotTestSessionSql.FIND_BY_ID)
        .param("id", id)
        .query { resultSet, _ -> resultSet.toSession() }
        .optional()
        .orElse(null)

    override fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): PilotTestSession? =
        jdbcClient.sql(PilotTestSessionSql.FIND_BY_TARGET_AND_IDEMPOTENCY)
            .params(mapOf("targetSystemId" to targetSystemId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toSession() }
            .optional()
            .orElse(null)

    override fun findByTarget(targetSystemId: String, limit: Int): List<PilotTestSession> {
        require(limit in 1..MAX_LIST_LIMIT) { "Pilot session limit must be between 1 and $MAX_LIST_LIMIT" }
        return jdbcClient.sql(PilotTestSessionSql.FIND_BY_TARGET)
            .params(mapOf("targetSystemId" to targetSystemId, "limit" to limit))
            .query { resultSet, _ -> resultSet.toSession() }
            .list()
    }

    override fun findItems(sessionId: UUID): List<PilotTestSessionItem> =
        jdbcClient.sql(PilotTestSessionSql.FIND_ITEMS)
        .param("sessionId", sessionId)
        .query { resultSet, _ -> resultSet.toItem() }
        .list()

    @Transactional
    override fun complete(
        id: UUID,
        status: PilotTestSessionStatus,
        resultOutcome: TrialOutcome,
        cleanupVerified: Boolean,
        completedAt: Instant,
        failure: String?,
        items: List<PilotTestSessionItem>,
    ): Boolean {
        require(status != PilotTestSessionStatus.RUNNING) { "A completed Pilot session cannot remain RUNNING" }
        require(items.isNotEmpty()) { "A Pilot test session must retain at least one selected candidate" }
        require(items.map(PilotTestSessionItem::sequenceNumber) == (1..items.size).toList()) {
            "Pilot test session item sequence must start at one and remain contiguous"
        }
        val updated = jdbcClient.sql(PilotTestSessionSql.COMPLETE_SESSION)
            .params(
                mapOf(
                    "id" to id,
                    "status" to status.name,
                    "resultOutcome" to resultOutcome.name,
                    "cleanupVerified" to cleanupVerified,
                    "completedAt" to Timestamp.from(completedAt),
                    "failure" to failure?.take(MAX_FAILURE_LENGTH),
                    "running" to PilotTestSessionStatus.RUNNING.name,
                ),
            )
            .update()
        if (updated != 1) return false
        items.forEach { item ->
            require(item.sessionId == id) { "Pilot session item belongs to '${item.sessionId}', not '$id'" }
            jdbcClient.sql(PilotTestSessionSql.INSERT_ITEM)
                .params(
                    mapOf(
                        "sessionId" to item.sessionId,
                        "sequenceNumber" to item.sequenceNumber,
                        "candidateId" to item.candidateId,
                        "specificationId" to item.specificationId,
                        "testSpecRunId" to item.testSpecRunId,
                        "status" to item.status.name,
                        "resultOutcome" to item.resultOutcome?.name,
                        "cleanupVerified" to item.cleanupVerified,
                        "failureCode" to item.failureCode?.take(MAX_FAILURE_CODE_LENGTH),
                        "failureMessage" to item.failureMessage?.take(MAX_FAILURE_LENGTH),
                        "completedAt" to Timestamp.from(item.completedAt),
                    ),
                )
                .update()
        }
        return true
    }

    override fun recoverIncompleteSessions(completedAt: Instant): Int =
        jdbcClient.sql(PilotTestSessionSql.RECOVER_RUNNING)
        .params(
            mapOf(
                "recoveryRequired" to PilotTestSessionStatus.RECOVERY_REQUIRED.name,
                "inconclusive" to TrialOutcome.INCONCLUSIVE.name,
                "completedAt" to Timestamp.from(completedAt),
                "failure" to RESTART_FAILURE,
                "running" to PilotTestSessionStatus.RUNNING.name,
            ),
        )
        .update()

    private fun ResultSet.toSession() = PilotTestSession(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        status = PilotTestSessionStatus.valueOf(getString("status")),
        idempotencyKey = getString("idempotency_key"),
        requestHash = getString("request_hash"),
        createdBy = getString("created_by"),
        createdCorrelationId = getString("created_correlation_id"),
        createdAt = getTimestamp("created_at").toInstant(),
        resultOutcome = getString("result_outcome")?.let(TrialOutcome::valueOf),
        cleanupVerified = getObject("cleanup_verified", Boolean::class.javaObjectType),
        completedAt = getTimestamp("completed_at")?.toInstant(),
        failure = getString("failure"),
    )

    private fun ResultSet.toItem() = PilotTestSessionItem(
        sessionId = getObject("session_id", UUID::class.java),
        sequenceNumber = getInt("sequence_number"),
        candidateId = getString("candidate_id"),
        specificationId = getObject("specification_id", UUID::class.java),
        testSpecRunId = getObject("test_spec_run_id", UUID::class.java),
        status = PilotTestSessionItemStatus.valueOf(getString("status")),
        resultOutcome = getString("result_outcome")?.let(TrialOutcome::valueOf),
        cleanupVerified = getObject("cleanup_verified", Boolean::class.javaObjectType),
        failureCode = getString("failure_code"),
        failureMessage = getString("failure_message"),
        completedAt = getTimestamp("completed_at").toInstant(),
    )

    private companion object {
        const val MAX_FAILURE_CODE_LENGTH = 100
        const val MAX_FAILURE_LENGTH = 1_000
        const val MAX_LIST_LIMIT = 100
        const val RESTART_FAILURE = "ARL restarted while a Pilot test session could have been in progress"
    }
}
