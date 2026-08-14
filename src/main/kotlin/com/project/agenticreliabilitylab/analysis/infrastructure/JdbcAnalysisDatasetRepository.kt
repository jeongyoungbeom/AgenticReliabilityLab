package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.AnalysisDatasetStore
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisDataset
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisGroundTruth
import com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisGroundTruthRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import com.project.agenticreliabilitylab.analysis.infrastructure.sql.AnalysisDatasetSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcAnalysisDatasetRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) : AnalysisDatasetStore {
    override fun create(dataset: NewAnalysisDataset) {
        jdbcClient.sql(AnalysisDatasetSql.INSERT_DATASET).params(
            mapOf(
                "id" to dataset.id,
                "experimentRunId" to dataset.experimentRunId,
                "targetTestBatchId" to dataset.targetTestBatchId,
                "contractVersion" to dataset.contractVersion,
                "evidenceBundleJson" to dataset.evidenceBundleJson,
                "evidenceIdsJson" to objectMapper.writeValueAsString(dataset.evidenceIds),
                "checksum" to dataset.checksum,
                "evidenceCount" to dataset.evidenceIds.size,
                "createdAt" to Timestamp.from(dataset.createdAt),
            ),
        ).update()
    }

    override fun findById(id: UUID): AnalysisDatasetRecord? =
        jdbcClient.sql(AnalysisDatasetSql.FIND_DATASET_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toDataset() }
            .optional()
            .orElse(null)

    override fun createGroundTruth(groundTruth: NewAnalysisGroundTruth) {
        jdbcClient.sql(AnalysisDatasetSql.INSERT_GROUND_TRUTH).params(
            mapOf(
                "id" to groundTruth.id,
                "analysisDatasetId" to groundTruth.analysisDatasetId,
                "version" to groundTruth.version,
                "expectedVerdict" to groundTruth.expectedVerdict.name,
                "requiredEvidenceIdsJson" to objectMapper.writeValueAsString(groundTruth.requiredEvidenceIds),
                "notes" to groundTruth.notes,
                "createdAt" to Timestamp.from(groundTruth.createdAt),
            ),
        ).update()
    }

    override fun findGroundTruth(id: UUID): AnalysisGroundTruthRecord? =
        jdbcClient.sql(AnalysisDatasetSql.FIND_GROUND_TRUTH_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toGroundTruth() }
            .optional()
            .orElse(null)

    private fun ResultSet.toDataset(): AnalysisDatasetRecord = AnalysisDatasetRecord(
        id = getObject("id", UUID::class.java),
        experimentRunId = getObject("experiment_run_id", UUID::class.java),
        targetTestBatchId = getObject("target_test_batch_id", UUID::class.java),
        contractVersion = getString("contract_version"),
        evidenceBundleJson = getString("evidence_bundle_json"),
        evidenceIds = getString("evidence_ids_json").toStringList(),
        checksum = getString("checksum"),
        evidenceCount = getInt("evidence_count"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private fun ResultSet.toGroundTruth(): AnalysisGroundTruthRecord = AnalysisGroundTruthRecord(
        id = getObject("id", UUID::class.java),
        analysisDatasetId = getObject("analysis_dataset_id", UUID::class.java),
        version = getString("ground_truth_version"),
        expectedVerdict = AnalysisVerdict.valueOf(getString("expected_verdict")),
        requiredEvidenceIds = getString("required_evidence_ids_json").toStringList(),
        notes = getString("notes"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private fun String.toStringList(): List<String> {
        val root = objectMapper.readTree(this)
        require(root.isArray) { "Stored evidence IDs must be an array" }
        return root.values().map { it.asString() }
    }

}
