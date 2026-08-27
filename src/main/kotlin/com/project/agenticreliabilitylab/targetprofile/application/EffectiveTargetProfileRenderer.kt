package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.declaredOpenApiPaths
import org.springframework.stereotype.Component

/**
 * Renders what ARL actually applied for a Target.
 *
 * Quick registration fills in Swagger paths, Harness endpoints, network allowlists and execution limits that the user
 * never typed. Those defaults must be inspectable — a hidden default that decides whether a run is allowed is
 * indistinguishable from a bug when it goes wrong.
 */
@Component
class EffectiveTargetProfileRenderer(
    private val yamlRenderer: TargetProfileYamlRenderer,
) {
    fun render(version: TargetProfileVersion): EffectiveTargetProfile {
        val definition = version.definition
        val target = definition.target
        val execution = definition.testSpecExecution
        val harnessState = execution?.harnessStateSource()
        return EffectiveTargetProfile(
            targetSystemId = version.targetSystemId,
            targetName = target.name,
            environment = target.environment.name,
            baseUrl = target.baseUrl,
            allowedOrigin = target.allowedOrigin,
            allowedCidrs = target.allowedCidrs.toList(),
            healthPath = target.healthPath,
            openApiPaths = target.declaredOpenApiPaths(),
            harnessStatePath = harnessState?.endpoint,
            harnessStateFields = harnessState?.fields?.toList().orEmpty(),
            harnessResetPath = execution?.reset?.hook?.path,
            harnessFaultPath = execution?.faultInjection?.injectEndpoint?.path,
            harnessFaultReleasePath = execution?.faultInjection?.releaseEndpoint?.path,
            authProfiles = execution?.authProfiles?.toList().orEmpty(),
            supportedFaults = execution?.supportedFaults?.toList().orEmpty(),
            allowedCalls = execution?.allowedCalls.orEmpty().map(::describe),
            requestTimeout = definition.genericHttp?.requestTimeout?.toString(),
            maxConcurrency = execution?.maxConcurrency,
            maxRequestCount = execution?.maxRequestCount,
            maxTrials = execution?.maxTrials,
            generatedYaml = yamlRenderer.render(definition),
        )
    }

    /** Picked by kind: a profile may declare Prometheus or trace sources first, and position is not identity. */
    private fun TestSpecExecutionProfileDefinition.harnessStateSource(): ProfileObservationSourceDefinition? =
        observationSources.firstOrNull { source -> source.kind == ProfileObservationSourceKind.HARNESS_STATE }

    private fun describe(call: ProfileHttpCallDefinition): String = buildString {
        append(call.method).append(' ').append(call.path)
        call.authProfile?.let { profile -> append(" (").append(profile).append(')') }
    }
}
