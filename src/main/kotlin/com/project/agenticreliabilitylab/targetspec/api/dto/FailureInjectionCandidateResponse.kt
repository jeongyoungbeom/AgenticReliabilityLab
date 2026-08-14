package com.project.agenticreliabilitylab.targetspec.api.dto

import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionRisk
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionType

data class FailureInjectionCandidateResponse(
    val id: String,
    val type: FailureInjectionType,
    val risk: FailureInjectionRisk,
    val title: String,
    val description: String,
    val recoveryExpectation: String,
) {
    companion object {
        fun from(value: FailureInjectionCandidate) = FailureInjectionCandidateResponse(
            value.id, value.type, value.risk, value.title, value.description, value.recoveryExpectation,
        )
    }
}
