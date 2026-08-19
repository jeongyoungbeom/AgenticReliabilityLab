package com.project.agenticreliabilitylab.testplan.api.dto

import com.project.agenticreliabilitylab.testplan.domain.TestPlanExecutionReference
import java.util.UUID

/** Pointer to the aggregate that owns execution state; the plan itself never reports run status. */
data class TestPlanExecutionReferenceResponse(
    val kind: String,
    val referenceId: UUID,
) {
    companion object {
        fun from(reference: TestPlanExecutionReference): TestPlanExecutionReferenceResponse =
            TestPlanExecutionReferenceResponse(kind = reference.kind.name, referenceId = reference.referenceId)
    }
}
