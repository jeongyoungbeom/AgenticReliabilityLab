package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.DeclaredObservationSource
import com.project.agenticreliabilitylab.testspec.application.DeclaredObservationSourceKind
import com.project.agenticreliabilitylab.testspec.application.FixedSpecExecutionSettings
import com.project.agenticreliabilitylab.testspec.application.RecordingTransport
import com.project.agenticreliabilitylab.testspec.application.StubAuthProvider
import com.project.agenticreliabilitylab.testspec.application.jsonResponse
import com.project.agenticreliabilitylab.testspec.application.testTarget
import com.project.agenticreliabilitylab.testspec.application.port.DeclaredObservationRequest
import com.project.agenticreliabilitylab.testspec.domain.ObservationWindow
import com.project.agenticreliabilitylab.testspec.domain.ObservedSpan
import tools.jackson.databind.ObjectMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpDeclaredObservationSourceClientTests {
    @Test
    fun `negotiates one harness state snapshot for all requested fields`() {
        val transport = RecordingTransport {
            jsonResponse(
                200,
                """{"contractVersion":"HARNESS_STATE_V1","fields":["dbStock","orderCount"],"state":{
                    "dbStock":7,"orderCount":2}}""".trimIndent(),
            )
        }
        val client = client(transport, mapOf("seller" to mapOf("Authorization" to "test-secret")))

        val result = client.read(
            request(harnessSource(authProfile = "seller"), linkedSetOf("dbStock", "orderCount")),
        )

        assertEquals(7L, result.getValue("dbStock").value)
        assertEquals(2L, result.getValue("orderCount").value)
        assertEquals("/harness/state", transport.requests.single().uri.path)
        assertEquals("test-secret", transport.requests.single().headers["Authorization"])
        assertEquals("run-18", transport.requests.single().headers["X-ARL-Run-Id"])
    }

    @Test
    fun `localizes a harness capability that no longer provides the field`() {
        val transport = RecordingTransport {
            jsonResponse(
                200,
                """{"contractVersion":"HARNESS_STATE_V1","fields":["orderCount"],"state":{"orderCount":0}}""",
            )
        }

        val result = client(transport)
            .read(request(harnessSource(), setOf("dbStock")))
            .getValue("dbStock")

        assertFalse(result.present)
        assertTrue(result.failure.orEmpty().contains("does not provide 'dbStock'"))
    }

    @Test
    fun `executes only the profile owned Prometheus query and reads one vector value`() {
        val transport = RecordingTransport {
            jsonResponse(
                200,
                """{"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1,"42.5"]}]}}""",
            )
        }

        val result = client(transport)
            .read(request(prometheusSource(), setOf("pendingPayments")))
            .getValue("pendingPayments")

        assertTrue(result.present)
        assertEquals(42.5, result.value)
        assertEquals("/prometheus/api/v1/query", transport.requests.single().uri.path)
        assertTrue(transport.requests.single().uri.rawQuery.contains("pending_payments"))
    }

    @Test
    fun `reports an empty Prometheus vector as an unavailable observation`() {
        val transport = RecordingTransport {
            jsonResponse(200, """{"status":"success","data":{"resultType":"vector","result":[]}}""")
        }

        val result = client(transport)
            .read(request(prometheusSource(), setOf("pendingPayments")))
            .getValue("pendingPayments")

        assertFalse(result.present)
        assertTrue(result.failure.orEmpty().contains("exactly one series"))
    }

    @Test
    fun `caps the outbound request timeout to the remaining observation budget`() {
        val transport = RecordingTransport { jsonResponse(503, "{}") }

        client(transport).read(
            request(harnessSource(), setOf("dbStock"), timeout = Duration.ofMillis(125)),
        )

        assertEquals(Duration.ofMillis(125), transport.requests.single().timeout)
    }

    @Test
    fun `queries the trace store inside the trial window with the profile owned TraceQL`() {
        val transport = RecordingTransport {
            jsonResponse(200, traceResponse(RESERVE_START_NANOS, durationNanos = 5_000_000))
        }

        val result = client(transport)
            .read(request(traceSource(), setOf("reserveSpans")))
            .getValue("reserveSpans")

        assertTrue(result.present)
        val spans = result.value as List<*>
        val span = spans.single() as Map<*, *>
        assertEquals("t1", span[ObservedSpan.TRACE_ID])
        assertEquals(RESERVE_START_MILLIS, span[ObservedSpan.START_MS])
        assertEquals(5L, span[ObservedSpan.DURATION_MS])

        val sent = transport.requests.single()
        assertEquals("/api/search", sent.uri.path)
        assertTrue(sent.uri.rawQuery.contains("inventory.reserve"))
        // The Profile's placeholder is gone by the time the query is sent, replaced by this trial's own scope.
        assertTrue(sent.uri.rawQuery.contains(URLEncoder.encode(TRIAL_SCOPE, StandardCharsets.UTF_8)))
        assertFalse(sent.uri.rawQuery.contains("trial%7D"))
        assertTrue(sent.uri.rawQuery.contains("start=${WINDOW_START.epochSecond}"))
        // Both bounds are sent so that reaching either one means "truncated" rather than "that was all of it".
        assertTrue(sent.uri.rawQuery.contains("limit="))
        assertTrue(sent.uri.rawQuery.contains("spss="))
    }

    @Test
    fun `drops spans that fall outside the trial window`() {
        val transport = RecordingTransport {
            jsonResponse(200, traceResponse(RESERVE_START_NANOS - HOUR_NANOS, durationNanos = 1_000_000))
        }

        val result = client(transport)
            .read(request(traceSource(), setOf("reserveSpans")))
            .getValue("reserveSpans")

        assertTrue(result.present)
        assertEquals(emptyList<Any>(), result.value)
    }

    @Test
    fun `localizes a trace response that does not match the expected shape`() {
        val transport = RecordingTransport { jsonResponse(200, """{"metrics":{"inspectedTraces":0}}""") }

        val result = client(transport)
            .read(request(traceSource(), setOf("reserveSpans")))
            .getValue("reserveSpans")

        assertFalse(result.present)
        assertTrue(result.failure.orEmpty().contains("no traces array"))
    }

    @Test
    fun `refuses a trace read that has no trial window`() {
        val transport = RecordingTransport { jsonResponse(200, """{"traces":[]}""") }

        val result = client(transport)
            .read(request(traceSource(), setOf("reserveSpans"), window = null))
            .getValue("reserveSpans")

        assertFalse(result.present)
        assertTrue(result.failure.orEmpty().contains("no observation window"))
    }

    private fun request(
        source: DeclaredObservationSource,
        fields: Set<String>,
        timeout: Duration = Duration.ofSeconds(1),
        window: ObservationWindow? = ObservationWindow(WINDOW_START, WINDOW_END),
    ) = DeclaredObservationRequest(
        target = testTarget(),
        source = source,
        fields = fields,
        runId = "run-18",
        trialScope = TRIAL_SCOPE,
        timeout = timeout,
        window = window,
    )

    private fun client(
        transport: RecordingTransport,
        auth: Map<String, Map<String, String>> = emptyMap(),
    ) = HttpDeclaredObservationSourceClient(
        transport,
        StubAuthProvider(auth),
        FixedSpecExecutionSettings(),
        ObjectMapper(),
        TempoSpanParser(),
    )

    private fun traceResponse(startNanos: Long, durationNanos: Long): String =
        """{"traces":[{"traceID":"t1","spanSets":[{"spans":[{"spanID":"s1","name":"inventory.reserve",""" +
            """"startTimeUnixNano":"$startNanos","durationNanos":"$durationNanos"}]}]}]}"""

    private fun harnessSource(authProfile: String? = null) = DeclaredObservationSource(
        name = "harness",
        kind = DeclaredObservationSourceKind.HARNESS_STATE,
        endpoint = "/harness/state",
        fields = setOf("dbStock", "orderCount"),
        queries = emptyMap(),
        authProfile = authProfile,
    )

    private fun prometheusSource() = DeclaredObservationSource(
        name = "metrics",
        kind = DeclaredObservationSourceKind.PROMETHEUS,
        endpoint = "http://127.0.0.1:19090/prometheus",
        fields = setOf("pendingPayments"),
        queries = mapOf("pendingPayments" to "sum(pending_payments)"),
        authProfile = null,
    )

    private fun traceSource() = DeclaredObservationSource(
        name = "traces",
        kind = DeclaredObservationSourceKind.TRACE,
        endpoint = "http://127.0.0.1:13200",
        fields = setOf("reserveSpans"),
        queries = mapOf(
            "reserveSpans" to """{name="inventory.reserve" && span.arl.trial="${'$'}{trial}"}""",
        ),
        authProfile = null,
    )

    private companion object {
        const val RESERVE_START_MILLIS = 1_700_000_000_000L
        const val RESERVE_START_NANOS = 1_700_000_000_000_000_000L
        const val HOUR_NANOS = 3_600_000_000_000L
        const val TRIAL_SCOPE = "run-18/2"
        val WINDOW_START: Instant = Instant.ofEpochMilli(RESERVE_START_MILLIS - 5_000L)
        val WINDOW_END: Instant = Instant.ofEpochMilli(RESERVE_START_MILLIS + 5_000L)
    }
}
