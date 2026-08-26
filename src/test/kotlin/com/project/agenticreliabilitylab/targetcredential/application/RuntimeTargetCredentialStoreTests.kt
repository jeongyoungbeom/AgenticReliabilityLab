package com.project.agenticreliabilitylab.targetcredential.application

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeTargetCredentialStoreTests {
    @Test
    fun `keeps credentials only until the runtime TTL and never returns values in status`() {
        val clock = MutableClock(Instant.parse("2026-08-26T00:00:00Z"))
        val store = RuntimeTargetCredentialStore(clock)

        val status = store.save(
            "sideproject-local",
            null,
            mapOf(
                TargetCredentialRole.SELLER to "seller-token",
                TargetCredentialRole.HARNESS to "harness-key",
            ),
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

        clock.advance(Duration.ofMinutes(31))

        assertNull(store.headersFor("sideproject-local", status.credentialSessionId, "seller"))
        assertEquals(emptySet(), store.status("sideproject-local", status.credentialSessionId).storedRoles)
    }

    @Test
    fun `does not let one browser credential session use another sessions Target token`() {
        val store = RuntimeTargetCredentialStore(Clock.systemUTC())
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

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
