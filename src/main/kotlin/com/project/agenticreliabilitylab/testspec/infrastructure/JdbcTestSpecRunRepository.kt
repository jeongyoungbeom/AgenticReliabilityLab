package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.TestSpecRunStore
import com.project.agenticreliabilitylab.testspec.domain.InvariantVerdict
import com.project.agenticreliabilitylab.testspec.domain.ResetCheck
import com.project.agenticreliabilitylab.testspec.domain.SpecRunOutcome
import com.project.agenticreliabilitylab.testspec.domain.StepTiming
import com.project.agenticreliabilitylab.testspec.domain.StoredResetResult
import com.project.agenticreliabilitylab.testspec.domain.ObservedEvidence
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import com.project.agenticreliabilitylab.testspec.infrastructure.sql.TestSpecRunSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private const val ACTIVE_RUN_SLOT = "ACTIVE"

@Repository
@Suppress("TooManyFunctions") // One adapter owns the run summary and its ordered trial and reset children.
class JdbcTestSpecRunRepository(
    private val jdbcClient: JdbcClient,
    private val objectMapper: ObjectMapper,
) : TestSpecRunStore {
    override fun create(run: TestSpecRun) {
        require(run.status == TestSpecRunStatus.PENDING) { "A new test specification run must be PENDING" }
        jdbcClient.sql(TestSpecRunSql.INSERT_RUN)
            .params(
                mapOf(
                    "id" to run.id,
                    "specificationId" to run.specificationId,
                    "targetSystemId" to run.targetSystemId,
                    "profileVersionId" to run.profileVersionId,
                    "status" to run.status.name,
                    "idempotencyKey" to run.idempotencyKey,
                    "requestHash" to run.requestHash,
                    "requestedTrials" to run.requestedTrials,
                    "createdBy" to run.createdBy,
                    "createdCorrelationId" to run.createdCorrelationId,
                    "createdAt" to Timestamp.from(run.createdAt),
                    "activeSlot" to ACTIVE_RUN_SLOT,
                ),
            )
            .update()
    }

    override fun findById(id: UUID): TestSpecRun? = jdbcClient.sql(TestSpecRunSql.FIND_BY_ID)
        .param("id", id)
        .query { resultSet, _ -> resultSet.toRun() }
        .optional()
        .orElse(null)

    override fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): TestSpecRun? =
        jdbcClient.sql(TestSpecRunSql.FIND_BY_TARGET_AND_IDEMPOTENCY_KEY)
            .params(mapOf("targetSystemId" to targetSystemId, "idempotencyKey" to idempotencyKey))
            .query { resultSet, _ -> resultSet.toRun() }
            .optional()
            .orElse(null)

    override fun hasBlockingRun(targetSystemId: String): Boolean =
        jdbcClient.sql(TestSpecRunSql.COUNT_BLOCKING)
            .param("targetSystemId", targetSystemId)
            .query(Long::class.java)
            .single() > 0

    override fun markRunning(id: UUID, startedAt: Instant): Boolean = jdbcClient.sql(TestSpecRunSql.MARK_RUNNING)
        .params(
            mapOf(
                "id" to id,
                "startedAt" to Timestamp.from(startedAt),
                "running" to TestSpecRunStatus.RUNNING.name,
                "pending" to TestSpecRunStatus.PENDING.name,
            ),
        )
        .update() == 1

    @Transactional
    override fun complete(id: UUID, outcome: SpecRunOutcome, completedAt: Instant): Boolean {
        require(outcome.runId == id.toString()) { "Run outcome '${outcome.runId}' does not belong to '$id'" }
        val executions = outcome.executions.associateBy(TrialExecution::trialNumber)
        require(outcome.result.trials.all { trial -> trial.trialNumber in executions }) {
            "Every trial verdict must have a matching execution record"
        }
        val status = if (outcome.cleanupVerified) TestSpecRunStatus.COMPLETED else TestSpecRunStatus.RECOVERY_REQUIRED
        val updated = jdbcClient.sql(TestSpecRunSql.MARK_COMPLETED)
            .params(
                mapOf(
                    "id" to id,
                    "status" to status.name,
                    "resultOutcome" to outcome.result.outcome.name,
                    "trialsRun" to outcome.result.trialsRun,
                    "trialsViolated" to outcome.result.trialsViolated,
                    "trialsInconclusive" to outcome.result.trialsInconclusive,
                    "cleanupVerified" to outcome.cleanupVerified,
                    "completedAt" to Timestamp.from(completedAt),
                    "activeSlot" to if (outcome.cleanupVerified) null else ACTIVE_RUN_SLOT,
                    "running" to TestSpecRunStatus.RUNNING.name,
                ),
            )
            .update()
        if (updated != 1) return false

        outcome.result.trials.forEach { trial -> insertTrial(id, trial, executions.getValue(trial.trialNumber)) }
        outcome.resets.forEachIndexed { index, reset ->
            jdbcClient.sql(TestSpecRunSql.INSERT_RESET)
                .params(
                    mapOf(
                        "runId" to id,
                        "sequenceNumber" to index + 1,
                        "performed" to reset.performed,
                        "verified" to reset.verified,
                        "checksJson" to objectMapper.writeValueAsString(reset.checks),
                        "failure" to reset.failure,
                    ),
                )
                .update()
        }
        return true
    }

    override fun markFailed(
        id: UUID,
        recoveryRequired: Boolean,
        failure: String,
        completedAt: Instant,
    ): Boolean = jdbcClient.sql(TestSpecRunSql.MARK_FAILED)
        .params(
            mapOf(
                "id" to id,
                "status" to if (recoveryRequired) {
                    TestSpecRunStatus.RECOVERY_REQUIRED.name
                } else {
                    TestSpecRunStatus.FAILED.name
                },
                "cleanupVerified" to !recoveryRequired,
                "completedAt" to Timestamp.from(completedAt),
                "failure" to failure,
                "activeSlot" to if (recoveryRequired) ACTIVE_RUN_SLOT else null,
                "pending" to TestSpecRunStatus.PENDING.name,
                "running" to TestSpecRunStatus.RUNNING.name,
            ),
        )
        .update() == 1

    @Transactional
    override fun recoverIncompleteRuns(completedAt: Instant): Int {
        val running = jdbcClient.sql(TestSpecRunSql.RECOVER_ORPHANED_RUNNING)
            .params(
                mapOf(
                    "recoveryRequired" to TestSpecRunStatus.RECOVERY_REQUIRED.name,
                    "completedAt" to Timestamp.from(completedAt),
                    "failure" to "Application restarted while Target requests could have been in progress",
                    "activeSlot" to ACTIVE_RUN_SLOT,
                    "running" to TestSpecRunStatus.RUNNING.name,
                ),
            )
            .update()
        val pending = jdbcClient.sql(TestSpecRunSql.FAIL_ORPHANED_PENDING)
            .params(
                mapOf(
                    "failed" to TestSpecRunStatus.FAILED.name,
                    "completedAt" to Timestamp.from(completedAt),
                    "failure" to "Application restarted before Target execution was claimed",
                    "pending" to TestSpecRunStatus.PENDING.name,
                ),
            )
            .update()
        return running + pending
    }

    override fun findTrials(runId: UUID): List<StoredTrialResult> = jdbcClient.sql(TestSpecRunSql.FIND_TRIALS)
        .param("runId", runId)
        .query { resultSet, _ -> resultSet.toTrial() }
        .list()

    override fun findResets(runId: UUID): List<StoredResetResult> = jdbcClient.sql(TestSpecRunSql.FIND_RESETS)
        .param("runId", runId)
        .query { resultSet, _ -> resultSet.toReset() }
        .list()

    private fun insertTrial(
        runId: UUID,
        trial: com.project.agenticreliabilitylab.testspec.domain.TrialResult,
        execution: TrialExecution,
    ) {
        jdbcClient.sql(TestSpecRunSql.INSERT_TRIAL)
            .params(
                mapOf(
                    "runId" to runId,
                    "trialNumber" to trial.trialNumber,
                    "outcome" to trial.outcome.name,
                    "stateChanged" to execution.stateChanged,
                    "completed" to execution.completed,
                    "failure" to execution.failure,
                    "verdictsJson" to objectMapper.writeValueAsString(trial.verdicts),
                    "timingsJson" to objectMapper.writeValueAsString(execution.timings),
                    "observationsJson" to observationsJson(trial.observations),
                ),
            )
            .update()
    }

    private fun ResultSet.toRun() = TestSpecRun(
        id = getObject("id", UUID::class.java),
        specificationId = getObject("specification_id", UUID::class.java),
        targetSystemId = getString("target_system_id"),
        profileVersionId = getObject("profile_version_id", UUID::class.java),
        status = TestSpecRunStatus.valueOf(getString("status")),
        idempotencyKey = getString("idempotency_key"),
        requestHash = getString("request_hash"),
        requestedTrials = getInt("requested_trials"),
        createdBy = getString("created_by"),
        createdCorrelationId = getString("created_correlation_id"),
        createdAt = getTimestamp("created_at").toInstant(),
        resultOutcome = getString("result_outcome")?.let(TrialOutcome::valueOf),
        trialsRun = getObject("trials_run", Int::class.javaObjectType),
        trialsViolated = getObject("trials_violated", Int::class.javaObjectType),
        trialsInconclusive = getObject("trials_inconclusive", Int::class.javaObjectType),
        cleanupVerified = getObject("cleanup_verified", Boolean::class.javaObjectType),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
        failure = getString("failure"),
    )

    private fun ResultSet.toTrial() = StoredTrialResult(
        runId = getObject("run_id", UUID::class.java),
        trialNumber = getInt("trial_number"),
        outcome = TrialOutcome.valueOf(getString("outcome")),
        stateChanged = getBoolean("state_changed"),
        completed = getBoolean("completed"),
        failure = getString("failure"),
        verdicts = objectMapper.readValue(
            getString("verdicts_json"),
            object : TypeReference<List<InvariantVerdict>>() {},
        ),
        timings = objectMapper.readValue(
            getString("timings_json"),
            object : TypeReference<List<StepTiming>>() {},
        ),
        observations = getString("observations_json")?.let { json ->
            objectMapper.readValue(json, object : TypeReference<Map<String, ObservedEvidence>>() {})
        } ?: emptyMap(),
    )

    /**
     * The observed values a trial was judged on, bounded so one trial cannot outgrow its row.
     *
     * When the whole set does not fit, the values are dropped and **the fact that they were dropped is written
     * down**. A record that quietly lost its evidence looks exactly like a record whose evidence was thin, and an
     * improvement suggestion built on the second is worth less than one that knows it is missing something.
     * Displays are kept either way: they are small and they are what an operator reads first.
     */
    private fun observationsJson(observations: Map<String, ObservedEvidence>): String {
        val full = objectMapper.writeValueAsString(observations)
        if (full.toByteArray(StandardCharsets.UTF_8).size <= MAX_OBSERVATIONS_BYTES) return full
        val omitted = observations.mapValues { (_, evidence) ->
            evidence.copy(
                value = null,
                omitted = "dropped: the trial's observed values exceeded $MAX_OBSERVATIONS_BYTES bytes",
            )
        }
        return objectMapper.writeValueAsString(omitted)
    }

    private fun ResultSet.toReset() = StoredResetResult(
        runId = getObject("run_id", UUID::class.java),
        sequenceNumber = getInt("sequence_number"),
        performed = getBoolean("performed"),
        verified = getBoolean("verified"),
        checks = objectMapper.readValue(
            getString("checks_json"),
            object : TypeReference<List<ResetCheck>>() {},
        ),
        failure = getString("failure"),
    )

    private companion object {
        /** What one trial's observed values may take in its row. Generous enough for a few hundred spans. */
        const val MAX_OBSERVATIONS_BYTES = 256 * 1024
    }
}
