package com.project.agenticreliabilitylab.targetdiscovery.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetdiscovery.application.PilotDiscovery
import com.project.agenticreliabilitylab.targetdiscovery.application.PilotDiscoveryService
import com.project.agenticreliabilitylab.targetcredential.api.TargetCredentialSessionCookie
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class PilotDiscoveryController(
    private val service: PilotDiscoveryService,
    private val access: OperatorAccessService,
) {
    @GetMapping("/api/targets/{targetSystemId}/pilot-discovery")
    fun find(
        @PathVariable targetSystemId: String,
        @RequestHeader("Authorization", required = false) authorization: String?,
        @CookieValue(TargetCredentialSessionCookie.NAME, required = false) credentialSessionId: String?,
    ): PilotDiscovery {
        access.requireViewer(authorization)
        return service.find(targetSystemId, credentialSessionId)
    }
}
