package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.targetprofile.application.port.TargetApprovalAuditStore
import com.project.agenticreliabilitylab.targetprofile.domain.TargetApprovalAuditEvent
import com.project.agenticreliabilitylab.targetprofile.infrastructure.sql.TargetApprovalAuditSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
class JdbcTargetApprovalAuditRepository(
    private val jdbcClient: JdbcClient,
) : TargetApprovalAuditStore {
    override fun append(event: TargetApprovalAuditEvent) {
        jdbcClient.sql(TargetApprovalAuditSql.INSERT).params(
            mapOf(
                "id" to event.id,
                "targetSystemId" to event.targetSystemId,
                "profileVersionId" to event.profileVersionId,
                "aggregateType" to event.aggregateType.name,
                "aggregateId" to event.aggregateId,
                "actor" to event.actor,
                "correlationId" to event.correlationId,
                "occurredAt" to Timestamp.from(event.occurredAt),
            ),
        ).update()
    }
}
