package com.project.agenticreliabilitylab.targetspec.application.model

import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanRecord

data class FailureInjectionPlanDetails(
    val plan: FailureInjectionPlanRecord,
    val items: List<FailureInjectionPlanItemRecord>,
)

data class CreateFailureInjectionPlan(
    val targetSystemId: String,
    val candidateIds: List<String>,
)
