package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileReadTimingDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.testspec.application.port.ActiveTestSpecExecutionProfile
import com.project.agenticreliabilitylab.testspec.domain.ReadTiming
import com.project.agenticreliabilitylab.testspec.domain.ResetPlan
import com.project.agenticreliabilitylab.testspec.domain.ResetVerification
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import org.springframework.stereotype.Component

/** Converts a validated immutable Profile Version into the Runner's executable authority. */
@Component
class TestSpecExecutionProfileMapper {
    fun map(version: TargetProfileVersion): ActiveTestSpecExecutionProfile {
        val definition = version.definition
        require(version.targetSystemId == definition.target.id) {
            "Target Profile Version '${version.id}' does not match its Target registration"
        }
        val profile = requireNotNull(definition.testSpecExecution) {
            "Target '${version.targetSystemId}' does not declare test specification execution"
        }
        require(profile.executionEnabled) {
            "Test specification execution is disabled for Target '${version.targetSystemId}'"
        }
        val targetLimits = definition.experiment?.stockConcurrency
        val capabilities = TargetSpecCapabilities(
            targetSystemId = version.targetSystemId,
            environment = definition.target.environment.name,
            allowedCalls = profile.allowedCalls.mapTo(linkedSetOf()) { call -> call.key() },
            authProfiles = profile.authProfiles,
            authProfilesByCall = profile.allowedCalls.associate { call -> call.key() to call.authProfile },
            observationSources = profile.observationSources.associate { source -> source.name to source.toDomain() },
            supportedFaults = profile.supportedFaults,
            infrastructureTargets = profile.infrastructureTargets,
            maxConcurrency = minOf(profile.maxConcurrency, targetLimits?.maxConcurrency ?: Int.MAX_VALUE),
            maxRequestCount = minOf(profile.maxRequestCount, targetLimits?.maxRequestCount ?: Int.MAX_VALUE),
            maxTrials = profile.maxTrials,
            stateChangingAllowed = profile.stateChangingAllowed &&
                definition.target.environment in WRITABLE_ENVIRONMENTS,
        )
        return ActiveTestSpecExecutionProfile(
            profileVersionId = version.id,
            capabilities = capabilities,
            resetPlan = profile.reset?.toDomain() ?: ResetPlan.NOT_REQUIRED,
        )
    }

    private fun ProfileResetDefinition.toDomain() = ResetPlan(
        method = method,
        hook = hook?.toDomain(),
        expectedDuration = expectedDuration,
        verifications = verifications.map { verification -> verification.toDomain() },
    )

    private fun ProfileResetVerificationDefinition.toDomain() = ResetVerification(
        id = id,
        call = call.toDomain(),
        expression = expression,
        condition = condition,
        readTiming = readTiming.toDomain(),
    )

    private fun ProfileReadTimingDefinition.toDomain() = ReadTiming(
        rule = rule,
        maxWait = maxWait,
        interval = interval,
        evidence = null,
    )

    private fun ProfileHttpCallDefinition.toDomain() = SpecHttpCall(
        method = method,
        path = path,
        authProfile = authProfile,
        headers = emptyMap(),
        bodyJson = null,
    )

    private fun ProfileObservationSourceDefinition.toDomain() = DeclaredObservationSource(
        name = name,
        kind = DeclaredObservationSourceKind.valueOf(kind.name),
        endpoint = endpoint,
        fields = fields,
        queries = queries,
        authProfile = authProfile,
    )

    private fun ProfileHttpCallDefinition.key(): String = "${method.uppercase()} $path"

    private companion object {
        val WRITABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
    }
}
