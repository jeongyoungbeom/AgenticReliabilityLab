package com.project.agenticreliabilitylab.targetcredential.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetcredential.api.dto.SaveTargetRuntimeCredentialsRequest
import com.project.agenticreliabilitylab.targetcredential.application.RuntimeTargetCredentialStore
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightResult
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightService
import com.project.agenticreliabilitylab.targetcredential.application.TargetRuntimeCredentialStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class TargetRuntimeCredentialController(
    private val credentials: RuntimeTargetCredentialStore,
    private val preflight: TargetCredentialPreflightService,
    private val access: OperatorAccessService,
) {
    @PutMapping("/api/targets/{targetSystemId}/runtime-credentials")
    fun save(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader(CREDENTIAL_SESSION_HEADER, required = false) credentialSessionId: String?,
        @RequestBody request: SaveTargetRuntimeCredentialsRequest,
    ): TargetRuntimeCredentialStatus {
        access.requireExecutor(authorization)
        return credentials.save(targetSystemId, credentialSessionId, request.values())
    }

    @PostMapping("/api/targets/{targetSystemId}/runtime-credentials/preflight")
    fun preflight(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader(CREDENTIAL_SESSION_HEADER, required = false) credentialSessionId: String?,
    ): List<TargetCredentialPreflightResult> {
        access.requireExecutor(authorization)
        return preflight.preflight(targetSystemId, credentialSessionId)
    }

    @DeleteMapping("/api/targets/{targetSystemId}/runtime-credentials")
    fun clear(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader(CREDENTIAL_SESSION_HEADER, required = false) credentialSessionId: String?,
    ): TargetRuntimeCredentialStatus {
        access.requireExecutor(authorization)
        return credentials.clear(targetSystemId, credentialSessionId)
    }

    private companion object {
        const val CREDENTIAL_SESSION_HEADER = "X-ARL-Target-Credential-Session"
    }
}
