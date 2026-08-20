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
