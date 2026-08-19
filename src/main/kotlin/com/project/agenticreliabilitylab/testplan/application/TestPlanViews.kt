package com.project.agenticreliabilitylab.testplan.application

import com.project.agenticreliabilitylab.testplan.domain.TestPlan
import com.project.agenticreliabilitylab.testplan.domain.TestPlanExecutionReference
import com.project.agenticreliabilitylab.testplan.domain.TestPlanItem

/**
 * A plan plus what has to be derived at read time.
 *
 * Execution status is intentionally absent: the caller follows [executionReferences] to the aggregate that owns it, so
 * there is exactly one place that reports whether the work ran.
 */
data class TestPlanView(
    val plan: TestPlan,
    val profileVersionActive: Boolean,
    val items: List<TestPlanItem>,
    val executionReferences: List<TestPlanExecutionReference>,
)

/**
 * List projection without items or execution references.
 *
 * Loading those per row turned the list into several queries per plan. It is not wired to an endpoint yet; the
 * workbench screen that lists plans is the intended caller.
 */
data class TestPlanSummaryView(
    val plan: TestPlan,
    val profileVersionActive: Boolean,
)
