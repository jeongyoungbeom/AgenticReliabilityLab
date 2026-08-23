package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import java.time.Duration

enum class DeclaredObservationSourceKind {
    HARNESS_STATE,
    PROMETHEUS,
    TRACE,
}

data class DeclaredObservationSource(
    val name: String,
    val kind: DeclaredObservationSourceKind,
    val endpoint: String,
    val fields: Set<String>,
    val queries: Map<String, String>,
    val authProfile: String?,
)

/**
 * What the active Profile allows a specification to do.
 *
 * A specification is untrusted input, so nothing here is inferred from the specification itself. Every limit is
 * read from the Profile, and the effective limit is always the smaller of Profile and any Target-declared value:
 * a Target claiming a larger allowance can lower the ceiling, never raise it.
 */
data class TargetSpecCapabilities(
    val targetSystemId: String,
    val environment: String,
    /** Paths the Profile registers, as "METHOD /path". Nothing else may be called. */
    val allowedCalls: Set<String>,
    val authProfiles: Set<String>,
    /** Required auth profile for each registered call. A present null value explicitly means no auth. */
    val authProfilesByCall: Map<String, String?> = emptyMap(),
    /** Profile-owned executable observation source definitions, keyed by source name. */
    val observationSources: Map<String, DeclaredObservationSource>,
    val supportedFaults: Set<String>,
    val infrastructureTargets: Set<String>,
    /** The longest TTL a fault injection step may declare. Zero when the Profile declares no fault injection. */
    val maxFaultTtl: Duration,
    val maxConcurrency: Int,
    val maxRequestCount: Int,
    val maxTrials: Int,
    val stateChangingAllowed: Boolean,
) {
    fun matchingCall(call: SpecHttpCall): String? {
        val method = call.method.uppercase()
        val exact = "$method ${call.path}"
        if (exact in allowedCalls) return exact
        return allowedCalls.filter { registered ->
            val separator = registered.indexOf(' ')
            separator > 0 && registered.substring(0, separator) == method &&
                SpecRequestPolicy.registeredPathMatches(registered.substring(separator + 1), call.path)
        }.singleOrNull()
    }

    fun allows(call: SpecHttpCall): Boolean = matchingCall(call) != null

    fun providesField(sourceName: String, field: String): Boolean =
        observationSources[sourceName]?.fields?.contains(field) == true
}
