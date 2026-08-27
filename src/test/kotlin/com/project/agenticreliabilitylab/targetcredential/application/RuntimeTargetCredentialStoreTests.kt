package com.project.agenticreliabilitylab.targetcredential.application

import com.project.agenticreliabilitylab.targetcredential.infrastructure.TargetCredentialProperties
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RuntimeTargetCredentialStoreTests {
    @Test
    fun `keeps credentials until explicit clear and never returns values in status`() {
        val store = store()

        val status = store.save(
            "sideproject-local",
            null,
            mapOf(TargetCredentialRole.SELLER to "seller-token", TargetCredentialRole.HARNESS to "harness-key"),
        )

        assertEquals(setOf("seller", "harness"), status.storedRoles)
        assertEquals(
            mapOf("Authorization" to "Bearer seller-token"),
            store.headersFor("sideproject-local", status.credentialSessionId, "seller"),
        )
        assertEquals(
            mapOf("X-ARL-Harness-Key" to "harness-key"),
            store.headersFor("sideproject-local", status.credentialSessionId, "harness"),
        )

        store.clear("sideproject-local", status.credentialSessionId)
        assertNull(store.headersFor("sideproject-local", status.credentialSessionId, "seller"))
        assertEquals(emptySet(), store.status("sideproject-local", status.credentialSessionId).storedRoles)
    }

    @Test
    fun `does not let one browser credential session use another sessions Target token`() {
        val store = store()
        val first = store.save("sideproject-local", null, mapOf(TargetCredentialRole.SELLER to "first-token"))
        val second = store.save("sideproject-local", null, mapOf(TargetCredentialRole.SELLER to "second-token"))

        assertEquals(
            mapOf("Authorization" to "Bearer first-token"),
            store.headersFor("sideproject-local", first.credentialSessionId, "seller"),
        )
        assertEquals(
            mapOf("Authorization" to "Bearer second-token"),
            store.headersFor("sideproject-local", second.credentialSessionId, "seller"),
        )
        assertNull(store.headersFor("sideproject-local", "00000000-0000-0000-0000-000000000000", "seller"))
    }

    @Test
    fun `refuses a session id the caller invented and mints its own instead`() {
        val store = store()
        val chosenByCaller = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        val saved = store.save("sideproject-local", chosenByCaller, mapOf(TargetCredentialRole.SELLER to "token"))

        // Otherwise anything able to set the cookie could choose the key the operator's credentials are filed under.
        assertNotEquals(chosenByCaller, saved.credentialSessionId)
        assertNull(store.headersFor("sideproject-local", chosenByCaller, "seller"))
        assertEquals(
            mapOf("Authorization" to "Bearer token"),
            store.headersFor("sideproject-local", saved.credentialSessionId, "seller"),
        )
    }

    @Test
    fun `an idle session is reclaimed but work keeps its own session alive`() {
        val clock = MutableClock(Instant.parse("2026-08-27T00:00:00Z"))
        val store = store(clock)
        val abandoned = store.save("target-a", null, mapOf(TargetCredentialRole.SELLER to "a-token"))
        val working = store.save("target-b", null, mapOf(TargetCredentialRole.SELLER to "b-token"))

        // Six hours of use keeps the working session alive past the eight-hour idle window; the other is untouched.
        repeat(3) {
            clock.advance(Duration.ofHours(2))
            assertEquals(
                mapOf("Authorization" to "Bearer b-token"),
                store.headersFor("target-b", working.credentialSessionId, "seller"),
            )
        }
        clock.advance(Duration.ofHours(3))

        assertNull(store.headersFor("target-a", abandoned.credentialSessionId, "seller"))
        assertEquals(
            mapOf("Authorization" to "Bearer b-token"),
            store.headersFor("target-b", working.credentialSessionId, "seller"),
        )
    }

    @Test
    fun `a caller without a cookie cannot grow the store past the session limit`() {
        val clock = MutableClock(Instant.parse("2026-08-27T00:00:00Z"))
        val store = store(clock, maxSessions = 3)
        val sessions = (1..5).map { index ->
            clock.advance(Duration.ofSeconds(1))
            store.save("sideproject-local", null, mapOf(TargetCredentialRole.SELLER to "token-$index"))
                .credentialSessionId
        }

        assertNull(store.headersFor("sideproject-local", sessions[0], "seller"))
        assertNull(store.headersFor("sideproject-local", sessions[1], "seller"))
        assertEquals(
            mapOf("Authorization" to "Bearer token-5"),
            store.headersFor("sideproject-local", sessions[4], "seller"),
        )
    }

    private fun store(clock: Clock = Clock.systemUTC(), maxSessions: Int = 100) = RuntimeTargetCredentialStore(
        TargetCredentialSessionRegistry(
            clock,
            TargetCredentialProperties().apply {
                idleTimeout = Duration.ofHours(8)
                this.maxSessions = maxSessions
            },
        ),
    )

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
