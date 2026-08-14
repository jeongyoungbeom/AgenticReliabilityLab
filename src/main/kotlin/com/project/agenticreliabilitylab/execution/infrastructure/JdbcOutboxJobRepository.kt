package com.project.agenticreliabilitylab.execution.infrastructure

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.execution.domain.OutboxJob
import com.project.agenticreliabilitylab.execution.domain.OutboxJobStatus
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import com.project.agenticreliabilitylab.execution.infrastructure.sql.OutboxJobSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.BadSqlGrammarException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcOutboxJobRepository(
    private val jdbcClient: JdbcClient,
    private val identifierGenerator: IdentifierGenerator,
) {
    fun enqueue(type: OutboxJobType, aggregateId: UUID, now: Instant) {
        try {
        jdbcClient.sql(OutboxJobSql.ENQUEUE).params(
            mapOf(
                "id" to identifierGenerator.next(),
                "jobType" to type.name,
                "aggregateId" to aggregateId,
                "status" to OutboxJobStatus.PENDING.name,
                "completed" to OutboxJobStatus.COMPLETED.name,
                "failed" to OutboxJobStatus.FAILED.name,
                "availableAt" to Timestamp.from(now),
                "createdAt" to Timestamp.from(now),
            ),
            ).update()
        } catch (_: BadSqlGrammarException) {
            enqueueWithH2Fallback(type, aggregateId, now)
        }
    }

    /** H2's PostgreSQL compatibility mode does not implement ON CONFLICT. */
    private fun enqueueWithH2Fallback(type: OutboxJobType, aggregateId: UUID, now: Instant) {
        try {
            jdbcClient.sql(OutboxJobSql.ENQUEUE_H2).params(
                mapOf(
                    "id" to identifierGenerator.next(),
                    "jobType" to type.name,
                    "aggregateId" to aggregateId,
                    "status" to OutboxJobStatus.PENDING.name,
                    "availableAt" to Timestamp.from(now),
                    "createdAt" to Timestamp.from(now),
                ),
            ).update()
        } catch (_: DuplicateKeyException) {
            jdbcClient.sql(OutboxJobSql.REENQUEUE_H2).params(
                mapOf(
                    "jobType" to type.name,
                    "aggregateId" to aggregateId,
                    "pending" to OutboxJobStatus.PENDING.name,
                    "completed" to OutboxJobStatus.COMPLETED.name,
                    "failed" to OutboxJobStatus.FAILED.name,
                    "availableAt" to Timestamp.from(now),
                ),
            ).update()
        }
    }

    @Transactional
    @Suppress("ReturnCount") // Empty capacity or a failed compare-and-set each have a deliberate safe exit.
    fun claimNext(
        workerId: String,
        now: Instant,
        leaseExpiresAt: Instant,
        eligibleTypes: Set<OutboxJobType>,
    ): OutboxJob? {
        if (eligibleTypes.isEmpty()) return null
        val typeParameters = eligibleTypes.sortedBy(OutboxJobType::name)
            .mapIndexed { index, type -> "jobType$index" to type.name }
            .toMap()
        val typePredicate = typeParameters.keys.joinToString(", ") { ":$it" }
        val candidate = jdbcClient.sql(OutboxJobSql.nextEligibleJob(typePredicate)).params(
            typeParameters + mapOf(
                "pending" to OutboxJobStatus.PENDING.name,
                "running" to OutboxJobStatus.RUNNING.name,
                "now" to Timestamp.from(now),
            ),
        ).query { resultSet, _ -> resultSet.toOutboxJob() }
            .optional()
            .orElse(null)
            ?: return null

        val claimed = jdbcClient.sql(OutboxJobSql.CLAIM).params(
            mapOf(
                "id" to candidate.id,
                "pending" to OutboxJobStatus.PENDING.name,
                "running" to OutboxJobStatus.RUNNING.name,
                "now" to Timestamp.from(now),
                "leaseOwner" to workerId,
                "leaseExpiresAt" to Timestamp.from(leaseExpiresAt),
            ),
        ).update() == 1
        return if (claimed) candidate.copy(
            status = OutboxJobStatus.RUNNING,
            attemptCount = candidate.attemptCount + 1,
            leaseOwner = workerId,
            leaseExpiresAt = leaseExpiresAt,
            lastError = null,
        ) else null
    }

    fun complete(id: UUID, workerId: String, now: Instant) {
        jdbcClient.sql(OutboxJobSql.COMPLETE).params(
            mapOf(
                "id" to id,
                "running" to OutboxJobStatus.RUNNING.name,
                "completed" to OutboxJobStatus.COMPLETED.name,
                "leaseOwner" to workerId,
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    fun renewLease(id: UUID, workerId: String, leaseExpiresAt: Instant): Boolean =
        jdbcClient.sql(OutboxJobSql.RENEW_LEASE).params(
            mapOf(
                "id" to id,
                "running" to OutboxJobStatus.RUNNING.name,
                "leaseOwner" to workerId,
                "leaseExpiresAt" to Timestamp.from(leaseExpiresAt),
            ),
        ).update() == 1

    /** Returns a rejected executor submission to PENDING without consuming a retry. */
    fun releaseClaim(id: UUID, workerId: String, now: Instant): Boolean =
        jdbcClient.sql(OutboxJobSql.RELEASE_CLAIM).params(
            mapOf(
                "id" to id,
                "running" to OutboxJobStatus.RUNNING.name,
                "pending" to OutboxJobStatus.PENDING.name,
                "leaseOwner" to workerId,
                "availableAt" to Timestamp.from(now),
            ),
        ).update() == 1

    /**
     * Returns successfully inspected but not-yet-actionable work to PENDING.
     * This is deliberately distinct from retryOrFail: waiting for a child job
     * is ordinary control flow and must not consume the failure budget.
     */
    fun defer(id: UUID, workerId: String, availableAt: Instant): Boolean =
        jdbcClient.sql(OutboxJobSql.DEFER).params(
            mapOf(
                "id" to id,
                "running" to OutboxJobStatus.RUNNING.name,
                "pending" to OutboxJobStatus.PENDING.name,
                "leaseOwner" to workerId,
                "availableAt" to Timestamp.from(availableAt),
            ),
        ).update() == 1

    fun retryOrFail(job: OutboxJob, workerId: String, now: Instant, maxAttempts: Int, message: String) {
        val retry = job.attemptCount < maxAttempts
        jdbcClient.sql(OutboxJobSql.RETRY_OR_FAIL).params(
            mapOf(
                "id" to job.id,
                "running" to OutboxJobStatus.RUNNING.name,
                "leaseOwner" to workerId,
                "status" to if (retry) OutboxJobStatus.PENDING.name else OutboxJobStatus.FAILED.name,
                "availableAt" to Timestamp.from(if (retry) now.plusSeconds(job.attemptCount.toLong()) else now),
                "lastError" to message.take(MAX_ERROR_MESSAGE_LENGTH),
                "completedAt" to if (retry) null else Timestamp.from(now),
            ),
        ).update()
    }

    fun pendingCount(): Long = jdbcClient.sql(OutboxJobSql.PENDING_COUNT).param("status", OutboxJobStatus.PENDING.name)
        .query(Long::class.java)
        .single()

    private fun ResultSet.toOutboxJob(): OutboxJob = OutboxJob(
        id = getObject("id", UUID::class.java),
        type = OutboxJobType.valueOf(getString("job_type")),
        aggregateId = getObject("aggregate_id", UUID::class.java),
        status = OutboxJobStatus.valueOf(getString("status")),
        attemptCount = getInt("attempt_count"),
        availableAt = getTimestamp("available_at").toInstant(),
        leaseOwner = getString("lease_owner"),
        leaseExpiresAt = getTimestamp("lease_expires_at")?.toInstant(),
        lastError = getString("last_error"),
        createdAt = getTimestamp("created_at").toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )

    private companion object {
        const val MAX_ERROR_MESSAGE_LENGTH = 1_000
    }
}
