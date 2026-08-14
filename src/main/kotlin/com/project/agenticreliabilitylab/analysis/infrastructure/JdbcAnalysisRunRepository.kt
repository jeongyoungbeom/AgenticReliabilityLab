package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisCompletion
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisRunStore
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisRun
import com.project.agenticreliabilitylab.analysis.domain.AnalysisFindingRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRecommendationRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunDetails
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunStatus
import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import com.project.agenticreliabilitylab.analysis.domain.FindingSeverity
import com.project.agenticreliabilitylab.analysis.domain.RecommendationPriority
import com.project.agenticreliabilitylab.analysis.infrastructure.sql.AnalysisRunSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // One adapter persists the full analysis-run aggregate and its child records.
class JdbcAnalysisRunRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
    private val identifierGenerator: IdentifierGenerator,
) : AnalysisRunStore {
    override fun findById(id: UUID): AnalysisRunRecord? =
        jdbcClient.sql(AnalysisRunSql.FIND_BY_ID)
            .param("id", id)
            .query { resultSet, _ -> resultSet.toAnalysisRun() }
            .optional()
            .orElse(null)

    override fun findByExperimentAndIdempotencyKey(
        experimentRunId: UUID,
        idempotencyKey: String,
    ): AnalysisRunRecord? =
        jdbcClient.sql(AnalysisRunSql.FIND_BY_EXPERIMENT_AND_IDEMPOTENCY_KEY)
            .params(mapOf("experimentRunId" to experimentRunId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toAnalysisRun() }
            .optional()
            .orElse(null)

    override fun findByTargetTestBatchAndIdempotencyKey(
        targetTestBatchId: UUID,
        idempotencyKey: String,
    ): AnalysisRunRecord? =
        jdbcClient.sql(AnalysisRunSql.FIND_BY_BATCH_AND_IDEMPOTENCY_KEY)
            .params(mapOf("targetTestBatchId" to targetTestBatchId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toAnalysisRun() }
            .optional()
            .orElse(null)

    override fun findIdsByAgentTypeAndStatus(agentType: String, status: AnalysisRunStatus): List<UUID> =
        jdbcClient.sql(AnalysisRunSql.FIND_IDS_BY_AGENT_TYPE_AND_STATUS)
            .params(mapOf("agentType" to agentType, "status" to status.name))
            .query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }
            .list()

    override fun findDetails(id: UUID): AnalysisRunDetails? {
        val run = findById(id) ?: return null
        return AnalysisRunDetails(
            run = run,
            findings = findFindings(id),
            recommendations = findRecommendations(id),
        )
    }

    @Transactional
    override fun create(run: NewAnalysisRun) {
        jdbcClient.sql(AnalysisRunSql.INSERT_RUN).params(
            mapOf(
                "id" to run.id,
                "experimentRunId" to run.experimentRunId,
                "targetTestBatchId" to run.targetTestBatchId,
                "idempotencyKey" to run.idempotencyKey,
                "agentType" to run.agentType,
                "agentVersion" to run.agentVersion,
                "modelKey" to run.modelKey,
                "modelId" to run.modelId,
                "promptVersion" to run.promptVersion,
                "analysisDatasetId" to run.analysisDatasetId,
                "inputChecksum" to run.inputChecksum,
                "inputEvidenceCount" to run.inputEvidenceCount,
                "status" to AnalysisRunStatus.REQUESTED.name,
                "requestedAt" to Timestamp.from(run.requestedAt),
            ),
        ).update()
    }

    override fun claimForExecution(id: UUID, now: Instant): Boolean =
        jdbcClient.sql(AnalysisRunSql.CLAIM_FOR_EXECUTION).params(
            mapOf(
                "id" to id,
                "requested" to AnalysisRunStatus.REQUESTED.name,
                "running" to AnalysisRunStatus.RUNNING.name,
                "startedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    @Transactional
    @Suppress("LongMethod") // Completion must atomically persist the run and its evidence-cited children.
    override fun complete(id: UUID, completion: AnalysisCompletion, now: Instant) {
        jdbcClient.sql(AnalysisRunSql.COMPLETE).params(
            mapOf(
                "id" to id,
                "running" to AnalysisRunStatus.RUNNING.name,
                "status" to AnalysisRunStatus.COMPLETED.name,
                "summary" to completion.summary,
                "outputJson" to completion.outputJson,
                "verdict" to completion.verdict.name,
                "promptTokenCount" to completion.promptTokenCount,
                "completionTokenCount" to completion.completionTokenCount,
                "durationMillis" to completion.durationMillis,
                "completedAt" to Timestamp.from(now),
            ),
        ).update().also { updated ->
            check(updated == 1) { "Analysis run '$id' was not RUNNING when completion was recorded" }
        }

        completion.findings.forEachIndexed { index, finding ->
            jdbcClient.sql(AnalysisRunSql.INSERT_FINDING).params(
                mapOf(
                    "id" to identifierGenerator.next(),
                    "analysisRunId" to id,
                    "ordinal" to index + 1,
                    "severity" to finding.severity.name,
                    "title" to finding.title,
                    "rationale" to finding.rationale,
                    "evidenceRefsJson" to objectMapper.writeValueAsString(finding.evidenceIds),
                ),
            ).update()
        }
        completion.recommendations.forEachIndexed { index, recommendation ->
            jdbcClient.sql(AnalysisRunSql.INSERT_RECOMMENDATION).params(
                mapOf(
                    "id" to identifierGenerator.next(),
                    "analysisRunId" to id,
                    "ordinal" to index + 1,
                    "priority" to recommendation.priority.name,
                    "title" to recommendation.title,
                    "recommendedAction" to recommendation.recommendedAction,
                    "rationale" to recommendation.rationale,
                    "evidenceRefsJson" to objectMapper.writeValueAsString(recommendation.evidenceIds),
                ),
            ).update()
        }
    }

    override fun fail(id: UUID, failureCode: String, failureMessage: String, now: Instant) {
        jdbcClient.sql(AnalysisRunSql.FAIL).params(
            mapOf(
                "id" to id,
                "requested" to AnalysisRunStatus.REQUESTED.name,
                "running" to AnalysisRunStatus.RUNNING.name,
                "failed" to AnalysisRunStatus.FAILED.name,
                "failureCode" to failureCode.take(100),
                "failureMessage" to failureMessage.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    private fun findFindings(analysisRunId: UUID): List<AnalysisFindingRecord> =
        jdbcClient.sql(AnalysisRunSql.FIND_FINDINGS).param("analysisRunId", analysisRunId)
            .query { resultSet, _ ->
                AnalysisFindingRecord(
                    id = resultSet.getObject("id", UUID::class.java),
                    analysisRunId = resultSet.getObject("analysis_run_id", UUID::class.java),
                    ordinal = resultSet.getInt("ordinal"),
                    severity = FindingSeverity.valueOf(resultSet.getString("severity")),
                    title = resultSet.getString("title"),
                    rationale = resultSet.getString("rationale"),
                    evidenceIds = resultSet.getString("evidence_refs_json").toEvidenceIds(),
                )
            }.list()

    private fun findRecommendations(analysisRunId: UUID): List<AnalysisRecommendationRecord> =
        jdbcClient.sql(AnalysisRunSql.FIND_RECOMMENDATIONS).param("analysisRunId", analysisRunId)
            .query { resultSet, _ ->
                AnalysisRecommendationRecord(
                    id = resultSet.getObject("id", UUID::class.java),
                    analysisRunId = resultSet.getObject("analysis_run_id", UUID::class.java),
                    ordinal = resultSet.getInt("ordinal"),
                    priority = RecommendationPriority.valueOf(resultSet.getString("priority")),
                    title = resultSet.getString("title"),
                    recommendedAction = resultSet.getString("recommended_action"),
                    rationale = resultSet.getString("rationale"),
                    evidenceIds = resultSet.getString("evidence_refs_json").toEvidenceIds(),
                )
            }.list()

    private fun ResultSet.toAnalysisRun(): AnalysisRunRecord = AnalysisRunRecord(
        id = getObject("id", UUID::class.java),
        experimentRunId = getObject("experiment_run_id", UUID::class.java),
        targetTestBatchId = getObject("target_test_batch_id", UUID::class.java),
        idempotencyKey = getString("idempotency_key"),
        agentType = getString("agent_type"),
        agentVersion = getString("agent_version"),
        modelKey = getString("model_key") ?: "LEGACY",
        modelId = getString("model_id"),
        promptVersion = getString("prompt_version"),
        analysisDatasetId = getObject("analysis_dataset_id", UUID::class.java),
        inputChecksum = getString("input_checksum"),
        inputEvidenceCount = getObject("input_evidence_count") as Int?,
        status = AnalysisRunStatus.valueOf(getString("status")),
        verdict = getString("verdict")?.let(AnalysisVerdict::valueOf),
        summary = getString("summary"),
        outputJson = getString("output_json"),
        promptTokenCount = getObject("prompt_token_count") as Int?,
        completionTokenCount = getObject("completion_token_count") as Int?,
        durationMillis = getObject("duration_millis") as Long?,
        failureCode = getString("failure_code"),
        failureMessage = getString("failure_message"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )

    private fun String.toEvidenceIds(): List<String> {
        val root = objectMapper.readTree(this)
        require(root.isArray) { "Stored analysis evidence references must be an array" }
        return buildList {
            for (evidenceId in root) add(evidenceId.asString())
        }
    }

}
