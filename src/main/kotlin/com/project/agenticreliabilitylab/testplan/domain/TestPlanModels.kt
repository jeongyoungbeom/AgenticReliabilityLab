package com.project.agenticreliabilitylab.testplan.domain

import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBindingKind
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateCategory
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateRisk
import java.time.Instant
import java.util.UUID

/**
 * Lifecycle of a selection and its approval.
 *
 * There is deliberately no RUNNING, COMPLETED, FAILED or RECOVERY_REQUIRED here. Those belong to the execution
 * aggregate the plan dispatched to, and duplicating them would create a second place that could disagree about what
 * actually happened.
 */
enum class TestPlanStatus {
    PENDING_APPROVAL,
    APPROVED,
    DISPATCHED,
    CANCELLED,
    SUPERSEDED,
}

/** Which existing aggregate a dispatched plan handed the work to. */
enum class TestPlanExecutionKind {
    TARGET_TEST_BATCH,
    EXPERIMENT_RUN,
}

/**
 * The confirmation phrase a plan requires, derived from the highest risk it contains.
 *
 * A plan carrying any state-changing item requires the write-test confirmation even if the rest is read-only, so the
 * approval a user gives always matches the most dangerous thing in the set.
 */
enum class TestPlanConfirmation(val phrase: String) {
    SAFE_READ_ONLY("EXECUTE_SAFE_TEST_PLAN"),
    STATE_CHANGING("EXECUTE_STATE_CHANGING_TEST_PLAN"),
    ;

    companion object {
        fun forRisks(risks: Collection<TestCandidateRisk>): TestPlanConfirmation =
            if (risks.all { risk -> risk == TestCandidateRisk.SAFE }) SAFE_READ_ONLY else STATE_CHANGING
    }
}

/** One selected candidate, snapshotted so later candidate edits cannot change what was approved. */
data class TestPlanItem(
    val id: UUID,
    val planId: UUID,
    val sequenceNumber: Int,
    val candidateId: UUID,
    val category: TestCandidateCategory,
    val risk: TestCandidateRisk,
    val bindingKind: ExecutionBindingKind,
    val targetTestCandidateIds: List<String>,
)

data class TestPlanExecutionReference(
    val id: UUID,
    val planId: UUID,
    val kind: TestPlanExecutionKind,
    val referenceId: UUID,
    val createdAt: Instant,
)

data class TestPlan(
    val id: UUID,
    val targetSystemId: String,
    val knowledgeSnapshotId: UUID,
    val generationId: UUID,
    val profileVersionId: UUID,
    val status: TestPlanStatus,
    val requiredConfirmation: TestPlanConfirmation,
    val idempotencyKey: String,
    val requestHash: String,
    val createdBy: String,
    val createdCorrelationId: String,
    val createdAt: Instant,
    val approvedBy: String? = null,
    val approvedCorrelationId: String? = null,
    val approvedAt: Instant? = null,
    val dispatchedAt: Instant? = null,
    val terminalReason: String? = null,
)
