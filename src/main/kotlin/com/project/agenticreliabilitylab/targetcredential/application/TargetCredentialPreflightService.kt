package com.project.agenticreliabilitylab.targetcredential.application

import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.target.domain.TargetReadTransportException
import com.project.agenticreliabilitylab.target.domain.TargetReadResponse
import com.project.agenticreliabilitylab.targetprofile.application.TargetProfileService
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.toRegisteredTarget
import com.project.agenticreliabilitylab.testspec.application.port.SpecAuthProvider
import com.project.agenticreliabilitylab.testspec.application.port.SpecAuthUnavailableException
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Calls only Profile-owned safe GET checks and reports credential failure separately from Target failure. */
@Service
class TargetCredentialPreflightService(
    private val profiles: TargetProfileService,
    private val transport: TargetReadTransport,
    private val authProvider: SpecAuthProvider,
) {
    fun preflight(
        targetSystemId: String,
        credentialSessionId: String? = null,
    ): List<TargetCredentialPreflightResult> {
        val profile = requireNotNull(profiles.findActive(targetSystemId)) {
            "Target '$targetSystemId' has no active Profile"
        }
        return TargetCredentialRole.entries.map { role -> check(profile, role, credentialSessionId) }
    }

    private fun check(
        profile: TargetProfileVersion,
        role: TargetCredentialRole,
        credentialSessionId: String?,
    ): TargetCredentialPreflightResult {
        val call = profile.preflightCall(role)
        val headers = call?.let { credentialHeaders(profile, role, credentialSessionId) }
        return when {
            call == null -> result(role, TargetCredentialPreflightStatus.PREFLIGHT_NOT_CONFIGURED)
            headers == null -> result(role, TargetCredentialPreflightStatus.TARGET_CREDENTIAL_MISSING, call)
            else -> preflightCall(profile, role, call, headers)
        }
    }

    private fun credentialHeaders(
        profile: TargetProfileVersion,
        role: TargetCredentialRole,
        credentialSessionId: String?,
    ): Map<String, String>? = try {
        authProvider.headersFor(profile.targetSystemId, role.profileName, credentialSessionId)
    } catch (_: SpecAuthUnavailableException) {
        null
    }

    private fun preflightCall(
        profile: TargetProfileVersion,
        role: TargetCredentialRole,
        call: ProfileHttpCallDefinition,
        headers: Map<String, String>,
    ): TargetCredentialPreflightResult {
        val response = sendPreflightRequest(profile, role, call, headers)
        return response?.let { targetResponse ->
            result(role, targetResponse.preflightStatus(), call, targetResponse.statusCode)
        } ?: result(role, TargetCredentialPreflightStatus.TARGET_UNREACHABLE, call)
    }

    private fun sendPreflightRequest(
        profile: TargetProfileVersion,
        role: TargetCredentialRole,
        call: ProfileHttpCallDefinition,
        headers: Map<String, String>,
    ): TargetReadResponse? = try {
        val target = profile.definition.target.toRegisteredTarget(Instant.EPOCH, Instant.EPOCH)
        val uri = target.baseUri.resolve(call.path)
        transport.send(
            target = target,
            uri = uri,
            method = "GET",
            headers = requestHeaders(role, headers),
            body = ByteArray(0),
            timeout = profile.definition.genericHttp?.requestTimeout ?: DEFAULT_TIMEOUT,
        )
    } catch (_: TargetReadTransportException) {
        null
    }

    private fun requestHeaders(
        role: TargetCredentialRole,
        headers: Map<String, String>,
    ): Map<String, String> = buildMap {
        put("Accept", "application/json")
        putAll(headers)
        if (role == TargetCredentialRole.HARNESS) put(RUN_ID_HEADER, UUID.randomUUID().toString())
    }

    private fun TargetReadResponse.preflightStatus(): TargetCredentialPreflightStatus =
        when (statusCode) {
            in SUCCESS_STATUS -> TargetCredentialPreflightStatus.READY
            in CREDENTIAL_FAILURE_STATUS -> TargetCredentialPreflightStatus.TARGET_CREDENTIAL_EXPIRED
            else -> TargetCredentialPreflightStatus.TARGET_PREFLIGHT_FAILED
        }

    private fun TargetProfileVersion.preflightCall(role: TargetCredentialRole): ProfileHttpCallDefinition? =
        definition.testSpecExecution
            ?.takeIf { execution -> role.profileName in execution.authProfiles }
            ?.let { execution ->
                when (role) {
                    TargetCredentialRole.HARNESS -> execution.observationSources.firstOrNull { source ->
                        source.kind == ProfileObservationSourceKind.HARNESS_STATE &&
                            source.authProfile == role.profileName
                    }?.let { source -> ProfileHttpCallDefinition("GET", source.endpoint, role.profileName) }

                    else -> execution.allowedCalls.firstOrNull { call ->
                        call.method.uppercase() == "GET" && call.authProfile == role.profileName
                    }
                }
            }

    private fun result(
        role: TargetCredentialRole,
        status: TargetCredentialPreflightStatus,
        call: ProfileHttpCallDefinition? = null,
        httpStatus: Int? = null,
    ) = TargetCredentialPreflightResult(
        role = role.profileName,
        status = status,
        method = call?.method,
        path = call?.path,
        httpStatus = httpStatus,
    )

    private companion object {
        const val RUN_ID_HEADER = "X-ARL-Run-Id"
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val SUCCESS_STATUS = 200..299
        val CREDENTIAL_FAILURE_STATUS = setOf(401, 403)
    }
}
