package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.NewTestSpecMisjudgmentReport
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecMisjudgmentCompletion
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecMisjudgmentReportStore
import com.project.agenticreliabilitylab.testspec.domain.TestSpecMisjudgmentReportRecord
import com.project.agenticreliabilitylab.testspec.domain.TestSpecMisjudgmentReportStatus
import com.project.agenticreliabilitylab.testspec.infrastructure.sql.TestSpecMisjudgmentReportSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // One misjudgment-report aggregate owns lifecycle transitions, lookups, and row mapping.
class JdbcTestSpecMisjudgmentReportRepository(
    private val jdbcClient: JdbcClient,
) : TestSpecMisjudgmentReportStore {
    override fun create(command: NewTestSpecMisjudgmentReport) {
        jdbcClient.sql(TestSpecMisjudgmentReportSql.INSERT).params(
            mapOf(
                "id" to command.id,
                "targetSystemId" to command.targetSystemId,
                "specificationId" to command.specificationId,
                "runId" to command.runId,
                "trialNumber" to command.trialNumber,
                "invariantId" to command.invariantId,
                "reason" to command.reason,
                "idempotencyKey" to command.idempotencyKey,
                "requestHash" to command.requestHash,
                "modelKey" to command.modelKey,
                "modelId" to command.modelId,
                "promptVersion" to command.promptVersion,
                "status" to TestSpecMisjudgmentReportStatus.REQUESTED.name,
                "requestedBy" to command.requestedBy,
                "requestedCorrelationId" to command.requestedCorrelationId,
                "requestedAt" to Timestamp.from(command.requestedAt),
            ),
        ).update()
    }

    override fun findById(id: UUID): TestSpecMisjudgmentReportRecord? =
        queryOne(TestSpecMisjudgmentReportSql.FIND_BY_ID, mapOf("id" to id))

    override fun findByTargetAndIdempotencyKey(
        targetSystemId: String,
        idempotencyKey: String,
    ): TestSpecMisjudgmentReportRecord? = queryOne(
        TestSpecMisjudgmentReportSql.FIND_BY_TARGET_AND_IDEMPOTENCY_KEY,
        mapOf("targetSystemId" to targetSystemId, "idempotencyKey" to idempotencyKey),
    )

    override fun claim(id: UUID, now: Instant): Boolean = jdbcClient.sql(TestSpecMisjudgmentReportSql.CLAIM)
        .params(
            mapOf(
                "id" to id,
                "requested" to TestSpecMisjudgmentReportStatus.REQUESTED.name,
                "running" to TestSpecMisjudgmentReportStatus.RUNNING.name,
                "startedAt" to Timestamp.from(now),
            ),
        ).update() == 1

    override fun complete(id: UUID, completion: TestSpecMisjudgmentCompletion, now: Instant) {
        val updated = jdbcClient.sql(TestSpecMisjudgmentReportSql.COMPLETE)
            .params(
                mapOf(
                    "id" to id,
                    "running" to TestSpecMisjudgmentReportStatus.RUNNING.name,
                    "status" to completion.status.name,
                    "draftedCondition" to completion.draftedCondition,
                    "draftedDescription" to completion.draftedDescription,
                    "resultingSpecificationId" to completion.resultingSpecificationId,
                    "rejectionReason" to completion.rejectionReason,
                    "promptTokenCount" to completion.promptTokenCount,
                    "completionTokenCount" to completion.completionTokenCount,
                    "durationMillis" to completion.durationMillis,
                    "completedAt" to Timestamp.from(now),
                ),
            ).update()
        check(updated == 1) { "Misjudgment report '$id' was not RUNNING when completion was recorded" }
    }

    override fun fail(id: UUID, code: String, message: String, now: Instant) {
        jdbcClient.sql(TestSpecMisjudgmentReportSql.FAIL)
            .params(
                mapOf(
                    "id" to id,
                    "requested" to TestSpecMisjudgmentReportStatus.REQUESTED.name,
                    "running" to TestSpecMisjudgmentReportStatus.RUNNING.name,
                    "failed" to TestSpecMisjudgmentReportStatus.FAILED.name,
                    "failureCode" to code,
                    "failureMessage" to message.take(MAX_FAILURE_MESSAGE_CHARACTERS),
                    "completedAt" to Timestamp.from(now),
                ),
            ).update()
    }

    override fun findRequestedIds(): List<UUID> = findIds(TestSpecMisjudgmentReportStatus.REQUESTED)
    override fun findRunningIds(): List<UUID> = findIds(TestSpecMisjudgmentReportStatus.RUNNING)

    private fun findIds(status: TestSpecMisjudgmentReportStatus): List<UUID> = jdbcClient
        .sql(TestSpecMisjudgmentReportSql.FIND_IDS_BY_STATUS)
        .param("status", status.name)
        .query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }
        .list()

    private fun queryOne(sql: String, params: Map<String, Any>): TestSpecMisjudgmentReportRecord? =
        jdbcClient.sql(sql)
            .params(params)
            .query { resultSet, _ -> resultSet.toRecord() }
            .optional()
            .orElse(null)

    private fun ResultSet.toRecord() = TestSpecMisjudgmentReportRecord(
        id = getObject("id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        specificationId = getObject("specification_id", UUID::class.java),
        runId = getObject("run_id", UUID::class.java),
        trialNumber = getInt("trial_number"),
        invariantId = getString("invariant_id"),
        reason = getString("reason"),
        idempotencyKey = getString("idempotency_key"),
        requestHash = getString("request_hash"),
        modelKey = getString("model_key"),
        modelId = getString("model_id"),
        promptVersion = getString("prompt_version"),
        status = TestSpecMisjudgmentReportStatus.valueOf(getString("status")),
        draftedCondition = getString("drafted_condition"),
        draftedDescription = getString("drafted_description"),
        resultingSpecificationId = getObject("resulting_specification_id", UUID::class.java),
        rejectionReason = getString("rejection_reason"),
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

    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).takeUnless { wasNull() }
    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private companion object {
        const val MAX_FAILURE_MESSAGE_CHARACTERS = 2_000
    }
}
