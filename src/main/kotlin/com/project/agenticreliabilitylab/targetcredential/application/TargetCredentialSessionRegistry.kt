package com.project.agenticreliabilitylab.targetcredential.application

import com.project.agenticreliabilitylab.targetcredential.application.port.TargetCredentialSettings
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which Target credential sessions exist and when each was last used.
 *
 * Kept apart from [RuntimeTargetCredentialStore] because the two answer different questions: the store owns the
 * secret values, this owns session liveness. Knowing which ids were actually issued is also what lets the store
 * refuse a session id a caller invented for itself.
 */
@Component
class TargetCredentialSessionRegistry(
    private val clock: Clock,
    private val settings: TargetCredentialSettings,
) {
    private val lastUsed = ConcurrentHashMap<String, Instant>()

    /** True only for an id this registry issued and has not reclaimed. */
    fun isKnown(sessionId: String): Boolean = lastUsed.containsKey(sessionId)

    fun touch(sessionId: String) {
        lastUsed[sessionId] = clock.instant()
    }

    fun forget(sessionIds: Set<String>) {
        lastUsed.keys.removeAll(sessionIds)
    }

    /**
     * Sessions the store should drop: idle past the timeout, plus the least recently used beyond the cap.
     *
     * The timeout is measured from last use, not from when the credentials were saved, so work in progress never
     * expires. The cap bounds a caller that keeps saving without a cookie and mints a fresh session every time.
     */
    fun reclaimable(): Set<String> {
        val deadline = clock.instant().minus(settings.idleTimeout)
        val reclaimable = lastUsed.filterValues { seen -> seen.isBefore(deadline) }.keys.toMutableSet()
        val remaining = lastUsed.entries.filterNot { entry -> entry.key in reclaimable }
        val excess = remaining.size - settings.maxSessions
        if (excess > 0) {
            remaining.sortedBy { entry -> entry.value }.take(excess).forEach { entry -> reclaimable += entry.key }
        }
        return reclaimable
    }
}
