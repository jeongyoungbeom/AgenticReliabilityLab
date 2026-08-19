package com.project.agenticreliabilitylab.testcatalog.infrastructure

import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.testcatalog.application.port.TestCandidateStore
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidate
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateCategory
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateGeneration
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateGenerationSource
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateRisk
import com.project.agenticreliabilitylab.testcatalog.infrastructure.sql.TestCandidateSql
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

@Repository
class JdbcTestCandidateRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val dataSource: DataSource,
) : TestCandidateStore {
    private val isPostgresDatabase = dataSource.connection.use { connection ->
        connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)
    }

    @Transactional
    override fun createIfAbsent(generation: TestCandidateGeneration, candidates: List<TestCandidate>): Boolean {
        val inserted = try {
            jdbcClient.sql(insertGenerationSql())
                .params(
                    mapOf(
                        "id" to generation.id,
                        "targetSystemId" to generation.targetSystemId,
                        "knowledgeSnapshotId" to generation.knowledgeSnapshotId,
                        "profileVersionId" to generation.profileVersionId,
                        "source" to generation.source.name,
                        "generatorVersion" to generation.generatorVersion,
                        "checksum" to generation.checksum,
                        "createdBy" to generation.createdBy,
                        "createdCorrelationId" to generation.createdCorrelationId,
                        "createdAt" to Timestamp.from(generation.createdAt),
                    ),
                )
                .update() == 1
        } catch (_: DuplicateKeyException) {
            false
        }
        if (!inserted) return false
        candidates.forEach(::insertCandidate)
        return true
    }

    override fun findGeneration(id: UUID): TestCandidateGeneration? =
        jdbcClient.sql(TestCandidateSql.FIND_GENERATION_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toGeneration() }
            .optional()
            .orElse(null)

    override fun findGenerationBySnapshotAndChecksum(
        knowledgeSnapshotId: UUID,
        checksum: String,
    ): TestCandidateGeneration? = jdbcClient.sql(TestCandidateSql.FIND_GENERATION_BY_SNAPSHOT_AND_CHECKSUM)
        .params(mapOf("knowledgeSnapshotId" to knowledgeSnapshotId, "checksum" to checksum))
        .query { resultSet, _ -> resultSet.toGeneration() }
        .optional()
        .orElse(null)

    override fun findGenerationsByTarget(targetSystemId: String, limit: Int): List<TestCandidateGeneration> =
        jdbcClient.sql(TestCandidateSql.FIND_GENERATIONS_BY_TARGET)
            .params(mapOf("targetSystemId" to targetSystemId, "limit" to limit))
            .query { resultSet, _ -> resultSet.toGeneration() }
            .list()

    override fun findCandidates(generationId: UUID): List<TestCandidate> =
        jdbcClient.sql(TestCandidateSql.FIND_CANDIDATES_BY_GENERATION)
            .param("generationId", generationId)
            .query { resultSet, _ -> resultSet.toCandidate() }
            .list()

    private fun insertCandidate(candidate: TestCandidate) {
        val detail = StoredTestCandidateDetail(
            verifiedExpectation = candidate.verifiedExpectation,
            preconditions = candidate.preconditions,
            binding = candidate.binding,
            citations = candidate.citations,
            requiredEvidence = candidate.requiredEvidence,
            dataLifecyclePlan = candidate.dataLifecyclePlan,
        )
        jdbcClient.sql(TestCandidateSql.INSERT_CANDIDATE)
            .params(
                mapOf(
                    "id" to candidate.id,
                    "generationId" to candidate.generationId,
                    "sequenceNumber" to candidate.sequenceNumber,
                    "category" to candidate.category.name,
                    "title" to candidate.title,
                    "description" to candidate.description,
                    "risk" to candidate.risk.name,
                    "confidence" to candidate.confidence.name,
                    "bindingKind" to candidate.binding.kind.name,
                    "detailJson" to objectMapper.writeValueAsString(detail),
                ),
            )
            .update()
    }

    private fun insertGenerationSql(): String = if (isPostgresDatabase) {
        TestCandidateSql.INSERT_GENERATION_ON_CONFLICT
    } else {
        TestCandidateSql.INSERT_GENERATION
    }

    private fun ResultSet.toGeneration(): TestCandidateGeneration = TestCandidateGeneration(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        knowledgeSnapshotId = getObject("knowledge_snapshot_id", UUID::class.java),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        source = TestCandidateGenerationSource.valueOf(getString("source")),
        generatorVersion = getString("generator_version"),
        checksum = getString("checksum"),
        createdBy = getString("created_by"),
        createdCorrelationId = getString("created_correlation_id"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private fun ResultSet.toCandidate(): TestCandidate {
        val detail = objectMapper.readValue(getString("detail_json"), StoredTestCandidateDetail::class.java)
        return TestCandidate(
            id = getObject("id", UUID::class.java),
            generationId = getObject("generation_id", UUID::class.java),
            sequenceNumber = getInt("sequence_number"),
            category = TestCandidateCategory.valueOf(getString("category")),
            title = getString("title"),
            description = getString("description"),
            risk = TestCandidateRisk.valueOf(getString("risk")),
            confidence = KnowledgeConfidence.valueOf(getString("confidence")),
            verifiedExpectation = detail.verifiedExpectation,
            preconditions = detail.preconditions,
            binding = detail.binding,
            citations = detail.citations,
            requiredEvidence = detail.requiredEvidence,
            dataLifecyclePlan = detail.dataLifecyclePlan,
        )
    }
}
