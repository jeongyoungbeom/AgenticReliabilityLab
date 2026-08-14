package com.project.agenticreliabilitylab.targetprofile.infrastructure.sql

/** SQL owned by the immutable Target approval audit adapter. */
object TargetApprovalAuditSql {
    val INSERT = """
        insert into target_approval_audit_event (
            id, target_system_id, profile_version_id, aggregate_type, aggregate_id, actor, correlation_id, occurred_at
        ) values (
            :id, :targetSystemId, :profileVersionId, :aggregateType, :aggregateId, :actor, :correlationId, :occurredAt
        )
    """.trimIndent()
}
