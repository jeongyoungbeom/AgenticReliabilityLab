package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.TestSpecificationStore
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.StoredTestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TestSpecificationStatus
import com.project.agenticreliabilitylab.testspec.infrastructure.sql.TestSpecificationSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcTestSpecificationRepository(
    private val jdbcClient: JdbcClient,
) : TestSpecificationStore {
    override fun create(specification: StoredTestSpecification) {
        jdbcClient.sql(TestSpecificationSql.INSERT)
            .params(
                mapOf(
                    "id" to specification.id,
                    "targetSystemId" to specification.targetSystemId,
                    "specKey" to specification.specKey,
                    "version" to specification.version,
                    "title" to specification.title,
                    "profileVersionId" to specification.profileVersionId,
                    "source" to specification.source.name,
                    "category" to specification.category.name,
                    "risk" to specification.risk.name,
                    "status" to specification.status.name,
                    "documentJson" to specification.documentJson,
                    "checksum" to specification.checksum,
                    "createdBy" to specification.createdBy,
                    "createdCorrelationId" to specification.createdCorrelationId,
                    "createdAt" to Timestamp.from(specification.createdAt),
                ),
            )
            .update()
    }

    override fun findById(id: UUID): StoredTestSpecification? = jdbcClient.sql(TestSpecificationSql.FIND_BY_ID)
        .param("id", id)
        .query { resultSet, _ -> resultSet.toSpecification() }
        .optional()
        .orElse(null)

    override fun findByTargetAndKey(targetSystemId: String, specKey: String): List<StoredTestSpecification> =
        jdbcClient.sql(TestSpecificationSql.FIND_BY_TARGET_AND_KEY)
            .params(mapOf("targetSystemId" to targetSystemId, "specKey" to specKey))
            .query { resultSet, _ -> resultSet.toSpecification() }
            .list()

    override fun findApprovedByTarget(targetSystemId: String): List<StoredTestSpecification> =
        jdbcClient.sql(TestSpecificationSql.FIND_APPROVED_BY_TARGET)
            .params(
                mapOf(
                    "targetSystemId" to targetSystemId,
                    "approved" to TestSpecificationStatus.APPROVED.name,
                ),
            )
            .query { resultSet, _ -> resultSet.toSpecification() }
            .list()

    override fun findByTarget(targetSystemId: String, limit: Int): List<StoredTestSpecification> =
        jdbcClient.sql(TestSpecificationSql.FIND_BY_TARGET)
            .params(mapOf("targetSystemId" to targetSystemId, "limit" to limit))
            .query { resultSet, _ -> resultSet.toSpecification() }
            .list()

    override fun approve(id: UUID, actor: String, correlationId: String, approvedAt: Instant): Boolean =
        jdbcClient.sql(TestSpecificationSql.APPROVE)
            .params(
                mapOf(
                    "id" to id,
                    "actor" to actor,
                    "correlationId" to correlationId,
                    "approvedAt" to Timestamp.from(approvedAt),
                    "approved" to TestSpecificationStatus.APPROVED.name,
                    "pending" to TestSpecificationStatus.PENDING_APPROVAL.name,
                ),
            )
            .update() == 1

    override fun reviseProfileVersion(id: UUID, expectedProfileVersionId: UUID, profileVersionId: UUID): Boolean =
        jdbcClient.sql(TestSpecificationSql.REVISE_PROFILE_VERSION)
            .params(
                mapOf(
                    "id" to id,
                    "expectedProfileVersionId" to expectedProfileVersionId,
                    "profileVersionId" to profileVersionId,
                    "draft" to TestSpecificationStatus.DRAFT.name,
                    "pending" to TestSpecificationStatus.PENDING_APPROVAL.name,
                    "approved" to TestSpecificationStatus.APPROVED.name,
                ),
            )
            .update() == 1

    override fun supersede(id: UUID, reason: String): Boolean = jdbcClient.sql(TestSpecificationSql.SUPERSEDE)
        .params(
            mapOf(
                "id" to id,
                "reason" to reason,
                "superseded" to TestSpecificationStatus.SUPERSEDED.name,
                "draft" to TestSpecificationStatus.DRAFT.name,
                "pending" to TestSpecificationStatus.PENDING_APPROVAL.name,
                "approved" to TestSpecificationStatus.APPROVED.name,
            ),
        )
        .update() == 1

    private fun ResultSet.toSpecification() = StoredTestSpecification(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        specKey = getString("spec_key"),
        version = getInt("version"),
        title = getString("title"),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        source = SpecSource.valueOf(getString("source")),
        category = SpecCategory.valueOf(getString("category")),
        risk = SpecRisk.valueOf(getString("risk")),
        status = TestSpecificationStatus.valueOf(getString("status")),
        documentJson = getString("document_json"),
        checksum = getString("checksum"),
        createdBy = getString("created_by"),
        createdCorrelationId = getString("created_correlation_id"),
        createdAt = getTimestamp("created_at").toInstant(),
        approvedBy = getString("approved_by"),
        approvedCorrelationId = getString("approved_correlation_id"),
        approvedAt = getTimestamp("approved_at")?.toInstant(),
        terminalReason = getString("terminal_reason"),
    )
}
