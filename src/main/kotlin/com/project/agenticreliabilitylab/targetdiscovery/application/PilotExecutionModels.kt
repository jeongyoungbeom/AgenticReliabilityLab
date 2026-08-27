package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.testspec.application.TestSpecRunView
import java.util.UUID

/** User-selected, deterministic pilot templates are executed serially for one Target. */
data class ExecutePilotTemplates(
    val targetSystemId: String,
    val candidateIds: List<String>,
    val confirmation: String,
    val idempotencyKey: String,
    val credentialSessionId: String?,
)

data class PilotTemplateExecutionOutcome(
    val candidateId: String,
    val specificationId: UUID?,
    val run: TestSpecRunView?,
    val failureCode: String?,
    val failureMessage: String?,
)
