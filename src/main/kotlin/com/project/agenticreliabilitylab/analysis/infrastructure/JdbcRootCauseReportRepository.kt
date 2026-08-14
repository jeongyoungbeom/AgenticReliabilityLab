package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.RootCauseReportCompletion
import com.project.agenticreliabilitylab.analysis.application.port.RootCauseReportStore
import com.project.agenticreliabilitylab.analysis.application.port.NewRootCauseReportRun
import com.project.agenticreliabilitylab.analysis.domain.HypothesisConfidence
import com.project.agenticreliabilitylab.analysis.domain.ImprovementProposalRecord
import com.project.agenticreliabilitylab.analysis.domain.RootCauseHypothesisRecord
import com.project.agenticreliabilitylab.analysis.domain.RootCauseReportDetails
import com.project.agenticreliabilitylab.analysis.domain.RootCauseReportRunRecord
import com.project.agenticreliabilitylab.analysis.domain.RootCauseReportStatus
import com.project.agenticreliabilitylab.analysis.infrastructure.sql.RootCauseReportSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Persistence for Phase 9's advisory-only diagnostic reports. */
@Repository
@Suppress("TooManyFunctions") // The report and its hypothesis/proposal children share one aggregate.
class JdbcRootCauseReportRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) : RootCauseReportStore {
    override fun create(command: NewRootCauseReportRun) {
        jdbcClient.sql(RootCauseReportSql.INSERT_RUN).params(
            mapOf(
                "id" to command.id, "analysisRunId" to command.analysisRunId, "idempotencyKey" to command.idempotencyKey,
                "configurationHash" to command.configurationHash, "modelKey" to command.modelKey, "modelId" to command.modelId,
                "promptVersion" to command.promptVersion, "inputBundleJson" to command.inputBundleJson,
                "inputChecksum" to command.inputChecksum, "status" to RootCauseReportStatus.REQUESTED.name,
                "requestedAt" to Timestamp.from(command.requestedAt),
            ),
        ).update()
    }

    override fun findById(id: UUID): RootCauseReportRunRecord? =
        queryRun(RootCauseReportSql.FIND_RUN_BY_ID, mapOf("id" to id))

    override fun findByAnalysisAndIdempotencyKey(
        analysisRunId: UUID,
        idempotencyKey: String,
    ): RootCauseReportRunRecord? =
        queryRun(
            RootCauseReportSql.FIND_RUN_BY_ANALYSIS_AND_IDEMPOTENCY_KEY,
            mapOf("analysisRunId" to analysisRunId, "idempotencyKey" to idempotencyKey),
        )

    override fun findDetails(id: UUID): RootCauseReportDetails? {
        val run = findById(id) ?: return null
        return RootCauseReportDetails(run, findHypotheses(id), findImprovementProposals(id))
    }

    override fun claim(id: UUID, now: Instant): Boolean = jdbcClient.sql(RootCauseReportSql.CLAIM).params(
        mapOf("id" to id, "requested" to RootCauseReportStatus.REQUESTED.name, "running" to RootCauseReportStatus.RUNNING.name, "startedAt" to Timestamp.from(now)),
    ).update() == 1

