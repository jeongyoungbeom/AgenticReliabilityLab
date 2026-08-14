package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.FollowUpSuggestionCompletion
import com.project.agenticreliabilitylab.analysis.application.port.FollowUpSuggestionStore
import com.project.agenticreliabilitylab.analysis.application.port.NewFollowUpSuggestionRun
import com.project.agenticreliabilitylab.analysis.domain.FollowUpSuggestionRunDetails
import com.project.agenticreliabilitylab.analysis.domain.FollowUpSuggestionRunRecord
import com.project.agenticreliabilitylab.analysis.domain.FollowUpSuggestionRunStatus
import com.project.agenticreliabilitylab.analysis.domain.FollowUpTestSuggestionRecord
import com.project.agenticreliabilitylab.analysis.infrastructure.sql.FollowUpSuggestionSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // The run and its suggested child records complete atomically.
class JdbcFollowUpSuggestionRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) : FollowUpSuggestionStore {
    override fun create(command: NewFollowUpSuggestionRun) {
        jdbcClient.sql(FollowUpSuggestionSql.INSERT_RUN).params(
            mapOf(
                "id" to command.id, "analysisRunId" to command.analysisRunId, "targetTestBatchId" to command.targetTestBatchId,
                "idempotencyKey" to command.idempotencyKey, "configurationHash" to command.configurationHash,
                "modelKey" to command.modelKey, "modelId" to command.modelId, "promptVersion" to command.promptVersion,
                "inputBundleJson" to command.inputBundleJson, "inputChecksum" to command.inputChecksum,
                "status" to FollowUpSuggestionRunStatus.REQUESTED.name, "requestedAt" to Timestamp.from(command.requestedAt),
            ),
        ).update()
    }

    override fun findById(id: UUID): FollowUpSuggestionRunRecord? =
        queryRun(FollowUpSuggestionSql.FIND_RUN_BY_ID, mapOf("id" to id))

    override fun findByAnalysisAndIdempotencyKey(
        analysisRunId: UUID,
        idempotencyKey: String,
    ): FollowUpSuggestionRunRecord? =
        queryRun(
            FollowUpSuggestionSql.FIND_RUN_BY_ANALYSIS_AND_IDEMPOTENCY_KEY,
            mapOf("analysisRunId" to analysisRunId, "idempotencyKey" to idempotencyKey),
        )

    override fun findDetails(id: UUID): FollowUpSuggestionRunDetails? {
        val run = findById(id) ?: return null
        return FollowUpSuggestionRunDetails(run, findSuggestions(id))
    }

    override fun claim(id: UUID, now: Instant): Boolean = jdbcClient.sql(FollowUpSuggestionSql.CLAIM)
        .params(
            mapOf(
                "id" to id,
                "requested" to FollowUpSuggestionRunStatus.REQUESTED.name,
                "running" to FollowUpSuggestionRunStatus.RUNNING.name,
                "startedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    @Transactional
    override fun complete(id: UUID, completion: FollowUpSuggestionCompletion, now: Instant) {
        val updated = jdbcClient.sql(FollowUpSuggestionSql.COMPLETE)
            .params(mapOf("id" to id, "running" to FollowUpSuggestionRunStatus.RUNNING.name,
                "completed" to FollowUpSuggestionRunStatus.COMPLETED.name,
            "outputJson" to completion.outputJson, "promptTokenCount" to completion.promptTokenCount,
            "completionTokenCount" to completion.completionTokenCount, "durationMillis" to completion.durationMillis, "completedAt" to Timestamp.from(now))).update()
        check(updated == 1) { "Follow-up suggestion run '$id' was not RUNNING when completion was recorded" }
        completion.suggestions.forEachIndexed { index, suggestion ->
            jdbcClient.sql(FollowUpSuggestionSql.INSERT_SUGGESTION)
                .params(mapOf("id" to suggestion.id, "suggestionRunId" to id, "ordinal" to index + 1,
                "candidateId" to suggestion.candidateId, "candidateTitle" to suggestion.candidateTitle,
                "rationale" to suggestion.rationale, "evidenceIdsJson" to objectMapper.writeValueAsString(suggestion.evidenceIds))).update()
        }
    }

    override fun fail(id: UUID, code: String, message: String, now: Instant) {
        jdbcClient.sql(FollowUpSuggestionSql.FAIL)
            .params(
                mapOf(
                    "id" to id,
                    "requested" to FollowUpSuggestionRunStatus.REQUESTED.name,
                    "running" to FollowUpSuggestionRunStatus.RUNNING.name,
                    "failed" to FollowUpSuggestionRunStatus.FAILED.name,
                    "failureCode" to code,
                    "failureMessage" to message.take(2_000),
                    "completedAt" to Timestamp.from(now),
                ),
            ).update()
    }

    override fun findRequestedIds(): List<UUID> = findIds(FollowUpSuggestionRunStatus.REQUESTED)
    override fun findRunningIds(): List<UUID> = findIds(FollowUpSuggestionRunStatus.RUNNING)

    private fun findIds(status: FollowUpSuggestionRunStatus): List<UUID> = jdbcClient.sql(
        FollowUpSuggestionSql.FIND_IDS_BY_STATUS,
    ).param("status", status.name).query { rs, _ -> rs.getObject("id", UUID::class.java) }.list()

    private fun findSuggestions(runId: UUID): List<FollowUpTestSuggestionRecord> = jdbcClient.sql(
        FollowUpSuggestionSql.FIND_SUGGESTIONS_BY_RUN_ID,
    ).param("runId", runId).query { rs, _ ->
        FollowUpTestSuggestionRecord(
            rs.getObject("id", UUID::class.java),
            rs.getObject("suggestion_run_id", UUID::class.java),
            rs.getInt("ordinal"),
            rs.getString("candidate_id"),
            rs.getString("candidate_title"),
            rs.getString("rationale"),
            rs.getString("evidence_refs_json").toStringList(),
        )
    }.list()

    private fun queryRun(sql: String, params: Map<String, Any>): FollowUpSuggestionRunRecord? = jdbcClient.sql(sql)
        .params(params).query { rs, _ -> rs.toRun() }.optional().orElse(null)

    private fun ResultSet.toRun() = FollowUpSuggestionRunRecord(
        id = getObject("id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        targetTestBatchId = getObject("target_test_batch_id", UUID::class.java),
        idempotencyKey = getString("idempotency_key"),
        configurationHash = getString("configuration_hash"),
        modelKey = getString("model_key"),
        modelId = getString("model_id"),
        promptVersion = getString("prompt_version"),
        inputBundleJson = getString("input_bundle_json"),
        inputChecksum = getString("input_checksum"),
        status = FollowUpSuggestionRunStatus.valueOf(getString("status")),
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
