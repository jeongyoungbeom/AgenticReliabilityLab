package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.ReadTiming
import com.project.agenticreliabilitylab.testspec.domain.ResetPlan
import com.project.agenticreliabilitylab.testspec.domain.ResetVerification
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An unverified reset is the failure that damages the *next* run, so these tests are mostly about refusing to
 * claim success.
 */
class EnvironmentResetServiceTests {
    private val mapper = ObjectMapper()
    private val references = SpecReferenceResolver(mapper)
    private val evaluator = ResponsePathEvaluator(mapper)

    @Test
    fun `resets and confirms the environment came back to its baseline`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/harness/reset" -> jsonResponse(200, "{}")
                else -> jsonResponse(200, """{"orderCount":0}""")
            }
        }

        val outcome = service(transport).reset(plan(), testTarget(), "run-1")

        assertTrue(outcome.performed)
        assertTrue(outcome.verified)
        assertEquals("0", outcome.checks.single().observed)
    }

    @Test
    fun `refuses to call the environment clean when a leftover is still there`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/harness/reset" -> jsonResponse(200, "{}")
                else -> jsonResponse(200, """{"orderCount":4}""")
            }
        }

        val outcome = service(transport).reset(plan(), testTarget(), "run-1")

        assertTrue(outcome.performed)
        assertFalse(outcome.verified)
        assertTrue(outcome.failure!!.contains("orderCount"))
    }

    @Test
    fun `refuses to call the environment clean when the check could not be read`() {
        val transport = RecordingTransport { request ->
            when (request.uri.path) {
                "/harness/reset" -> jsonResponse(200, "{}")
                else -> jsonResponse(503, "down")
            }
        }

        val outcome = service(transport).reset(plan(), testTarget(), "run-1")

        assertFalse(outcome.verified)
    }

    @Test
    fun `reports a reset hook that did not succeed instead of moving on`() {
        val transport = RecordingTransport { jsonResponse(500, """{"error":"boom"}""") }

        val outcome = service(transport).reset(plan(), testTarget(), "run-1")

        assertFalse(outcome.performed)
        assertFalse(outcome.verified)
        assertTrue(outcome.failure!!.contains("reset hook"))
    }

    @Test
    fun `treats a reset nobody can check as unverified`() {
        val transport = RecordingTransport { jsonResponse(200, "{}") }

        val outcome = service(transport).reset(plan(verifications = emptyList()), testTarget(), "run-1")

        assertTrue(outcome.performed)
        assertFalse(outcome.verified)
        assertTrue(outcome.failure!!.contains("no checks"))
    }

    @Test
    fun `has nothing to undo when the specification changed nothing`() {
        val transport = RecordingTransport { jsonResponse(200, "{}") }

        val outcome = service(transport).reset(ResetPlan.NOT_REQUIRED, testTarget(), "run-1")

        assertFalse(outcome.performed)
        assertTrue(outcome.verified)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `rejects foreign run or partial V1 reset result before checking state`() {
        listOf(
            v1ResetBody(runId = OTHER_RUN_ID),
            v1ResetBody(clean = false),
            v1ResetBody(activeFaultCount = 1),
            "{}",
        ).forEach { body ->
            val transport = RecordingTransport { jsonResponse(200, body) }
            val outcome = service(transport).reset(v1Plan(), testTarget(), RUN_ID)
            assertTrue(outcome.performed)
            assertFalse(outcome.verified)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `rejects foreign run state after a valid V1 reset`() {
        val transport = RecordingTransport { request ->
            if (request.uri.path.endsWith("/reset")) jsonResponse(200, v1ResetBody())
            else jsonResponse(200, v1StateBody(runId = OTHER_RUN_ID))
        }
        val outcome = service(transport).reset(v1Plan(), testTarget(), RUN_ID)
        assertFalse(outcome.verified)
        assertTrue(outcome.checks.all { !it.satisfied })
    }

    @Test
    fun `accepts matching V1 reset and clean state`() {
        val transport = RecordingTransport { request ->
            if (request.uri.path.endsWith("/reset")) jsonResponse(200, v1ResetBody())
            else jsonResponse(200, v1StateBody())
        }
        assertTrue(service(transport).reset(v1Plan(), testTarget(), RUN_ID).verified)
    }

    @Test
    fun `rejects a remaining product although orders are clean`() {
        val transport = RecordingTransport { request ->
            if (request.uri.path.endsWith("/reset")) jsonResponse(200, v1ResetBody())
            else jsonResponse(200, v1StateBody(productCount = 1))
        }
        val outcome = service(transport).reset(v1Plan(), testTarget(), RUN_ID)
        assertFalse(outcome.verified)
        assertTrue(outcome.checks.any { it.id == "productCount" && !it.satisfied })
    }

    private fun v1ResetBody(
        runId: String = RUN_ID, clean: Boolean = true, activeFaultCount: Int = 0,
    ) = """{"version":"1.0","runId":"$runId","clean":$clean,"removedFixtureCount":0,"activeFaultCount":$activeFaultCount}"""

    private fun v1StateBody(runId: String = RUN_ID, productCount: Int = 0) =
        """{"version":"1.0","runId":"$runId","productCount":$productCount,"orderCount":0,"paymentCount":0,"completedPaymentCount":0,"failedPaymentCount":0,"activeFaultCount":0}"""

    private fun v1Plan() = plan().copy(
        hook = SpecHttpCall("POST", "/api/harness/reset", null, emptyMap(), null),
        verifications = listOf(
            "productCount", "orderCount", "paymentCount", "completedPaymentCount",
            "failedPaymentCount", "activeFaultCount",
        ).map { field ->
            ResetVerification(
                id = field,
                call = SpecHttpCall("GET", "/api/harness/state", null, emptyMap(), null),
                expression = "response.body.$field",
                condition = "$field == 0",
                readTiming = ReadTiming.IMMEDIATE,
            )
        },
    )

    private companion object {
        const val RUN_ID = "00000000-0000-4000-8000-000000000001"
        const val OTHER_RUN_ID = "00000000-0000-4000-8000-000000000002"
    }

    private fun service(transport: RecordingTransport) = EnvironmentResetService(
        caller = SpecHttpCaller(
            transport = transport,
            references = references,
            authProvider = StubAuthProvider(emptyMap()),
            settings = FixedSpecExecutionSettings(),
        ),
        values = SpecValueReader(
            caller = SpecHttpCaller(
                transport = transport,
                references = references,
                authProvider = StubAuthProvider(emptyMap()),
                settings = FixedSpecExecutionSettings(),
            ),
            evaluator = evaluator,
            settings = FixedSpecExecutionSettings(maxObservationWait = Duration.ofMillis(200)),
        ),
        expressions = SpecExpressionEnvironment(),
    )

    private fun plan(verifications: List<ResetVerification> = listOf(orderCountIsZero())) = ResetPlan(
        method = CleanupMethod.ENVIRONMENT_RESET,
        hook = SpecHttpCall("POST", "/harness/reset", null, emptyMap(), null),
        expectedDuration = Duration.ofSeconds(120),
        verifications = verifications,
    )

    private fun orderCountIsZero() = ResetVerification(
        id = "orderCount",
        call = SpecHttpCall("GET", "/harness/state", null, emptyMap(), null),
        expression = "response.body.orderCount",
        condition = "orderCount == 0",
        readTiming = ReadTiming.IMMEDIATE,
    )
}
