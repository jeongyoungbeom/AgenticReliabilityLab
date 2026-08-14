package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.AnalysisEvaluationStore
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisComparison
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisEvaluation
import com.project.agenticreliabilitylab.analysis.domain.AnalysisComparisonRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisComparisonRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisEvaluationRecord
import com.project.agenticreliabilitylab.analysis.infrastructure.sql.AnalysisEvaluationSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class JdbcAnalysisEvaluationRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) : AnalysisEvaluationStore {
    override fun createComparison(comparison: NewAnalysisComparison) {
        jdbcClient.sql(AnalysisEvaluationSql.INSERT_COMPARISON).params(
            mapOf(
                "id" to comparison.id,
                "experimentRunId" to comparison.experimentRunId,
                "targetTestBatchId" to comparison.targetTestBatchId,
                "analysisDatasetId" to comparison.analysisDatasetId,
                "idempotencyKey" to comparison.idempotencyKey,
                "modelKeysJson" to objectMapper.writeValueAsString(comparison.modelKeys),
                "configurationJson" to comparison.configurationJson,
                "configurationHash" to comparison.configurationHash,
                "requestedAt" to Timestamp.from(comparison.requestedAt),
            ),
        ).update()
    }

    override fun findComparison(id: UUID): AnalysisComparisonRecord? =
        jdbcClient.sql(AnalysisEvaluationSql.FIND_COMPARISON_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toComparison() }
            .optional()
            .orElse(null)

    override fun findComparisonByExperimentAndIdempotencyKey(
        experimentRunId: UUID,
        idempotencyKey: String,
    ): AnalysisComparisonRecord? =
        jdbcClient.sql(AnalysisEvaluationSql.FIND_COMPARISON_BY_EXPERIMENT_AND_IDEMPOTENCY_KEY)
            .params(mapOf("experimentRunId" to experimentRunId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toComparison() }
            .optional()
            .orElse(null)

    override fun findComparisonByTargetTestBatchAndIdempotencyKey(
        targetTestBatchId: UUID,
        idempotencyKey: String,
    ): AnalysisComparisonRecord? =
        jdbcClient.sql(AnalysisEvaluationSql.FIND_COMPARISON_BY_BATCH_AND_IDEMPOTENCY_KEY)
            .params(mapOf("targetTestBatchId" to targetTestBatchId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toComparison() }
            .optional()
            .orElse(null)

    override fun attachComparisonRun(comparisonId: UUID, modelKey: String, analysisRunId: UUID) {
        jdbcClient.sql(AnalysisEvaluationSql.INSERT_COMPARISON_RUN)
            .params(
                mapOf(
                    "comparisonId" to comparisonId,
                    "modelKey" to modelKey,
                    "analysisRunId" to analysisRunId,
                ),
            ).update()
    }

    override fun findComparisonRuns(comparisonId: UUID): List<AnalysisComparisonRunRecord> =
        jdbcClient.sql(AnalysisEvaluationSql.FIND_COMPARISON_RUNS).param("comparisonId", comparisonId)
            .query { resultSet, _ ->
                AnalysisComparisonRunRecord(
                    comparisonId = resultSet.getObject("analysis_comparison_id", UUID::class.java),
                    modelKey = resultSet.getString("model_key"),
                    analysisRunId = resultSet.getObject("analysis_run_id", UUID::class.java),
                )
            }.list()

    override fun createEvaluation(evaluation: NewAnalysisEvaluation) {
        jdbcClient.sql(AnalysisEvaluationSql.INSERT_EVALUATION).params(
            mapOf(
                "id" to evaluation.id,
                "analysisRunId" to evaluation.analysisRunId,
                "analysisGroundTruthId" to evaluation.analysisGroundTruthId,
                "evaluationVersion" to evaluation.evaluationVersion,
                "verdictMatch" to evaluation.verdictMatch,
                "citedRequiredEvidenceCount" to evaluation.citedRequiredEvidenceCount,
                "requiredEvidenceCount" to evaluation.requiredEvidenceCount,
                "citationRecall" to evaluation.citationRecall,
                "score" to evaluation.score,
                "evaluatedAt" to Timestamp.from(evaluation.evaluatedAt),
            ),
        ).update()
    }

    override fun findEvaluation(
        analysisRunId: UUID,
        groundTruthId: UUID,
        evaluationVersion: String,
    ): AnalysisEvaluationRecord? =
        jdbcClient.sql(AnalysisEvaluationSql.FIND_EVALUATION).params(
            mapOf(
                "analysisRunId" to analysisRunId,
                "groundTruthId" to groundTruthId,
                "evaluationVersion" to evaluationVersion,
            ),
        ).query { resultSet, _ -> resultSet.toEvaluation() }
            .optional()
            .orElse(null)

    private fun ResultSet.toComparison(): AnalysisComparisonRecord = AnalysisComparisonRecord(
        id = getObject("id", UUID::class.java),
        experimentRunId = getObject("experiment_run_id", UUID::class.java),
        targetTestBatchId = getObject("target_test_batch_id", UUID::class.java),
        analysisDatasetId = getObject("analysis_dataset_id", UUID::class.java),
        idempotencyKey = getString("idempotency_key"),
        modelKeys = getString("model_keys_json").toStringList(),
        configurationJson = getString("configuration_json"),
        configurationHash = getString("configuration_hash"),
        requestedAt = getTimestamp("requested_at").toInstant(),
    )

    private fun ResultSet.toEvaluation(): AnalysisEvaluationRecord = AnalysisEvaluationRecord(
        id = getObject("id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        analysisGroundTruthId = getObject("analysis_ground_truth_id", UUID::class.java),
        evaluationVersion = getString("evaluation_version"),
        verdictMatch = getBoolean("verdict_match"),
        citedRequiredEvidenceCount = getInt("cited_required_evidence_count"),
        requiredEvidenceCount = getInt("required_evidence_count"),
        citationRecall = getDouble("citation_recall"),
        score = getDouble("score"),
        evaluatedAt = getTimestamp("evaluated_at").toInstant(),
    )

    private fun String.toStringList(): List<String> {
        val root = objectMapper.readTree(this)
        require(root.isArray) { "Stored model keys must be an array" }
        return root.values().map { it.asString() }
    }

}
