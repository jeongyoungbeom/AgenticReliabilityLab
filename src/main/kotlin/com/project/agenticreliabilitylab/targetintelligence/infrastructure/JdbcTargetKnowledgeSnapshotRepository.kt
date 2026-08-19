package com.project.agenticreliabilitylab.targetintelligence.infrastructure

import com.project.agenticreliabilitylab.targetintelligence.application.port.TargetKnowledgeSnapshotStore
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeContent
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeSnapshot
import com.project.agenticreliabilitylab.targetintelligence.infrastructure.sql.TargetKnowledgeSnapshotSql
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Stores the extracted content as one immutable JSON document alongside its audit columns. */
@Repository
class JdbcTargetKnowledgeSnapshotRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val dataSource: DataSource,
) : TargetKnowledgeSnapshotStore {
    private val isPostgresDatabase = dataSource.connection.use { connection ->
        connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)
    }

    override fun createIfAbsent(snapshot: TargetKnowledgeSnapshot): Boolean = try {
        jdbcClient.sql(insertSql())
            .params(
                mapOf(
                    "id" to snapshot.id,
                    "targetSystemId" to snapshot.targetSystemId,
                    "profileVersionId" to snapshot.profileVersionId,
                    "checksum" to snapshot.checksum,
                    "extractionVersion" to snapshot.extractionVersion,
                    "contentJson" to objectMapper.writeValueAsString(snapshot.content),
                    "createdBy" to snapshot.createdBy,
                    "createdCorrelationId" to snapshot.createdCorrelationId,
                    "createdAt" to Timestamp.from(snapshot.createdAt),
                ),
            )
            .update() == 1
    } catch (_: DuplicateKeyException) {
        false
    }

    override fun findById(id: UUID): TargetKnowledgeSnapshot? = jdbcClient.sql(TargetKnowledgeSnapshotSql.FIND_BY_ID)
        .param("id", id)
        .query { resultSet, _ -> resultSet.toSnapshot() }
        .optional()
        .orElse(null)

    override fun findByProfileVersionAndChecksum(
        profileVersionId: UUID,
        checksum: String,
    ): TargetKnowledgeSnapshot? = jdbcClient.sql(TargetKnowledgeSnapshotSql.FIND_BY_PROFILE_VERSION_AND_CHECKSUM)
        .params(mapOf("profileVersionId" to profileVersionId, "checksum" to checksum))
        .query { resultSet, _ -> resultSet.toSnapshot() }
        .optional()
        .orElse(null)

    override fun findByTarget(targetSystemId: String, limit: Int): List<TargetKnowledgeSnapshot> = jdbcClient
        .sql(TargetKnowledgeSnapshotSql.FIND_BY_TARGET)
        .params(mapOf("targetSystemId" to targetSystemId, "limit" to limit))
        .query { resultSet, _ -> resultSet.toSnapshot() }
        .list()

    override fun confirm(id: UUID, actor: String, correlationId: String, confirmedAt: Instant): Boolean = jdbcClient
        .sql(TargetKnowledgeSnapshotSql.CONFIRM)
        .params(
            mapOf(
                "id" to id,
                "confirmedBy" to actor,
                "confirmedCorrelationId" to correlationId,
                "confirmedAt" to Timestamp.from(confirmedAt),
            ),
        )
        .update() == 1

    private fun insertSql(): String = if (isPostgresDatabase) {
        TargetKnowledgeSnapshotSql.INSERT_SNAPSHOT_ON_CONFLICT
    } else {
        TargetKnowledgeSnapshotSql.INSERT_SNAPSHOT
    }

    private fun ResultSet.toSnapshot(): TargetKnowledgeSnapshot = TargetKnowledgeSnapshot(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        checksum = getString("checksum"),
        extractionVersion = getString("extraction_version"),
        content = objectMapper.readValue(getString("content_json"), TargetKnowledgeContent::class.java),
        createdBy = getString("created_by"),
        createdCorrelationId = getString("created_correlation_id"),
        createdAt = getTimestamp("created_at").toInstant(),
        confirmedBy = getString("confirmed_by"),
        confirmedCorrelationId = getString("confirmed_correlation_id"),
        confirmedAt = getTimestamp("confirmed_at")?.toInstant(),
    )
}
