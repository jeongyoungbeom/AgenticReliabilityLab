package com.project.agenticreliabilitylab.testplan.api.dto

import com.project.agenticreliabilitylab.testplan.application.TestPlanView
import java.time.Instant
import java.util.UUID

data class TestPlanResponse(
    val id: UUID,
    val targetSystemId: String,
    val knowledgeSnapshotId: UUID,
    val generationId: UUID,
    val profileVersionId: UUID,
    val profileVersionActive: Boolean,
    val status: String,
    val requiredConfirmation: String,
    val createdBy: String,
    val createdAt: Instant,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val dispatchedAt: Instant?,
    val terminalReason: String?,
    val items: List<TestPlanItemResponse>,
    val executionReferences: List<TestPlanExecutionReferenceResponse>,
) {
    companion object {
        fun from(view: TestPlanView): TestPlanResponse {
            val plan = view.plan
            return TestPlanResponse(
                id = plan.id,
                targetSystemId = plan.targetSystemId,
                knowledgeSnapshotId = plan.knowledgeSnapshotId,
                generationId = plan.generationId,
                profileVersionId = plan.profileVersionId,
                profileVersionActive = view.profileVersionActive,
                status = plan.status.name,
                requiredConfirmation = plan.requiredConfirmation.phrase,
                createdBy = plan.createdBy,
                createdAt = plan.createdAt,
                approvedBy = plan.approvedBy,
                approvedAt = plan.approvedAt,
                dispatchedAt = plan.dispatchedAt,
                terminalReason = plan.terminalReason,
                items = view.items.map(TestPlanItemResponse::from),
                executionReferences = view.executionReferences.map(TestPlanExecutionReferenceResponse::from),
            )
        }
    }
}
