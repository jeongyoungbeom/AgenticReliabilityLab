package com.project.agenticreliabilitylab.targetcredential.api

import com.project.agenticreliabilitylab.access.OperatorAccessService
import com.project.agenticreliabilitylab.targetcredential.api.dto.SaveTargetRuntimeCredentialsRequest
import com.project.agenticreliabilitylab.targetcredential.application.RuntimeTargetCredentialStore
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightService
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialSessionRegistry
import com.project.agenticreliabilitylab.targetcredential.infrastructure.TargetCredentialProperties
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import java.time.Clock
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetRuntimeCredentialControllerTests {
    private val properties = TargetCredentialProperties()
    private val store = RuntimeTargetCredentialStore(TargetCredentialSessionRegistry(Clock.systemUTC(), properties))
    private val access = Mockito.mock(OperatorAccessService::class.java)
    private val preflight = Mockito.mock(TargetCredentialPreflightService::class.java)
    private val controller = TargetRuntimeCredentialController(
        store,
        preflight,
        access,
        TargetCredentialSessionCookie(properties),
    )

    @Test
    fun `hands the credential session to the browser only as an HttpOnly cookie`() {
        allowExecutor()

        val saved = controller.save("sideproject-local", null, null, request("seller-token"))

        val cookie = saved.headers.getFirst(HttpHeaders.SET_COOKIE).orEmpty()
        assertTrue(cookie.startsWith("${TargetCredentialSessionCookie.NAME}="), "cookie: $cookie")
        assertContains(cookie, "HttpOnly")
        assertContains(cookie, "SameSite=Strict")
        assertContains(cookie, "Path=/api")
        assertEquals(setOf("seller"), saved.body?.storedRoles)
        assertTrue(saved.body?.sessionActive == true)
        // The body must not leak the routing key back into page scripts.
        assertFalse(saved.body.toString().contains(sessionIdFrom(cookie)))
    }

    @Test
    fun `a reloaded page recovers its live session from the cookie instead of stranding the tokens`() {
        allowExecutor()
        val session = saveAndReadSession("sideproject-local", "seller-token")

        val afterReload = controller.status("sideproject-local", null, session)

        assertTrue(afterReload.sessionActive)
        assertEquals(setOf("seller"), afterReload.storedRoles)
        assertEquals(
            mapOf("Authorization" to "Bearer seller-token"),
            store.headersFor("sideproject-local", session, "seller"),
        )
    }

    @Test
    fun `clearing one Target leaves the other Targets of the same browser session reachable`() {
        allowExecutor()
        val session = saveAndReadSession("target-a", "a-token")
        controller.save("target-b", null, session, request("b-token"))

        val cleared = controller.clear("target-a", null, session)

        assertNull(store.headersFor("target-a", session, "seller"))
        assertFalse(cleared.sessionActive)
        // One cookie covers every Target, so clearing one must not expire it and strand the rest.
        assertEquals(mapOf("Authorization" to "Bearer b-token"), store.headersFor("target-b", session, "seller"))
        assertTrue(controller.status("target-b", null, session).sessionActive)
    }

    @Test
    fun `ending the session removes every Target it held and expires the cookie`() {
        allowExecutor()
        val session = saveAndReadSession("target-a", "a-token")
        controller.save("target-b", null, session, request("b-token"))

        val ended = controller.endSession(null, session)

        assertEquals(2, ended.body?.clearedTargetCount)
        assertContains(ended.headers.getFirst(HttpHeaders.SET_COOKIE).orEmpty(), "Max-Age=0")
        assertNull(store.headersFor("target-a", session, "seller"))
        assertNull(store.headersFor("target-b", session, "seller"))
    }

    @Test
    fun `a browser that lost its cookie can still tidy up instead of getting an error`() {
        allowExecutor()

        assertEquals(0, controller.endSession(null, null).body?.clearedTargetCount)
        assertFalse(controller.clear("sideproject-local", null, null).sessionActive)
    }

    @Test
    fun `a request without the cookie cannot reach another browsers session`() {
        allowExecutor()
        controller.save("sideproject-local", null, null, request("seller-token"))

        val anonymous = controller.status("sideproject-local", null, null)

        assertFalse(anonymous.sessionActive)
        assertEquals(emptySet(), anonymous.storedRoles)
    }

    private fun allowExecutor() {
        Mockito.`when`(access.requireExecutor(null)).thenReturn("operator")
    }

    private fun saveAndReadSession(targetSystemId: String, sellerToken: String): String = sessionIdFrom(
        controller.save(targetSystemId, null, null, request(sellerToken))
            .headers.getFirst(HttpHeaders.SET_COOKIE),
    )

    private fun request(sellerToken: String) = SaveTargetRuntimeCredentialsRequest(seller = sellerToken)

    private fun sessionIdFrom(cookie: String?): String =
        requireNotNull(cookie).substringAfter('=').substringBefore(';')
}
