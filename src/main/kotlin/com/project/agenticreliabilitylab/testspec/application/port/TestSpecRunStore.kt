package com.project.agenticreliabilitylab.testspec.application.port

import com.project.agenticreliabilitylab.testspec.domain.SpecRunOutcome
import com.project.agenticreliabilitylab.testspec.domain.StoredResetResult
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import java.time.Instant
import java.util.UUID

interface TestSpecRunStore {
    fun create(run: TestSpecRun)
    fun findById(id: UUID): TestSpecRun?
    fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): TestSpecRun?
    /** PENDING, RUNNING and RECOVERY_REQUIRED runs all block a second execution against the same Target. */
    fun hasBlockingRun(targetSystemId: String): Boolean
    fun markRunning(id: UUID, startedAt: Instant): Boolean
    fun complete(id: UUID, outcome: SpecRunOutcome, completedAt: Instant): Boolean
    fun markFailed(id: UUID, recoveryRequired: Boolean, failure: String, completedAt: Instant): Boolean
    /** Reconciles process-crash leftovers before the application accepts new execution requests. */
    fun recoverIncompleteRuns(completedAt: Instant): Int
    fun findTrials(runId: UUID): List<StoredTrialResult>
    fun findResets(runId: UUID): List<StoredResetResult>
}
