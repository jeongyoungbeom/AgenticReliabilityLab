package com.project.agenticreliabilitylab.testspec.application

import java.util.UUID

/**
 * A reviewer's claim that one invariant's `VIOLATED` verdict on one run was wrong.
 *
 * [specificationId] and [runId] both come from the reviewer's own screen, not from trusted server state, so the
 * service re-derives everything it needs (the invariant definition, the verdict, its observed values) from them
 * rather than accepting any of that as part of the request.
 */
data class ReportTestSpecMisjudgment(
    val targetSystemId: String,
    val specificationId: UUID,
    val runId: UUID,
    val trialNumber: Int,
    val invariantId: String,
    val reason: String,
    val requestedModelKey: String?,
)
