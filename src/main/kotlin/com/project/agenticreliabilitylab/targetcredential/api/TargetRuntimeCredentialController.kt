package com.project.agenticreliabilitylab.targetcredential.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetcredential.api.dto.EndTargetCredentialSessionResponse
import com.project.agenticreliabilitylab.targetcredential.api.dto.SaveTargetRuntimeCredentialsRequest
import com.project.agenticreliabilitylab.targetcredential.api.dto.TargetRuntimeCredentialResponse
import com.project.agenticreliabilitylab.targetcredential.application.RuntimeTargetCredentialStore
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightResult
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
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
    private val sessionCookie: TargetCredentialSessionCookie,
) {
    @PutMapping("/api/targets/{targetSystemId}/runtime-credentials")
    fun save(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @CookieValue(TargetCredentialSessionCookie.NAME, required = false) credentialSessionId: String?,
        @RequestBody request: SaveTargetRuntimeCredentialsRequest,
    ): ResponseEntity<TargetRuntimeCredentialResponse> {
        access.requireExecutor(authorization)
        val status = credentials.save(targetSystemId, credentialSessionId, request.values())
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie.issue(status.credentialSessionId).toString())
            .body(TargetRuntimeCredentialResponse.from(status))
    }

    /** Lets a reloaded page discover that its cookie still points at a live credential session. */
    @GetMapping("/api/targets/{targetSystemId}/runtime-credentials")
    fun status(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @CookieValue(TargetCredentialSessionCookie.NAME, required = false) credentialSessionId: String?,
    ): TargetRuntimeCredentialResponse {
        access.requireExecutor(authorization)
        return TargetRuntimeCredentialResponse.from(credentials.status(targetSystemId, credentialSessionId))
    }

    @PostMapping("/api/targets/{targetSystemId}/runtime-credentials/preflight")
    fun preflight(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @CookieValue(TargetCredentialSessionCookie.NAME, required = false) credentialSessionId: String?,
    ): List<TargetCredentialPreflightResult> {
        access.requireExecutor(authorization)
        return preflight.preflight(targetSystemId, credentialSessionId)
    }

    /**
     * Clears one Target only, and deliberately leaves the cookie alone.
     *
     * One cookie covers every Target this browser touched, so expiring it here would strand the other Targets'
     * tokens in memory with no way to read or remove them.
     */
    @DeleteMapping("/api/targets/{targetSystemId}/runtime-credentials")
    fun clear(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @CookieValue(TargetCredentialSessionCookie.NAME, required = false) credentialSessionId: String?,
    ): TargetRuntimeCredentialResponse {
        access.requireExecutor(authorization)
        return TargetRuntimeCredentialResponse.from(credentials.clear(targetSystemId, credentialSessionId))
    }

    /** Ends the browser's credential session: every Target it held goes, and the cookie expires with it. */
    @DeleteMapping("/api/target-credential-session")
    fun endSession(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @CookieValue(TargetCredentialSessionCookie.NAME, required = false) credentialSessionId: String?,
    ): ResponseEntity<EndTargetCredentialSessionResponse> {
        access.requireExecutor(authorization)
        val clearedTargetCount = credentials.clearSession(credentialSessionId)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie.expire().toString())
            .body(EndTargetCredentialSessionResponse(clearedTargetCount))
    }
}
