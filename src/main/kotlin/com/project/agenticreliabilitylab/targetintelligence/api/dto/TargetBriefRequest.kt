package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.application.TargetBriefInput
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Structured facts the user declares directly; these become STATED knowledge.
 *
 * Collections stay nullable so an absent JSON field never depends on Kotlin default-value binding.
 */
data class TargetBriefRequest(
    @field:Size(max = MAX_ITEMS)
    val domainTerms: List<@NotBlank @Size(max = MAX_TERM_CHARACTERS) String>? = null,
    @field:Valid
    @field:Size(max = MAX_ITEMS)
    val workflows: List<TargetBriefWorkflowRequest>? = null,
    @field:Size(max = MAX_ITEMS)
    val invariants: List<@NotBlank @Size(max = MAX_STATEMENT_CHARACTERS) String>? = null,
    @field:Size(max = MAX_ITEMS)
    val components: List<@NotBlank @Size(max = MAX_TERM_CHARACTERS) String>? = null,
) {
    fun toInput(): TargetBriefInput = TargetBriefInput(
        domainTerms = domainTerms.orEmpty(),
        workflows = workflows.orEmpty().map(TargetBriefWorkflowRequest::toInput),
        invariants = invariants.orEmpty(),
        components = components.orEmpty(),
    )

    private companion object {
        const val MAX_ITEMS = 50
        const val MAX_TERM_CHARACTERS = 200
        const val MAX_STATEMENT_CHARACTERS = 500
    }
}
