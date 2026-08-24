package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.application.ReportTestSpecMisjudgment
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class ReportTestSpecMisjudgmentRequest(
    @field:NotBlank
    @field:Size(max = MAX_UUID_CHARACTERS)
    val specificationId: String,
    @field:NotBlank
    @field:Size(max = MAX_UUID_CHARACTERS)
    val runId: String,
    @field:Min(1)
    val trialNumber: Int,
    @field:NotBlank
    @field:Size(max = MAX_INVARIANT_ID_CHARACTERS)
    val invariantId: String,
    @field:NotBlank
    @field:Size(max = MAX_REASON_CHARACTERS)
    val reason: String,
    val modelKey: String? = null,
) {
    fun toCommand(targetSystemId: String): ReportTestSpecMisjudgment = ReportTestSpecMisjudgment(
        targetSystemId = targetSystemId,
        specificationId = UUID.fromString(specificationId),
        runId = UUID.fromString(runId),
        trialNumber = trialNumber,
        invariantId = invariantId,
        reason = reason,
        requestedModelKey = modelKey,
    )

    private companion object {
        const val MAX_UUID_CHARACTERS = 36
        const val MAX_INVARIANT_ID_CHARACTERS = 200
        const val MAX_REASON_CHARACTERS = 2_000
    }
}