    @Transactional
    override fun complete(id: UUID, completion: RootCauseReportCompletion, now: Instant) {
        val updated = jdbcClient.sql(RootCauseReportSql.COMPLETE).params(
            mapOf(
                "id" to id, "running" to RootCauseReportStatus.RUNNING.name, "completed" to RootCauseReportStatus.COMPLETED.name,
                "outputJson" to completion.outputJson, "outputChecksum" to completion.outputChecksum, "promptTokenCount" to completion.promptTokenCount,
                "completionTokenCount" to completion.completionTokenCount, "durationMillis" to completion.durationMillis,
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
        check(updated == 1) { "Root-cause report '$id' was not RUNNING when completion was recorded" }

        completion.hypotheses.forEachIndexed { index, hypothesis ->
            jdbcClient.sql(RootCauseReportSql.INSERT_HYPOTHESIS).params(
                mapOf(
                    "id" to hypothesis.id, "reportRunId" to id, "ordinal" to index + 1, "title" to hypothesis.title,
                    "confidence" to hypothesis.confidence.name, "rationale" to hypothesis.rationale,
                    "falsifiability" to hypothesis.falsifiability, "evidenceIdsJson" to objectMapper.writeValueAsString(hypothesis.evidenceIds),
                ),
            ).update()
        }
        completion.improvementProposals.forEachIndexed { index, proposal ->
            jdbcClient.sql(RootCauseReportSql.INSERT_IMPROVEMENT_PROPOSAL).params(
                mapOf(
                    "id" to proposal.id, "reportRunId" to id, "ordinal" to index + 1,
                    "hypothesisOrdinal" to proposal.hypothesisOrdinal, "title" to proposal.title,
                    "proposedChange" to proposal.proposedChange, "expectedEffect" to proposal.expectedEffect,
                    "risk" to proposal.risk, "evidenceIdsJson" to objectMapper.writeValueAsString(proposal.evidenceIds),
                ),
            ).update()
        }
    }

    override fun fail(id: UUID, code: String, message: String, now: Instant) {
        jdbcClient.sql(RootCauseReportSql.FAIL).params(
            mapOf(
                "id" to id, "requested" to RootCauseReportStatus.REQUESTED.name, "running" to RootCauseReportStatus.RUNNING.name,
                "failed" to RootCauseReportStatus.FAILED.name, "failureCode" to code.take(100),
                "failureMessage" to message.take(2_000), "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun findRequestedIds(): List<UUID> = findIds(RootCauseReportStatus.REQUESTED)
    override fun findRunningIds(): List<UUID> = findIds(RootCauseReportStatus.RUNNING)

    private fun findIds(status: RootCauseReportStatus): List<UUID> = jdbcClient.sql(
        RootCauseReportSql.FIND_IDS_BY_STATUS,
    ).param("status", status.name).query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }.list()

    private fun findHypotheses(reportRunId: UUID): List<RootCauseHypothesisRecord> = jdbcClient.sql(
        RootCauseReportSql.FIND_HYPOTHESES_BY_REPORT_ID,
    ).param("reportRunId", reportRunId).query { resultSet, _ ->
        RootCauseHypothesisRecord(
            resultSet.getObject("id", UUID::class.java),
            resultSet.getObject("report_run_id", UUID::class.java),
            resultSet.getInt("ordinal"),
            resultSet.getString("title"),
            HypothesisConfidence.valueOf(resultSet.getString("confidence")),
            resultSet.getString("rationale"),
            resultSet.getString("falsifiability"),
            resultSet.getString("evidence_refs_json").toStringList(),
        )
    }.list()

    private fun findImprovementProposals(reportRunId: UUID): List<ImprovementProposalRecord> = jdbcClient.sql(
        RootCauseReportSql.FIND_IMPROVEMENT_PROPOSALS_BY_REPORT_ID,
    ).param("reportRunId", reportRunId).query { resultSet, _ ->
        ImprovementProposalRecord(
            resultSet.getObject("id", UUID::class.java),
            resultSet.getObject("report_run_id", UUID::class.java),
            resultSet.getInt("ordinal"),
            resultSet.getInt("hypothesis_ordinal"),
            resultSet.getString("title"),
            resultSet.getString("proposed_change"),
            resultSet.getString("expected_effect"),
            resultSet.getString("risk"),
            resultSet.getString("evidence_refs_json").toStringList(),
        )
    }.list()

    private fun queryRun(sql: String, params: Map<String, Any>): RootCauseReportRunRecord? = jdbcClient.sql(sql)
        .params(params).query { resultSet, _ -> resultSet.toRun() }.optional().orElse(null)

    private fun ResultSet.toRun() = RootCauseReportRunRecord(
        id = getObject("id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        idempotencyKey = getString("idempotency_key"),
        configurationHash = getString("configuration_hash"),
        modelKey = getString("model_key"),
        modelId = getString("model_id"),
        promptVersion = getString("prompt_version"),
        inputBundleJson = getString("input_bundle_json"),
        inputChecksum = getString("input_checksum"),
        outputChecksum = getString("output_checksum"),
        status = RootCauseReportStatus.valueOf(getString("status")),
        outputJson = getString("output_json"),
        promptTokenCount = nullableInt("prompt_token_count"),
        completionTokenCount = nullableInt("completion_token_count"),
        durationMillis = nullableLong("duration_millis"),
        failureCode = getString("failure_code"),
        failureMessage = getString("failure_message"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )

    private fun String.toStringList(): List<String> = objectMapper.readTree(this).values().map { it.asString() }
    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }
    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

}
