package com.project.agenticreliabilitylab.targetcredential.api

import com.project.agenticreliabilitylab.targetcredential.application.port.TargetCredentialSettings
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The Target credential session id lives only in an HttpOnly cookie.
 *
 * It is an opaque routing key, never a Target credential. Keeping it out of JavaScript means a browser reload keeps
 * reaching the same server-side entry instead of stranding it until an ARL restart, and page scripts cannot read it.
 * It is a browser-session cookie on purpose: closing the browser ends the credential session for the user, and the
 * store's idle timeout reclaims what the server still holds.
 */
@Component
class TargetCredentialSessionCookie(
    private val settings: TargetCredentialSettings,
) {
    fun issue(credentialSessionId: String): ResponseCookie = base(credentialSessionId).build()

    fun expire(): ResponseCookie = base("").maxAge(Duration.ZERO).build()

    private fun base(value: String): ResponseCookie.ResponseCookieBuilder = ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(settings.cookieSecure)
        .sameSite("Strict")
        .path(PATH)

    companion object {
        const val NAME = "arl_target_credential_session"
        private const val PATH = "/api"
    }
}
