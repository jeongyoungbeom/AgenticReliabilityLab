package com.project.agenticreliabilitylab.targetcredential.application

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local, expiring Target credential storage for the UI pilot.
 *
 * Values are never serialized, logged or returned. A process restart, explicit clear or TTL expiry removes them.
 */
@Component
class RuntimeTargetCredentialStore(
    private val clock: Clock,
) {
    private val credentials = ConcurrentHashMap<CredentialKey, StoredCredential>()

    fun save(
        targetSystemId: String,
        credentialSessionId: String?,
        values: Map<TargetCredentialRole, String>,
    ): TargetRuntimeCredentialStatus {
        require(values.isNotEmpty()) { "At least one Target credential is required" }
        val sessionId = credentialSessionId?.let(::requireSafeSessionId) ?: UUID.randomUUID().toString()
        val expiresAt = clock.instant().plus(CREDENTIAL_TTL)
        values.forEach { (role, rawValue) ->
            val value = rawValue.trim()
            require(value.isNotEmpty() && value.length <= MAX_CREDENTIAL_LENGTH) {
                "Target credential '${role.profileName}' must contain 1 to $MAX_CREDENTIAL_LENGTH characters"
            }
            require(value.none { character -> character == '\r' || character == '\n' }) {
                "Target credential '${role.profileName}' contains an invalid line break"
            }
            credentials[CredentialKey(targetSystemId, sessionId, role.profileName)] = StoredCredential(value, expiresAt)
        }
        removeExpired()
        return status(targetSystemId, sessionId)
    }

    fun headersFor(targetSystemId: String, credentialSessionId: String?, authProfile: String): Map<String, String>? {
        val sessionId = credentialSessionId ?: return null
        val key = CredentialKey(targetSystemId, requireSafeSessionId(sessionId), authProfile)
        return credentials[key]
            ?.takeIf { stored -> stored.isActive(key) }
            ?.toHeaders(authProfile)
    }

    private fun StoredCredential.isActive(key: CredentialKey): Boolean {
        if (expiresAt.isAfter(clock.instant())) return true
        credentials.remove(key, this)
        return false
    }

    private fun StoredCredential.toHeaders(authProfile: String): Map<String, String> =
        when (authProfile) {
            TargetCredentialRole.HARNESS.profileName -> mapOf(HARNESS_HEADER to value)
            else -> mapOf(AUTHORIZATION_HEADER to value.asBearerToken())
        }

    fun status(targetSystemId: String, credentialSessionId: String?): TargetRuntimeCredentialStatus {
        removeExpired()
        val sessionId = credentialSessionId?.let(::requireSafeSessionId)
        val active = sessionId?.let { current ->
            credentials.filterKeys { key -> key.targetSystemId == targetSystemId && key.credentialSessionId == current }
        }.orEmpty()
        return TargetRuntimeCredentialStatus(
            targetSystemId = targetSystemId,
            credentialSessionId = sessionId.orEmpty(),
            storedRoles = active.keys.mapTo(linkedSetOf()) { key -> key.authProfile },
            expiresAt = active.values.minOfOrNull(StoredCredential::expiresAt),
        )
    }

    fun clear(targetSystemId: String, credentialSessionId: String?): TargetRuntimeCredentialStatus {
        val sessionId = credentialSessionId?.let(::requireSafeSessionId)
            ?: throw IllegalArgumentException("Target credential session is required to clear runtime credentials")
        credentials.keys.removeIf { key ->
            key.targetSystemId == targetSystemId && key.credentialSessionId == sessionId
        }
        return status(targetSystemId, sessionId)
    }

    private fun removeExpired() {
        val now = clock.instant()
        credentials.entries.removeIf { (_, stored) -> !stored.expiresAt.isAfter(now) }
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
    private data class StoredCredential(val value: String, val expiresAt: Instant)

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val HARNESS_HEADER = "X-ARL-Harness-Key"
        const val BEARER_PREFIX = "Bearer "
        const val MAX_CREDENTIAL_LENGTH = 8_192
        val SESSION_ID_PATTERN = Regex("[A-Za-z0-9-]{16,64}")
        val CREDENTIAL_TTL: Duration = Duration.ofMinutes(30)
    }
}
