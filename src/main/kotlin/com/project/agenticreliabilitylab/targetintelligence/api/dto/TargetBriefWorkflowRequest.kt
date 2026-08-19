package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.application.TargetBriefWorkflow
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Collections stay nullable so an absent JSON field never depends on Kotlin default-value binding. */
data class TargetBriefWorkflowRequest(
    @field:NotBlank
    @field:Size(max = MAX_TITLE_CHARACTERS)
    val title: String,
    @field:Size(max = MAX_STEPS)
    val steps: List<@NotBlank @Size(max = MAX_STEP_CHARACTERS) String>? = null,
) {
    fun toInput(): TargetBriefWorkflow = TargetBriefWorkflow(title = title, steps = steps.orEmpty())

    private companion object {
        const val MAX_TITLE_CHARACTERS = 200
        const val MAX_STEP_CHARACTERS = 500
        const val MAX_STEPS = 20
    }
}
