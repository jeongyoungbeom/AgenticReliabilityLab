package com.project.agenticreliabilitylab.testspec.domain

import java.time.Duration

/**
 * How this Target injects and releases a fault.
 *
 * Profile data, the same way [ResetPlan] is: a human declares both hooks and the ceiling once, and a
 * specification can only ever name a fault type, a scope and a TTL within that ceiling - never a URL of its own.
 */
data class FaultInjectionPlan(
    val injectHook: SpecHttpCall,
    val releaseHook: SpecHttpCall,
    val maxTtl: Duration,
)

/** What one inject or release attempt did. [faultId] is set whenever the Target told us which fault it means. */
data class FaultInjectionOutcome(
    val faultId: String?,
    val succeeded: Boolean,
    val failure: String? = null,
    /** Optional Target-reported location at which the fault was applied. */
    val injectionPoint: String? = null,
)
