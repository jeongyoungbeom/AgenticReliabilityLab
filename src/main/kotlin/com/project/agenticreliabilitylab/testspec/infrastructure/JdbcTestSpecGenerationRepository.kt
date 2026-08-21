package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.NewTestSpecGenerationRun
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecGenerationCompletion
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecGenerationStore
import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationCandidateOutcome
import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationCandidateRecord
import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationRunDetails
import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationRunRecord
import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationRunStatus
import com.project.agenticreliabilitylab.testspec.infrastructure.sql.TestSpecGenerationSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // The run and its proposed child candidates complete atomically.
class JdbcTestSpecGenerationRepository(
    private val jdbcClient: JdbcClient,
) : TestSpecGenerationStore {
    override fun create(command: NewTestSpecGenerationRun) {
        jdbcClient.sql(TestSpecGenerationSql.INSERT_RUN).params(
            mapOf(
                "id" to command.id,
                "targetSystemId" to command.targetSystemId,
                "knowledgeSnapshotId" to command.knowledgeSnapshotId,
                "profileVersionId" to command.profileVersionId,
                "idempotencyKey" to command.idempotencyKey,
                "configurationHash" to command.configurationHash,
                "modelKey" to command.modelKey,
                "modelId" to command.modelId,
                "promptVersion" to command.promptVersion,
                "inputBundleJson" to command.inputBundleJson,
                "inputChecksum" to command.inputChecksum,
                "status" to TestSpecGenerationRunStatus.REQUESTED.name,
                "requestedBy" to command.requestedBy,
                "requestedCorrelationId" to command.requestedCorrelationId,
                "requestedAt" to Timestamp.from(command.requestedAt),
            ),
        ).update()
    }

    override fun findById(id: UUID): TestSpecGenerationRunRecord? =
        queryRun(TestSpecGenerationSql.FIND_RUN_BY_ID, mapOf("id" to id))

    override fun findByTargetAndIdempotencyKey(
        targetSystemId: String,
        idempotencyKey: String,
    ): TestSpecGenerationRunRecord? = queryRun(
        TestSpecGenerationSql.FIND_RUN_BY_TARGET_AND_IDEMPOTENCY_KEY,
        mapOf("targetSystemId" to targetSystemId, "idempotencyKey" to idempotencyKey),
    )

    override fun findDetails(id: UUID): TestSpecGenerationRunDetails? {
        val run = findById(id) ?: return null
        return TestSpecGenerationRunDetails(run, findCandidates(id))
    }

    override fun claim(id: UUID, now: Instant): Boolean = jdbcClient.sql(TestSpecGenerationSql.CLAIM)
        .params(
            mapOf(
                "id" to id,
                "requested" to TestSpecGenerationRunStatus.REQUESTED.name,
                "running" to TestSpecGenerationRunStatus.RUNNING.name,
                "startedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    @Transactional
    override fun complete(id: UUID, completion: TestSpecGenerationCompletion, now: Instant) {
        val updated = jdbcClient.sql(TestSpecGenerationSql.COMPLETE)
            .params(
                mapOf(
                    "id" to id,
                    "running" to TestSpecGenerationRunStatus.RUNNING.name,
                    "completed" to TestSpecGenerationRunStatus.COMPLETED.name,
                    "promptTokenCount" to completion.promptTokenCount,
                    "completionTokenCount" to completion.completionTokenCount,
                    "durationMillis" to completion.durationMillis,
                    "completedAt" to Timestamp.from(now),
                ),
            ).update()
        check(updated == 1) { "Test specification generation run '$id' was not RUNNING when completion was recorded" }
        completion.candidates.forEachIndexed { index, candidate ->
            jdbcClient.sql(TestSpecGenerationSql.INSERT_CANDIDATE)
                .params(
                    mapOf(
                        "id" to candidate.id,
                        "runId" to id,
                        "ordinal" to index + 1,
                        "outcome" to candidate.outcome.name,
                        "specKey" to candidate.specKey,
                        "title" to candidate.title,
                        "documentJson" to candidate.documentJson,
                        "rejectionReason" to candidate.rejectionReason,
                        "specificationId" to candidate.specificationId,
                    ),
                ).update()
        }
    }

    override fun fail(id: UUID, code: String, message: String, now: Instant) {
        jdbcClient.sql(TestSpecGenerationSql.FAIL)
            .params(
                mapOf(
                    "id" to id,
                    "requested" to TestSpecGenerationRunStatus.REQUESTED.name,
                    "running" to TestSpecGenerationRunStatus.RUNNING.name,
                    "failed" to TestSpecGenerationRunStatus.FAILED.name,
                    "failureCode" to code,
                    "failureMessage" to message.take(MAX_FAILURE_MESSAGE_CHARACTERS),
                    "completedAt" to Timestamp.from(now),
                ),
            ).update()
    }

    override fun findRequestedIds(): List<UUID> = findIds(TestSpecGenerationRunStatus.REQUESTED)
    override fun findRunningIds(): List<UUID> = findIds(TestSpecGenerationRunStatus.RUNNING)

    private fun findIds(status: TestSpecGenerationRunStatus): List<UUID> = jdbcClient
        .sql(TestSpecGenerationSql.FIND_IDS_BY_STATUS)
        .param("status", status.name)
        .query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }
        .list()

    private fun findCandidates(runId: UUID): List<TestSpecGenerationCandidateRecord> = jdbcClient
        .sql(TestSpecGenerationSql.FIND_CANDIDATES_BY_RUN_ID)
        .param("runId", runId)
        .query { resultSet, _ -> resultSet.toCandidate() }
        .list()

    private fun queryRun(sql: String, params: Map<String, Any>): TestSpecGenerationRunRecord? = jdbcClient.sql(sql)
        .params(params)
        .query { resultSet, _ -> resultSet.toRun() }
        .optional()
        .orElse(null)

    private fun ResultSet.toRun() = TestSpecGenerationRunRecord(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        knowledgeSnapshotId = getObject("knowledge_snapshot_id", UUID::class.java),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        idempotencyKey = getString("idempotency_key"),
        configurationHash = getString("configuration_hash"),
        modelKey = getString("model_key"),
        modelId = getString("model_id"),
        promptVersion = getString("prompt_version"),
        inputBundleJson = getString("input_bundle_json"),
        inputChecksum = getString("input_checksum"),
        status = TestSpecGenerationRunStatus.valueOf(getString("status")),
        promptTokenCount = nullableInt("prompt_token_count"),
        completionTokenCount = nullableInt("completion_token_count"),
        durationMillis = nullableLong("duration_millis"),
        failureCode = getString("failure_code"),
        failureMessage = getString("failure_message"),
        requestedBy = getString("requested_by"),
        requestedCorrelationId = getString("requested_correlation_id"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )

    private fun ResultSet.toCandidate() = TestSpecGenerationCandidateRecord(
        id = getObject("id", UUID::class.java),
        runId = getObject("run_id", UUID::class.java),
        ordinal = getInt("ordinal"),
        outcome = TestSpecGenerationCandidateOutcome.valueOf(getString("outcome")),
        specKey = getString("spec_key"),
        title = getString("title"),
        documentJson = getString("document_json"),
        rejectionReason = getString("rejection_reason"),
        specificationId = getObject("specification_id", UUID::class.java),
    )

    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }
    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private companion object {
        const val MAX_FAILURE_MESSAGE_CHARACTERS = 2_000
    }
}
