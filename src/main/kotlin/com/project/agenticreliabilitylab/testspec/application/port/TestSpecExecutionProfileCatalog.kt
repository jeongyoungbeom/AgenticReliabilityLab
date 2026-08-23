package com.project.agenticreliabilitylab.testspec.application.port

import com.project.agenticreliabilitylab.testspec.application.TargetSpecCapabilities
import com.project.agenticreliabilitylab.testspec.domain.FaultInjectionPlan
import com.project.agenticreliabilitylab.testspec.domain.ResetPlan
import java.util.UUID

data class ActiveTestSpecExecutionProfile(
    val profileVersionId: UUID,
    val capabilities: TargetSpecCapabilities,
    val resetPlan: ResetPlan,
    /** Null when the Profile declares no fault injection, in which case no INJECT_FAULT step can validate. */
    val faultInjectionPlan: FaultInjectionPlan? = null,
)

/** Resolves the immutable active Profile version used to validate and run one specification. */
interface TestSpecExecutionProfileCatalog {
    fun requireActive(targetSystemId: String): ActiveTestSpecExecutionProfile
}
