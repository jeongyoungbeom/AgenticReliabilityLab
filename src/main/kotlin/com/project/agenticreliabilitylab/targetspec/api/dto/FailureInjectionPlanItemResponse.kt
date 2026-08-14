package com.project.agenticreliabilitylab.targetspec.api.dto

import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionRisk
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionType

data class FailureInjectionPlanItemResponse(
    val sequenceNumber: Int,
    val candidateId: String,
    val type: FailureInjectionType,
    val risk: FailureInjectionRisk,
    val title: String,
    val recoveryExpectation: String,
)
