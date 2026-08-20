package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.application.TestSpecRunView
import com.project.agenticreliabilitylab.testspec.domain.InvariantVerdict
import com.project.agenticreliabilitylab.testspec.domain.ResetCheck
import com.project.agenticreliabilitylab.testspec.domain.StepTiming
import com.project.agenticreliabilitylab.testspec.domain.StoredResetResult
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import java.time.Instant
import java.util.UUID

data class TestSpecRunResponse(
    val id: UUID,
    val specificationId: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val status: TestSpecRunStatus,
    val requestedTrials: Int,
    val resultOutcome: TrialOutcome?,
    val trialsRun: Int?,
    val trialsViolated: Int?,
    val trialsInconclusive: Int?,
    val cleanupVerified: Boolean?,
    val createdBy: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val failure: String?,
    val trials: List<TestSpecTrialResponse>,
    val resets: List<TestSpecResetResponse>,
) {
    companion object {
        fun from(view: TestSpecRunView): TestSpecRunResponse {
            val run = view.run
            return TestSpecRunResponse(
                id = run.id,
                specificationId = run.specificationId,
                targetSystemId = run.targetSystemId,
                profileVersionId = run.profileVersionId,
                status = run.status,
                requestedTrials = run.requestedTrials,
                resultOutcome = run.resultOutcome,
                trialsRun = run.trialsRun,
                trialsViolated = run.trialsViolated,
                trialsInconclusive = run.trialsInconclusive,
                cleanupVerified = run.cleanupVerified,
                createdBy = run.createdBy,
                createdAt = run.createdAt,
                startedAt = run.startedAt,
                completedAt = run.completedAt,
                failure = run.failure,
                trials = view.trials.map(TestSpecTrialResponse::from),
                resets = view.resets.map(TestSpecResetResponse::from),
            )
        }
    }
}

data class TestSpecTrialResponse(
    val trialNumber: Int,
    val outcome: TrialOutcome,
    val stateChanged: Boolean,
    val completed: Boolean,
    val failure: String?,
    val verdicts: List<InvariantVerdict>,
    val timings: List<StepTiming>,
) {
    companion object {
        fun from(trial: StoredTrialResult) = TestSpecTrialResponse(
            trialNumber = trial.trialNumber,
            outcome = trial.outcome,
            stateChanged = trial.stateChanged,
            completed = trial.completed,
            failure = trial.failure,
            verdicts = trial.verdicts,
            timings = trial.timings,
        )
    }
}

data class TestSpecResetResponse(
    val sequenceNumber: Int,
    val performed: Boolean,
    val verified: Boolean,
    val checks: List<ResetCheck>,
    val failure: String?,
) {
    companion object {
        fun from(reset: StoredResetResult) = TestSpecResetResponse(
            sequenceNumber = reset.sequenceNumber,
            performed = reset.performed,
            verified = reset.verified,
            checks = reset.checks,
            failure = reset.failure,
        )
    }
}
