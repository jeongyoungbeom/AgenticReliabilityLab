package com.project.agenticreliabilitylab.experiment.api

import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyExperimentService
import com.project.agenticreliabilitylab.experiment.application.port.ExperimentRunStore
import com.project.agenticreliabilitylab.experiment.application.port.NewExperimentRun
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves a Profile that selects the Harness adapter actually routes an Experiment through it.
 *
 * The adapter unit tests call the adapter directly, which cannot catch a wiring mistake: a correct adapter that the
 * engine never selects would still pass them. This drives the public Experiment API instead, so the Profile's
 * adapter id, capability discovery, execution and the existing invariant oracle are all exercised together.
 *
 * Several tests deliberately end with cleanup unverified, and an unverified cleanup blocks every later run on that
 * Target by design. So each test names its own Target rather than resetting that interlock: the safety rule keeps
 * working exactly as it does in production, and the tests stay independent of each other's order.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestHarnessExperimentIntegrationTests(
    @Autowired private val runStore: ExperimentRunStore,
    @Autowired private val experimentService: StockConcurrencyExperimentService,
    @Value("\${local.server.port}") private val serverPort: Int,
) {
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @Test
    fun `runs a stock concurrency experiment through the declared harness capability`() {
        executionBody.set(successBody())

        val runId = runExperiment(PASS_TARGET, "harness-run")
        val completed = awaitTerminalRun(runId)

        assertContains(completed, "\"runStatus\":\"COMPLETED\"")
        assertContains(completed, "\"systemOutcome\":\"PASSED\"")
        assertContains(completed, "\"cleanupStatus\":\"VERIFIED\"")
        assertContains(lastExecutionRequest.get(), "\"contractVersion\":\"TEST_HARNESS_V1\"")
        assertContains(lastExecutionRequest.get(), "\"capabilityId\":\"stock-concurrency-v1\"")
        assertContains(lastExecutionRequest.get(), "\"idempotencyKey\":\"$runId:stock-concurrency-target\"")
    }

    @Test
    fun `names the broken invariant and its evidence instead of reporting a bare failure`() {
        executionBody.set(observationBody(successCount = 10, oversellCount = 3, finalStock = 5))

        val completed = awaitTerminalRun(runExperiment(INVARIANT_FAILURE_TARGET, "harness-fail"))

        assertContains(completed, "\"systemOutcome\":\"FAILED\"")
        assertContains(completed, "stock-concurrency-invariants-v1")
        assertContains(completed, "oversell-count-is-zero")
        assertContains(completed, "db-stock-matches-expected")
        assertContains(completed, "STOCK_CONCURRENCY invariant(s) failed")
    }

    @Test
    fun `leaves an unreported observation unjudged rather than calling it a violation`() {
        executionBody.set(
            """
            {
              "status":"COMPLETED",
              "operationId":"harness-op-2",
              "fixture":{"productId":"test-product-1"},
              "observations":{
                "successCount":10,
                "failureCount":0,
                "oversellCount":0,
                "durationSeconds":1
              },
              "resources":[{"type":"product","id":"test-product-1","namespace":"arl-harness-test"}],
              "cleanup":{"status":"VERIFIED"}
            }
            """.trimIndent(),
        )

        val completed = awaitTerminalRun(runExperiment(PARTIAL_OBSERVATION_TARGET, "harness-partial"))

        assertContains(completed, "\"systemOutcome\":\"INCONCLUSIVE\"")
        assertContains(completed, "NOT_EVALUATED")
        assertContains(completed, "did not report the final")
    }

    @Test
    fun `treats an incomplete target workload as unjudged rather than a reliability failure`() {
        executionBody.set(
            observationBody(successCount = 10, oversellCount = 3, finalStock = 5)
                .replace("\"status\":\"COMPLETED\"", "\"status\":\"FAILED\""),
        )

        val completed = awaitTerminalRun(runExperiment(INCOMPLETE_WORKLOAD_TARGET, "harness-incomplete"))

        assertContains(completed, "\"systemOutcome\":\"INCONCLUSIVE\"")
        assertContains(completed, "reported 'FAILED' instead of COMPLETED")
        assertContains(completed, "\\\"workloadCompleted\\\":false")
    }

    @Test
    fun `fails the run when the target does not verify cleanup`() {
        executionBody.set(
            observationBody(successCount = 10, oversellCount = 0, finalStock = 0)
                .replace("\"cleanup\":{\"status\":\"VERIFIED\"}", "\"cleanup\":{\"status\":\"PENDING\"}"),
        )

        val completed = awaitTerminalRun(runExperiment(DIRTY_CLEANUP_TARGET, "harness-dirty-cleanup"))

        assertContains(completed, "\"cleanupStatus\":\"FAILED\"")
        assertContains(completed, "\"cleanupFailureCode\":\"CLEANUP_NOT_VERIFIED\"")
    }

    /**
     * A Target that reports nothing it created cannot prove it cleaned up, so the run fails on cleanup even though
     * every invariant passed. That split - a passing verdict on a failed run - is what this asserts.
     */
    @Test
    fun `fails a run whose invariants all passed when the target recorded no resource`() {
        executionBody.set(
            """
            {
              "status":"COMPLETED",
              "operationId":"harness-op-3",
              "observations":{
                "successCount":10,
                "failureCount":0,
                "oversellCount":0,
                "finalRedisStock":0,
                "finalDbStock":0,
                "durationSeconds":1
              },
              "resources":[],
              "cleanup":{"status":"VERIFIED"}
            }
            """.trimIndent(),
        )

        val completed = awaitTerminalRun(runExperiment(NO_RESOURCE_TARGET, "harness-no-resource"))

        assertContains(completed, "\"runStatus\":\"FAILED\"")
        assertContains(completed, "\"systemOutcome\":\"PASSED\"")
        assertContains(completed, "\"cleanupStatus\":\"FAILED\"")
        assertContains(completed, "\"cleanupFailureCode\":\"CLEANUP_NO_RESOURCES\"")
    }

    /**
     * A capability is read per execution, not once at approval, so a Harness that narrows its declared bounds after a
     * run was accepted must stop that run. Nothing may be dispatched: the run has to end as a validation failure with
     * cleanup NOT_REQUIRED, because a Target that was never called cannot have been left dirty.
     */
    @Test
    fun `refuses a run whose capability shrank below the requested parameters after acceptance`() {
        executionBody.set(successBody())
        capabilities.set(capabilitiesBody(maxConcurrency = 2))

        val completed = try {
            awaitTerminalRun(runExperiment(SHRUNK_CAPABILITY_TARGET, "harness-shrunk"))
        } finally {
            capabilities.set(capabilitiesBody(maxConcurrency = DEFAULT_MAX_CONCURRENCY))
        }

        assertContains(completed, "\"runStatus\":\"VALIDATION_FAILED\"")
        assertContains(completed, "exceeds the effective Test Harness bound")
        assertContains(completed, "\"cleanupStatus\":\"NOT_REQUIRED\"")
    }

    /**
     * When a dispatched action's outcome is unknown, the workload lease is deliberately not released: something may
     * still be running inside the Target, so nothing else may touch the same host until a human resolves it.
     *
     * The two Targets here share one host resource group, which is what makes the held lease observable - the first
     * Target's own runs are already blocked by its unverified cleanup, so a second Target is needed to see it.
     */
    @Test
    fun `holds the shared host lease after a dispatched action outcome becomes unknown`() {
        executionStatus.set(500)
        executionBody.set("{}")

        val unknown = awaitTerminalRun(runExperiment(SHARED_HOST_TARGET_A, "harness-unknown"))
        assertContains(unknown, "\"runStatus\":\"RECOVERY_REQUIRED\"")

        executionStatus.set(200)
        executionBody.set(successBody())

        val blocked = awaitTerminalRun(runExperiment(SHARED_HOST_TARGET_B, "harness-lease-blocked"))
        assertContains(blocked, "\"runStatus\":\"FAILED\"")
        assertContains(blocked, "Another workload currently owns")
    }

    /**
     * Simulates the one state no request can produce: the process died while a Target operation was in flight.
     *
     * The run is left mid-flight through the store because that is exactly what a crash leaves behind, then the
     * restart hook runs. Recovery must not guess that nothing happened - the run becomes RECOVERY_REQUIRED and the
     * Target stays blocked until a human resolves it.
     */
    @Test
    fun `marks a run left in flight by a restart as recovery required and blocks the target`() {
        val runId = UUID.randomUUID()
        runStore.create(
            NewExperimentRun(
                id = runId,
                targetSystemId = RESTART_TARGET,
                definitionVersion = "stock-concurrency-v1",
                parametersJson = """{"stock":10,"requestCount":10,"concurrency":5,"quantityPerRequest":1}""",
                plannedRunSpecId = UUID.randomUUID(),
                idempotencyKey = "harness-restart-${Instant.now().toEpochMilli()}",
                loadProfileJson = "{}",
                fixturePlanJson = "{}",
                hostResourceGroup = RESTART_TARGET,
                specHash = "restart-spec-hash",
                queuedAt = Instant.now(),
            ),
        )
        assertTrue(runStore.claimForExecution(runId, Instant.now()), "The seeded run should be claimable")

        experimentService.recoverIncompleteRuns()

        val recovered = getRun(runId.toString())
        assertContains(recovered, "\"runStatus\":\"RECOVERY_REQUIRED\"")

        val rejected = postExperiment(RESTART_TARGET, "harness-restart-blocked-${Instant.now().toEpochMilli()}")
        assertEquals(409, rejected.statusCode(), rejected.body())
        assertContains(rejected.body(), "CLEANUP_BLOCKING")
    }

    private fun runExperiment(targetSystemId: String, prefix: String): String {
        val started = postExperiment(targetSystemId, "$prefix-${Instant.now().toEpochMilli()}")
        assertEquals(202, started.statusCode(), started.body())
        return Regex("\"id\":\"([^\"]+)\"").find(started.body())?.groupValues?.get(1)
            ?: error("Experiment run id was absent: ${started.body()}")
    }

    private fun postExperiment(targetSystemId: String, key: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/experiments"))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(experimentBody(targetSystemId)))
            .timeout(Duration.ofSeconds(5))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun getRun(runId: String): String = httpClient.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/experiments/$runId"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    private fun awaitTerminalRun(runId: String): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        do {
            val body = getRun(runId)
            if (TERMINAL_MARKERS.any { marker -> marker in body }) return body
            Thread.sleep(SLEEP_MILLIS)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Experiment run '$runId' did not reach a terminal state")
    }

    private companion object {
        const val HEALTH_PATH = "/actuator/health"
        const val CAPABILITIES_PATH = "/harness/v1/capabilities"
        const val EXECUTION_PATH = "/harness/v1/executions"
        const val SLEEP_MILLIS = 25L
        const val DEFAULT_MAX_CONCURRENCY = 50

        const val PASS_TARGET = "harness-pass"
        const val INVARIANT_FAILURE_TARGET = "harness-invariant-failure"
        const val PARTIAL_OBSERVATION_TARGET = "harness-partial-observation"
        const val INCOMPLETE_WORKLOAD_TARGET = "harness-incomplete-workload"
        const val DIRTY_CLEANUP_TARGET = "harness-dirty-cleanup"
        const val NO_RESOURCE_TARGET = "harness-no-resource"
        const val SHRUNK_CAPABILITY_TARGET = "harness-shrunk-capability"
        const val RESTART_TARGET = "harness-restart"
        const val SHARED_HOST_TARGET_A = "harness-shared-host-a"
        const val SHARED_HOST_TARGET_B = "harness-shared-host-b"
        const val SHARED_HOST_GROUP = "harness-shared-host"

        val ISOLATED_TARGETS = listOf(
            PASS_TARGET,
            INVARIANT_FAILURE_TARGET,
            PARTIAL_OBSERVATION_TARGET,
            INCOMPLETE_WORKLOAD_TARGET,
            DIRTY_CLEANUP_TARGET,
            NO_RESOURCE_TARGET,
            SHRUNK_CAPABILITY_TARGET,
            RESTART_TARGET,
        )

        val TERMINAL_MARKERS = listOf(
            "\"runStatus\":\"COMPLETED\"",
            "\"runStatus\":\"FAILED\"",
            "\"runStatus\":\"VALIDATION_FAILED\"",
            "\"runStatus\":\"RECOVERY_REQUIRED\"",
        )

        val lastExecutionRequest = AtomicReference("")
        val executionBody = AtomicReference("")
        val executionStatus = AtomicInteger(200)
        val capabilities = AtomicReference(capabilitiesBody(DEFAULT_MAX_CONCURRENCY))

        val harness: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(HEALTH_PATH) { exchange ->
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            createContext(CAPABILITIES_PATH) { exchange -> exchange.respond(capabilities.get(), 200) }
            createContext(EXECUTION_PATH) { exchange ->
                lastExecutionRequest.set(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
                exchange.respond(executionBody.get(), executionStatus.get())
            }
            executor = null
            start()
        }

        fun capabilitiesBody(maxConcurrency: Int): String = """
            {
              "contractVersion":"TEST_HARNESS_V1",
              "capabilities":[
                {
                  "id":"stock-concurrency-v1",
                  "version":"1",
                  "experimentType":"STOCK_CONCURRENCY",
                  "environments":["LOCAL","TEST"],
                  "testNamespace":"arl-harness-test",
                  "limits":{
                    "maxStock":100,
                    "maxRequestCount":100,
                    "maxConcurrency":$maxConcurrency,
                    "maxQuantityPerRequest":5
                  },
                  "invariantIds":["stock-non-negative"],
                  "cleanupVerification":"REQUIRED"
                }
              ]
            }
        """.trimIndent()

        fun experimentBody(targetSystemId: String): String = """
            {
              "targetSystem": "$targetSystemId",
              "type": "STOCK_CONCURRENCY",
              "parameters": {
                "stock": 10,
                "requestCount": 10,
                "concurrency": 5,
                "quantityPerRequest": 1
              }
            }
        """.trimIndent()

        fun successBody(): String = observationBody(successCount = 10, oversellCount = 0, finalStock = 0)

        fun observationBody(successCount: Int, oversellCount: Int, finalStock: Int): String = """
            {
              "status":"COMPLETED",
              "operationId":"harness-op-1",
              "message":"done",
              "fixture":{"productId":"test-product-1"},
              "observations":{
                "successCount":$successCount,
                "failureCount":${10 - successCount},
                "oversellCount":$oversellCount,
                "finalRedisStock":$finalStock,
                "finalDbStock":$finalStock,
                "durationSeconds":1
              },
              "resources":[{"type":"product","id":"test-product-1","namespace":"arl-harness-test"}],
              "cleanup":{"status":"VERIFIED"}
            }
        """.trimIndent()

        private fun HttpExchange.respond(body: String, statusCode: Int) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(statusCode, bytes.size.toLong())
            responseBody.use { stream -> stream.write(bytes) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun harnessTargetProperties(registry: DynamicPropertyRegistry) {
            val origin = "http://127.0.0.1:${harness.address.port}"
            ISOLATED_TARGETS.forEachIndexed { index, id -> registerTarget(registry, index, id, id, origin) }
            val shared = ISOLATED_TARGETS.size
            registerTarget(registry, shared, SHARED_HOST_TARGET_A, SHARED_HOST_GROUP, origin)
            registerTarget(registry, shared + 1, SHARED_HOST_TARGET_B, SHARED_HOST_GROUP, origin)
        }

        private fun registerTarget(
            registry: DynamicPropertyRegistry,
            index: Int,
            id: String,
            hostResourceGroup: String,
            origin: String,
        ) {
            val prefix = "arl.targets.registrations[$index]"
            registry.add("$prefix.id") { id }
            registry.add("$prefix.name") { "Harness Experiment Target $index" }
            registry.add("$prefix.adapter-type") { "HTTP_TARGET" }
            registry.add("$prefix.environment") { "TEST" }
            registry.add("$prefix.base-url") { origin }
            registry.add("$prefix.allowed-origin") { origin }
            registry.add("$prefix.allowed-cidrs[0]") { "127.0.0.0/8" }
            registry.add("$prefix.health-path") { HEALTH_PATH }
            registry.add("$prefix.source-repository") { id }
            registry.add("$prefix.identity-verification") { "CONFIGURATION_ONLY" }
            registry.add("$prefix.capabilities[0]") { "HEALTH" }
            registry.add("$prefix.capabilities[1]") { "STOCK_CONCURRENCY" }
            registry.add("$prefix.enabled") { true }
            registerExperimentTarget(registry, index, id, hostResourceGroup)
        }

        private fun registerExperimentTarget(
            registry: DynamicPropertyRegistry,
            index: Int,
            id: String,
            hostResourceGroup: String,
        ) {
            val prefix = "arl.experiment-targets.registrations[$index]"
            registry.add("$prefix.target-system-id") { id }
            registry.add("$prefix.adapter-id") { "TEST_HARNESS_V1" }
            registry.add("$prefix.execution-enabled") { true }
            registry.add("$prefix.host-resource-group") { hostResourceGroup }
            registry.add("$prefix.stock-concurrency.endpoint") { EXECUTION_PATH }
            registry.add("$prefix.stock-concurrency.capabilities-endpoint") { CAPABILITIES_PATH }
            registry.add("$prefix.stock-concurrency.max-stock") { 100 }
            registry.add("$prefix.stock-concurrency.max-request-count") { 100 }
            registry.add("$prefix.stock-concurrency.max-concurrency") { DEFAULT_MAX_CONCURRENCY }
            registry.add("$prefix.stock-concurrency.max-quantity-per-request") { 5 }
            registry.add("$prefix.stock-concurrency.execution-timeout") { "10s" }
        }

        @JvmStatic
        @AfterAll
        fun stopHarness() {
            harness.stop(0)
        }
    }
}
