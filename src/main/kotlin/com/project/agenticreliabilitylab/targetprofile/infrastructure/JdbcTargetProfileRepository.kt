package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.targetprofile.application.port.TargetProfileStore
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileAuditEvent
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.infrastructure.sql.TargetProfileSql
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Repository
class JdbcTargetProfileRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val dataSource: DataSource,
) : TargetProfileStore {
    private val isPostgresDatabase = dataSource.connection.use { connection ->
        connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)
    }

    override fun findVersion(id: UUID): TargetProfileVersion? =
        jdbcClient.sql(TargetProfileSql.FIND_VERSION_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toVersion() }
            .optional().orElse(null)

    override fun findVersionByTargetAndChecksum(targetSystemId: String, checksum: String): TargetProfileVersion? =
        jdbcClient.sql(TargetProfileSql.FIND_VERSION_BY_TARGET_AND_CHECKSUM)
            .params(mapOf("targetSystemId" to targetSystemId, "checksum" to checksum))
            .query { resultSet, _ -> resultSet.toVersion() }.optional().orElse(null)

    override fun findActive(targetSystemId: String): TargetProfileVersion? =
        jdbcClient.sql(TargetProfileSql.FIND_ACTIVE_BY_TARGET).param("targetSystemId", targetSystemId)
            .query { resultSet, _ -> resultSet.toVersion() }.optional().orElse(null)

    override fun findAllActive(): List<TargetProfileVersion> =
        jdbcClient.sql(TargetProfileSql.FIND_ALL_ACTIVE).query { resultSet, _ -> resultSet.toVersion() }.list()

    override fun createIfAbsent(version: TargetProfileVersion): Boolean =
        try {
            jdbcClient.sql(insertVersionSql()).params(
            mapOf(
                "id" to version.id,
                "targetSystemId" to version.targetSystemId,
                "source" to version.source.name,
                "status" to version.status.name,
                "checksum" to version.checksum,
                "configJson" to objectMapper.writeValueAsString(version.definition),
                "createdBy" to version.createdBy,
                "createdAt" to Timestamp.from(version.createdAt),
            ),
            ).update() == 1
        } catch (_: DuplicateKeyException) {
            false
        }

    override fun activate(targetSystemId: String, versionId: UUID, actor: String, activatedAt: Instant): Boolean {
        val timestamp = Timestamp.from(activatedAt)
        jdbcClient.sql(TargetProfileSql.SUPERSEDE_ACTIVE_VERSIONS).params(
            mapOf(
                "targetSystemId" to targetSystemId,
                "versionId" to versionId,
                "active" to TargetProfileStatus.ACTIVE.name,
                "superseded" to TargetProfileStatus.SUPERSEDED.name,
            ),
        ).update()
        if (
            jdbcClient.sql(TargetProfileSql.MARK_VERSION_ACTIVE).params(
                mapOf(
                    "targetSystemId" to targetSystemId,
                    "versionId" to versionId,
                    "active" to TargetProfileStatus.ACTIVE.name,
                    "actor" to actor,
                "activatedAt" to timestamp,
                ),
            ).update() != 1
        ) return false

        val pointerParameters = mapOf(
            "targetSystemId" to targetSystemId,
            "versionId" to versionId,
            "actor" to actor,
            "activatedAt" to timestamp,
        )
        if (isPostgresDatabase) {
            jdbcClient.sql(TargetProfileSql.UPSERT_ACTIVE_POINTER_POSTGRES).params(pointerParameters).update()
        } else {
            updateActivePointer(pointerParameters)
        }
        return true
    }

    private fun updateActivePointer(pointerParameters: Map<String, Any>) {
        if (jdbcClient.sql(TargetProfileSql.UPDATE_ACTIVE_POINTER).params(pointerParameters).update() == 0) {
            try {
                jdbcClient.sql(TargetProfileSql.INSERT_ACTIVE_POINTER).params(pointerParameters).update()
            } catch (_: DuplicateKeyException) {
                jdbcClient.sql(TargetProfileSql.UPDATE_ACTIVE_POINTER).params(pointerParameters).update()
            }
        }
    }

    private fun insertVersionSql(): String =
        if (isPostgresDatabase) TargetProfileSql.INSERT_VERSION_ON_CONFLICT else TargetProfileSql.INSERT_VERSION

    override fun appendAuditEvent(event: TargetProfileAuditEvent) {
        jdbcClient.sql(TargetProfileSql.INSERT_AUDIT_EVENT).params(
            mapOf(
                "id" to event.id,
                "targetSystemId" to event.targetSystemId,
                "profileVersionId" to event.profileVersionId,
                "eventType" to event.eventType.name,
                "actor" to event.actor,
                "correlationId" to event.correlationId,
                "occurredAt" to Timestamp.from(event.occurredAt),
            ),
        ).update()
    }

    private fun ResultSet.toVersion(): TargetProfileVersion = TargetProfileVersion(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        source = com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileSource
            .valueOf(getString("source")),
        status = TargetProfileStatus.valueOf(getString("status")),
        checksum = getString("checksum"),
        definition = objectMapper.readValue(getString("config_json"), TargetProfileDefinition::class.java),
        createdBy = getString("created_by"),
        createdAt = getTimestamp("created_at").toInstant(),
        activatedBy = getString("activated_by"),
        activatedAt = getTimestamp("activated_at")?.toInstant(),
    )
}
