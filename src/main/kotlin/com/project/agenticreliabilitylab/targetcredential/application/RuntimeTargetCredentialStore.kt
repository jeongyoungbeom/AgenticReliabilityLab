package com.project.agenticreliabilitylab.targetcredential.application

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local Target credential storage for the UI pilot.
 *
 * Values are never serialized, logged or returned. A process restart, an explicit clear, or an idle session past
 * the configured timeout removes them. Session liveness lives in [TargetCredentialSessionRegistry].
 */
@Component
class RuntimeTargetCredentialStore(
    private val sessions: TargetCredentialSessionRegistry,
) {
    private val credentials = ConcurrentHashMap<CredentialKey, StoredCredential>()

    fun save(
        targetSystemId: String,
        credentialSessionId: String?,
        values: Map<TargetCredentialRole, String>,
    ): TargetRuntimeCredentialStatus {
        require(values.isNotEmpty()) { "At least one Target credential is required" }
        dropSessions(sessions.reclaimable())
        val sessionId = resolveSessionId(credentialSessionId)
        values.forEach { (role, rawValue) ->
            val value = rawValue.trim()
            require(value.isNotEmpty() && value.length <= MAX_CREDENTIAL_LENGTH) {
                "Target credential '${role.profileName}' must contain 1 to $MAX_CREDENTIAL_LENGTH characters"
            }
            require(value.none { character -> character == '\r' || character == '\n' }) {
                "Target credential '${role.profileName}' contains an invalid line break"
            }
            credentials[CredentialKey(targetSystemId, sessionId, role.profileName)] = StoredCredential(value)
        }
        sessions.touch(sessionId)
        dropSessions(sessions.reclaimable())
        return status(targetSystemId, sessionId)
    }

    /**
     * Reuses a submitted session id only when the registry issued it and still holds it.
     *
     * Without this, anything able to set the cookie could choose the key the operator's Target credentials are
     * filed under.
     */
    private fun resolveSessionId(credentialSessionId: String?): String = credentialSessionId
        ?.let(::requireSafeSessionId)
        ?.takeIf(sessions::isKnown)
        ?: UUID.randomUUID().toString()

    fun headersFor(targetSystemId: String, credentialSessionId: String?, authProfile: String): Map<String, String>? {
        val sessionId = credentialSessionId ?: return null
        dropSessions(sessions.reclaimable())
        val key = CredentialKey(targetSystemId, requireSafeSessionId(sessionId), authProfile)
        return credentials[key]?.also { sessions.touch(sessionId) }?.toHeaders(authProfile)
    }

    private fun StoredCredential.toHeaders(authProfile: String): Map<String, String> =
        when (authProfile) {
            TargetCredentialRole.HARNESS.profileName -> mapOf(HARNESS_HEADER to value)
            else -> mapOf(AUTHORIZATION_HEADER to value.asBearerToken())
        }

    fun status(targetSystemId: String, credentialSessionId: String?): TargetRuntimeCredentialStatus {
        dropSessions(sessions.reclaimable())
        val sessionId = credentialSessionId?.let(::requireSafeSessionId)
        val active = sessionId?.let { current ->
            credentials.filterKeys { key -> key.targetSystemId == targetSystemId && key.credentialSessionId == current }
        }.orEmpty()
        if (active.isNotEmpty()) sessions.touch(requireNotNull(sessionId))
        return TargetRuntimeCredentialStatus(
            targetSystemId = targetSystemId,
            credentialSessionId = sessionId.orEmpty(),
            storedRoles = active.keys.mapTo(linkedSetOf()) { key -> key.authProfile },
        )
    }

    /**
     * Clears one Target and deliberately keeps the session alive.
     *
     * One session covers every Target the browser touched, so forgetting it here would strand the rest with no way
     * to read or remove them. [clearSession] is the operation that ends the session.
     */
    fun clear(targetSystemId: String, credentialSessionId: String?): TargetRuntimeCredentialStatus {
        val sessionId = credentialSessionId?.let(::requireSafeSessionId)
            ?: return status(targetSystemId, null)
        credentials.keys.removeIf { key ->
            key.targetSystemId == targetSystemId && key.credentialSessionId == sessionId
        }
        return status(targetSystemId, sessionId)
    }

    /** Ends the whole browser session: every Target it held goes, and the session id is forgotten. */
    fun clearSession(credentialSessionId: String?): Int {
        val sessionId = credentialSessionId?.let(::requireSafeSessionId) ?: return 0
        val cleared = credentials.keys.filter { key -> key.credentialSessionId == sessionId }
        dropSessions(setOf(sessionId))
        return cleared.map(CredentialKey::targetSystemId).distinct().size
    }

    private fun dropSessions(sessionIds: Set<String>) {
        if (sessionIds.isEmpty()) return
        credentials.keys.removeIf { key -> key.credentialSessionId in sessionIds }
        sessions.forget(sessionIds)
    }

    private fun String.asBearerToken(): String =
        if (startsWith(BEARER_PREFIX, ignoreCase = true)) this else "$BEARER_PREFIX$this"

    private fun requireSafeSessionId(value: String): String {
        require(SESSION_ID_PATTERN.matches(value)) { "Target credential session is invalid" }
        return value
    }

    private data class CredentialKey(
        val targetSystemId: String,
        val credentialSessionId: String,
        val authProfile: String,
    )
    private data class StoredCredential(val value: String)

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val HARNESS_HEADER = "X-ARL-Harness-Key"
        const val BEARER_PREFIX = "Bearer "
        const val MAX_CREDENTIAL_LENGTH = 8_192
        val SESSION_ID_PATTERN = Regex("[A-Za-z0-9-]{16,64}")
    }
}
