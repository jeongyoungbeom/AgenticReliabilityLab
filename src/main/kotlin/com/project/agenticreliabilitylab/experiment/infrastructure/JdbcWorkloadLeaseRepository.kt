package com.project.agenticreliabilitylab.experiment.infrastructure

import com.project.agenticreliabilitylab.experiment.application.port.WorkloadLeasePort
import com.project.agenticreliabilitylab.experiment.domain.WorkloadLease
import com.project.agenticreliabilitylab.experiment.infrastructure.sql.WorkloadLeaseSql
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcWorkloadLeaseRepository(
    private val jdbcClient: JdbcClient,
) : WorkloadLeasePort {
    override fun tryAcquire(
        hostResourceGroup: String,
        ownerId: String,
        leaseOwner: String,
        now: Instant,
        expiresAt: Instant,
        mode: String,
        ownerType: String,
    ): WorkloadLease? {
        val updated = jdbcClient.sql(WorkloadLeaseSql.ACQUIRE_EXPIRED)
            .params(leaseParameters(hostResourceGroup, ownerId, leaseOwner, now, expiresAt, mode, ownerType))
            .update()

        val acquired = updated != 0 || createLease(
            hostResourceGroup,
            ownerId,
            leaseOwner,
            now,
            expiresAt,
            mode,
            ownerType,
        )
        val fencingToken = if (acquired) jdbcClient.sql(WorkloadLeaseSql.FIND_FENCING_TOKEN).params(
            mapOf(
                "hostResourceGroup" to hostResourceGroup,
                "ownerId" to ownerId,
                "leaseOwner" to leaseOwner,
            ),
        ).query(Long::class.java).optional().orElse(null) else null

        return fencingToken?.let { WorkloadLease(hostResourceGroup, ownerId, leaseOwner, it, expiresAt) }
    }

    private fun createLease(
        hostResourceGroup: String,
        ownerId: String,
        leaseOwner: String,
        now: Instant,
        expiresAt: Instant,
        mode: String,
        ownerType: String,
    ): Boolean = try {
        jdbcClient.sql(WorkloadLeaseSql.INSERT)
            .params(leaseParameters(hostResourceGroup, ownerId, leaseOwner, now, expiresAt, mode, ownerType))
            .update() == 1
    } catch (_: DuplicateKeyException) {
        false
    }

    override fun release(lease: WorkloadLease) {
        jdbcClient.sql(WorkloadLeaseSql.DELETE).params(
            mapOf(
                "hostResourceGroup" to lease.hostResourceGroup,
                "ownerId" to lease.ownerId,
                "leaseOwner" to lease.leaseOwner,
                "fencingToken" to lease.fencingToken,
            ),
        ).update()
    }

    private fun leaseParameters(
        hostResourceGroup: String,
        ownerId: String,
        leaseOwner: String,
        now: Instant,
        expiresAt: Instant,
        mode: String,
        ownerType: String,
    ): Map<String, Any> = mapOf(
        "hostResourceGroup" to hostResourceGroup,
        "mode" to mode,
        "ownerType" to ownerType,
        "ownerId" to ownerId,
        "leaseOwner" to leaseOwner,
        "now" to Timestamp.from(now),
        "expiresAt" to Timestamp.from(expiresAt),
    )
}
