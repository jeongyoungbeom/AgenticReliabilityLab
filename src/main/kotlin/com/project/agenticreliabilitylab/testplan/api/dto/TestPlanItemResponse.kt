package com.project.agenticreliabilitylab.testplan.api.dto

import com.project.agenticreliabilitylab.testplan.domain.TestPlanItem
import java.util.UUID

data class TestPlanItemResponse(
    val id: UUID,
    val sequenceNumber: Int,
    val candidateId: UUID,
    val category: String,
    val risk: String,
    val bindingKind: String,
    val targetTestCandidateIds: List<String>,
) {
    companion object {
        fun from(item: TestPlanItem): TestPlanItemResponse = TestPlanItemResponse(
            id = item.id,
            sequenceNumber = item.sequenceNumber,
            candidateId = item.candidateId,
            category = item.category.name,
            risk = item.risk.name,
            bindingKind = item.bindingKind.name,
            targetTestCandidateIds = item.targetTestCandidateIds,
        )
    }
}
