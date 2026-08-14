package com.project.agenticreliabilitylab.experiment.api

import com.project.agenticreliabilitylab.analysis.application.ReliabilityAnalysisModel
import com.project.agenticreliabilitylab.analysis.application.ReliabilityAnalysisModelRequest
import com.project.agenticreliabilitylab.analysis.application.ReliabilityAnalysisModelResponse
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExperimentApiIntegrationTests.FakeAnalysisModelConfiguration::class)
class ExperimentApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Autowired
    private lateinit var analysisModel: FakeReliabilityAnalysisModel

    @Test
    fun `executes a registered HTTP scenario and persists evidence`() {
        val key = "phase1-success-${Instant.now().toEpochMilli()}"
        val before = scenarioExecutions.get()
        val started = postExperiment(key, parameters(stock = 10, requestCount = 50, concurrency = 10, quantity = 1))

        assertEquals(202, started.statusCode())
        val runId = started.body().jsonValue("id")
        val completed = awaitTerminalRun(runId)

        assertContains(completed, "\"runStatus\":\"COMPLETED\"")
        assertContains(completed, "\"systemOutcome\":\"PASSED\"")
        assertContains(completed, "\"cleanupStatus\":\"VERIFIED\"")

        val evidence = get("/api/experiments/$runId/evidence")
        assertEquals(200, evidence.statusCode())
        assertContains(evidence.body(), "HTTP_SCENARIO_V1")
        assertContains(evidence.body(), "finalDbStock")
        assertContains(lastScenarioRequest.get(), "\"actionId\":\"stock-concurrency-target\"")
        assertContains(lastScenarioRequest.get(), "\"idempotencyKey\":\"$runId:stock-concurrency-target\"")
        assertEquals(before + 1, scenarioExecutions.get())
    }

    @Test
    fun `does not create a second target action for the same idempotency key`() {
        val key = "phase1-idempotency-${Instant.now().toEpochMilli()}"
        val before = scenarioExecutions.get()
        val first = postExperiment(key, parameters(stock = 8, requestCount = 20, concurrency = 4, quantity = 1))
        val second = postExperiment(key, parameters(stock = 8, requestCount = 20, concurrency = 4, quantity = 1))

        assertEquals(202, first.statusCode())
        assertEquals(202, second.statusCode())
        assertEquals(first.body().jsonValue("id"), second.body().jsonValue("id"))
        awaitTerminalRun(first.body().jsonValue("id"))
        assertEquals(before + 1, scenarioExecutions.get())
    }

    @Test
    fun `rejects a parameter beyond the registered target profile limit before dispatch`() {
        val before = scenarioExecutions.get()
        val response = postExperiment(
            "phase1-limit-${Instant.now().toEpochMilli()}",
            parameters(stock = 10, requestCount = 10, concurrency = 10, quantity = 101),
        )

        assertEquals(400, response.statusCode())
        assertContains(response.body(), "quantityPerRequest")
        assertEquals(before, scenarioExecutions.get())
    }

    @Test
    fun `returns the common error contract with the request correlation id`() {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/experiments"))
                .header("Content-Type", "application/json")
                .header("X-Correlation-Id", "api-contract-test")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        parameters(stock = 10, requestCount = 10, concurrency = 2, quantity = 1),
                    ),
                )
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(400, response.statusCode())
        assertContains(response.body(), "\"code\":\"INVALID_REQUEST\"")
        assertContains(response.body(), "\"correlationId\":\"api-contract-test\"")
        assertEquals("api-contract-test", response.headers().firstValue("X-Correlation-Id").orElse(null))
    }

    @Test
    fun `runs a sequential campaign and persists each linked experiment step`() {
        val before = scenarioExecutions.get()
        val response = postCampaign(
            "phase1-campaign-${Instant.now().toEpochMilli()}",
            """
            {
              "targetSystem": "contract-test-target",
              "repeatCount": 2,
              "parameters": {
                "stock": 10,
                "requestCount": 50,
                "concurrency": 10,
                "quantityPerRequest": 1
              }
            }
            """.trimIndent(),
        )

        assertEquals(202, response.statusCode(), response.body())
        val campaignId = response.body().jsonValue("id")
        val completed = awaitTerminalCampaign(campaignId)

        assertContains(completed, "\"status\":\"COMPLETED\"")
        assertEquals(2, Regex("\"experimentRunId\":\"").findAll(completed).count())
        assertEquals(3, Regex("\"status\":\"COMPLETED\"").findAll(completed).count())
        assertEquals(before + 2, scenarioExecutions.get())
    }

    @Test
    fun `analyzes completed evidence once and persists grounded findings and recommendations`() {
        val experiment = postExperiment(
            "phase2-analysis-experiment-${Instant.now().toEpochMilli()}",
            parameters(stock = 10, requestCount = 50, concurrency = 10, quantity = 1),
        )
        assertEquals(202, experiment.statusCode())
        val experimentRunId = experiment.body().jsonValue("id")
        awaitTerminalRun(experimentRunId)

        val before = analysisModel.calls.get()
        val key = "phase2-analysis-${Instant.now().toEpochMilli()}"
        val first = postAnalysis(experimentRunId, key)
        val second = postAnalysis(experimentRunId, key)

        assertEquals(202, first.statusCode(), first.body())
        assertEquals(202, second.statusCode(), second.body())
        assertEquals(first.body().jsonValue("id"), second.body().jsonValue("id"))

        val analysis = awaitTerminalAnalysis(first.body().jsonValue("id"))
        assertContains(analysis, "\"status\":\"COMPLETED\"")
        assertContains(analysis, "\"modelId\":\"fake-gpt-oss\"")
        assertContains(analysis, "\"title\":\"Stock accounting is consistent\"")
        assertContains(analysis, "\"recommendedAction\":\"Keep the current invariant checks in the regression suite.\"")
        assertContains(analysis, "\"inputEvidenceCount\":1")
        assertEquals(before + 1, analysisModel.calls.get())
    }

    @Test
    fun `rejects a model finding that cites evidence outside the supplied bundle`() {
        val experiment = postExperiment(
            "phase2-invalid-output-experiment-${Instant.now().toEpochMilli()}",
            parameters(stock = 10, requestCount = 50, concurrency = 10, quantity = 1),
        )
        assertEquals(202, experiment.statusCode())
        val experimentRunId = experiment.body().jsonValue("id")
        awaitTerminalRun(experimentRunId)

        analysisModel.returnUnknownEvidenceId = true
        try {
            val started = postAnalysis(experimentRunId, "phase2-invalid-output-${Instant.now().toEpochMilli()}")
            assertEquals(202, started.statusCode(), started.body())
            val analysis = awaitTerminalAnalysis(started.body().jsonValue("id"))
            assertContains(analysis, "\"status\":\"FAILED\"")
            assertContains(analysis, "\"failureCode\":\"MODEL_OUTPUT_INVALID\"")
        } finally {
            analysisModel.returnUnknownEvidenceId = false
        }
    }

    @Test
    fun `selects the registered Qwen model and records invocation metrics`() {
        val experiment = postExperiment(
            "phase3-qwen-experiment-${Instant.now().toEpochMilli()}",
            parameters(stock = 10, requestCount = 50, concurrency = 10, quantity = 1),
        )
        val experimentRunId = experiment.body().jsonValue("id")
        awaitTerminalRun(experimentRunId)

        val started = postAnalysis(experimentRunId, "phase3-qwen-${Instant.now().toEpochMilli()}", "QWEN")
        assertEquals(202, started.statusCode(), started.body())
        val analysis = awaitTerminalAnalysis(started.body().jsonValue("id"))

        assertContains(analysis, "\"modelKey\":\"QWEN\"")
        assertContains(analysis, "\"modelId\":\"fake-qwen\"")
        assertContains(analysis, "\"verdict\":\"PASSED\"")
        assertContains(analysis, "\"promptTokenCount\":17")
        assertContains(analysis, "\"completionTokenCount\":23")
        assertContains(analysis, "\"durationMillis\":5")
        assertTrue(analysisModel.modelIds.contains("fake-qwen"))
    }

    @Test
    fun `compares registered models on one immutable dataset and evaluates both against ground truth`() {
        val experiment = postExperiment(
            "phase4-comparison-experiment-${Instant.now().toEpochMilli()}",
            parameters(stock = 10, requestCount = 50, concurrency = 10, quantity = 1),
        )
        val experimentRunId = experiment.body().jsonValue("id")
        awaitTerminalRun(experimentRunId)
        val evidenceId = get("/api/experiments/$experimentRunId/evidence").body().jsonValue("id")

        val started = postComparison(experimentRunId, "phase4-comparison-${Instant.now().toEpochMilli()}")
        assertEquals(202, started.statusCode(), started.body())
        val comparisonId = started.body().jsonValue("id")
        val datasetId = started.body().jsonValue("analysisDatasetId")
        val runIds = started.body().jsonValues("analysisRunId")
        assertEquals(2, runIds.size)

        val analyses = runIds.map(::awaitTerminalAnalysis)
        assertTrue(analyses.all { "\"analysisDatasetId\":\"$datasetId\"" in it })
        assertTrue(analyses.any { "\"modelKey\":\"GPT_OSS\"" in it })
        assertTrue(analyses.any { "\"modelKey\":\"QWEN\"" in it })

        val groundTruth = postGroundTruth(datasetId, "phase4-v1", evidenceId)
        assertEquals(201, groundTruth.statusCode(), groundTruth.body())
        val groundTruthId = groundTruth.body().jsonValue("id")
        val evaluations = postComparisonEvaluation(comparisonId, groundTruthId)
        assertEquals(200, evaluations.statusCode(), evaluations.body())
        assertEquals(2, Regex("\"score\":1.0").findAll(evaluations.body()).count())
        assertEquals(2, Regex("\"verdictMatch\":true").findAll(evaluations.body()).count())
    }

    @Test
    fun `runs a sequential multi-agent analysis with auditable role and invocation records`() {
        val experiment = postExperiment(
            "phase5-multi-agent-experiment-${Instant.now().toEpochMilli()}",
            parameters(stock = 10, requestCount = 50, concurrency = 10, quantity = 1),
        )
        val experimentRunId = experiment.body().jsonValue("id")
        awaitTerminalRun(experimentRunId)

        val before = analysisModel.calls.get()
        val key = "phase5-multi-agent-${Instant.now().toEpochMilli()}"
        val payload = """
            {
              "roleModelKeys": {
                "SUPERVISOR":"GPT_OSS",
                "PLANNER":"QWEN",
                "ANALYST":"GPT_OSS",
                "REVIEWER":"QWEN"
              }
            }
        """.trimIndent()
        val first = postMultiAnalysis(experimentRunId, key, payload)
        val second = postMultiAnalysis(experimentRunId, key, payload)
        assertEquals(202, first.statusCode(), first.body())
        assertEquals(202, second.statusCode(), second.body())
        assertEquals(first.body().jsonValue("id"), second.body().jsonValue("id"))

        val analysisRunId = first.body().jsonValue("id")
        val analysis = awaitTerminalAnalysis(analysisRunId)
        assertContains(analysis, "\"status\":\"COMPLETED\"")
        assertContains(analysis, "\"agentType\":\"MULTI_RELIABILITY_AGENT\"")
        assertEquals(before + 4, analysisModel.calls.get())

        val details = get("/api/multi-analysis-runs/$analysisRunId")
        assertEquals(200, details.statusCode(), details.body())
        assertContains(details.body(), "\"role\":\"SUPERVISOR\"")
        assertContains(details.body(), "\"role\":\"PLANNER\"")
        assertContains(details.body(), "\"role\":\"ANALYST\"")
        assertContains(details.body(), "\"role\":\"REVIEWER\"")
        assertEquals(4, Regex("\"toolPolicy\":\"NO_TOOLS\"").findAll(details.body()).count())
        assertEquals(4, Regex("\"toolCallCount\":0").findAll(details.body()).count())
        assertContains(details.body(), "\"modelId\":\"fake-gpt-oss\"")
        assertContains(details.body(), "\"modelId\":\"fake-qwen\"")
    }

    @Test
    fun `compares only user-selected single and multi-agent configurations on one dataset`() {
        val experiment = postExperiment(
            "phase6-selected-comparison-experiment-${Instant.now().toEpochMilli()}",
            parameters(stock = 10, requestCount = 50, concurrency = 10, quantity = 1),
        )
        val experimentRunId = experiment.body().jsonValue("id")
        awaitTerminalRun(experimentRunId)

        val before = analysisModel.calls.get()
        val key = "phase6-selected-comparison-${Instant.now().toEpochMilli()}"
        val body = """
            {
              "configurations": [
                {"architecture":"SINGLE","modelKey":"GPT_OSS"},
                {"architecture":"MULTI","modelKey":"GPT_OSS"}
              ]
            }
        """.trimIndent()
        val first = postComparison(experimentRunId, key, body)
        val second = postComparison(experimentRunId, key, body)

        assertEquals(202, first.statusCode(), first.body())
        assertEquals(202, second.statusCode(), second.body())
        assertEquals(first.body().jsonValue("id"), second.body().jsonValue("id"))
        assertEquals(2, first.body().jsonValues("analysisRunId").size)
        assertContains(first.body(), "\"modelKeys\":[\"GPT_OSS\"]")
        assertContains(first.body(), "\"architecture\":\"SINGLE\"")
        assertContains(first.body(), "\"architecture\":\"MULTI\"")

        val analyses = first.body().jsonValues("analysisRunId").map(::awaitTerminalAnalysis)
        assertTrue(analyses.any { "\"agentType\":\"SINGLE_RELIABILITY_AGENT\"" in it })
        assertTrue(analyses.any { "\"agentType\":\"MULTI_RELIABILITY_AGENT\"" in it })
        assertEquals(before + 5, analysisModel.calls.get())

        val completedComparison = get("/api/analysis-comparisons/${first.body().jsonValue("id")}")
        assertEquals(200, completedComparison.statusCode(), completedComparison.body())
        assertContains(completedComparison.body(), "\"promptTokenCount\":68")
        assertContains(completedComparison.body(), "\"completionTokenCount\":92")
        assertContains(completedComparison.body(), "\"durationMillis\":20")

        val conflicting = postComparison(
            experimentRunId,
            key,
            """{"configurations":[{"architecture":"SINGLE","modelKey":"GPT_OSS"},{"architecture":"SINGLE","modelKey":"QWEN"}]}""",
        )
        assertEquals(409, conflicting.statusCode(), conflicting.body())
        assertContains(conflicting.body(), "COMPARISON_IDEMPOTENCY_CONFLICT")
    }

    @Test
    fun `concurrent matching multi-agent idempotency requests return one run`() {
        val experiment = postExperiment(
            "phase5-multi-agent-race-experiment-${Instant.now().toEpochMilli()}",
            parameters(stock = 10, requestCount = 50, concurrency = 10, quantity = 1),
        )
        val experimentRunId = experiment.body().jsonValue("id")
        awaitTerminalRun(experimentRunId)

        val key = "phase5-multi-agent-race-${Instant.now().toEpochMilli()}"
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) {
                executor.submit<HttpResponse<String>> {
                    gate.await()
                    postMultiAnalysis(experimentRunId, key, "{\"modelKey\":\"GPT_OSS\"}")
                }
            }
            gate.countDown()
            val responses = requests.map { it.get() }
            assertTrue(responses.all { it.statusCode() == 202 }, responses.joinToString { it.body() })
            assertEquals(1, responses.map { it.body().jsonValue("id") }.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitTerminalRun(runId: String): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        do {
            val response = get("/api/experiments/$runId")
            assertEquals(200, response.statusCode())
            if ("\"runStatus\":\"COMPLETED\"" in response.body() ||
                "\"runStatus\":\"FAILED\"" in response.body() ||
                "\"runStatus\":\"VALIDATION_FAILED\"" in response.body() ||
                "\"runStatus\":\"RECOVERY_REQUIRED\"" in response.body()
            ) {
                return response.body()
            }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Experiment run '$runId' did not reach a terminal state")
    }

    private fun awaitTerminalCampaign(campaignId: String): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        do {
            val response = get("/api/campaigns/$campaignId")
            assertEquals(200, response.statusCode())
            if (response.body().jsonValue("status") in setOf("COMPLETED", "FAILED", "RECOVERY_REQUIRED")) {
                return response.body()
            }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Campaign '$campaignId' did not reach a terminal state")
    }

    private fun awaitTerminalAnalysis(analysisRunId: String): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        do {
            val response = get("/api/analysis-runs/$analysisRunId")
            assertEquals(200, response.statusCode())
            if ("\"status\":\"COMPLETED\"" in response.body() || "\"status\":\"FAILED\"" in response.body()) {
                return response.body()
            }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Analysis run '$analysisRunId' did not reach a terminal state")
    }

    private fun postExperiment(key: String, body: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/experiments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postCampaign(key: String, body: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/campaigns"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postAnalysis(experimentRunId: String, key: String, modelKey: String? = null): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/experiments/$experimentRunId/analyses"))
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(modelKey?.let { "{\"modelKey\":\"$it\"}" } ?: ""))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postComparison(
        experimentRunId: String,
        key: String,
        body: String = "{\"modelKeys\":[\"GPT_OSS\",\"QWEN\"]}",
    ): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/experiments/$experimentRunId/analysis-comparisons"))
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postMultiAnalysis(experimentRunId: String, key: String, body: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/experiments/$experimentRunId/multi-analyses"))
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postGroundTruth(datasetId: String, version: String, evidenceId: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/analysis-datasets/$datasetId/ground-truth"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"version\":\"$version\",\"expectedVerdict\":\"PASSED\",\"requiredEvidenceIds\":[\"$evidenceId\"]}",
                    ),
                )
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postComparisonEvaluation(comparisonId: String, groundTruthId: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort/api/analysis-comparisons/$comparisonId/evaluations/$groundTruthId"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(3))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$serverPort$path"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun parameters(stock: Int, requestCount: Int, concurrency: Int, quantity: Int): String =
        """
        {
          "targetSystem": "contract-test-target",
          "type": "STOCK_CONCURRENCY",
          "parameters": {
            "stock": $stock,
            "requestCount": $requestCount,
            "concurrency": $concurrency,
            "quantityPerRequest": $quantity
          }
        }
        """.trimIndent()

    private fun String.jsonValue(name: String): String =
        Regex("\"$name\":\"([^\"]+)\"").find(this)?.groupValues?.get(1)
            ?: throw AssertionError("Response does not contain string field '$name': $this")

    private fun String.jsonValues(name: String): List<String> =
        Regex("\"$name\":\"([^\"]+)\"").findAll(this).map { it.groupValues[1] }.toList()

    companion object {
        private val scenarioExecutions = AtomicInteger(0)
        private val lastScenarioRequest = AtomicReference("")
        private val targetServer: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        @BeforeAll
        @JvmStatic
        fun startTargetContractServer() {
            targetServer.createContext("/actuator/health") { exchange ->
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            targetServer.createContext("/reliability/v1/scenarios/STOCK_CONCURRENCY/executions") { exchange ->
                lastScenarioRequest.set(String(exchange.requestBody.readAllBytes()))
                scenarioExecutions.incrementAndGet()
                val body = """
                    {
                      "operationId":"contract-operation-${scenarioExecutions.get()}",
                      "status":"COMPLETED",
                      "message":"Contract target completed the isolated scenario",
                      "result":{
                        "productId":"fixture-product-${scenarioExecutions.get()}",
                        "successCount":10,
                        "failureCount":40,
                        "oversellCount":0,
                        "finalRedisStock":0,
                        "finalDbStock":0,
                        "durationSeconds":1
                      },
                      "resources":[{
                        "type":"PRODUCT",
                        "id":"fixture-product-${scenarioExecutions.get()}",
                        "namespace":"contract-test"
                      }],
                      "cleanup":{"status":"VERIFIED"},
                      "artifact":{"reference":"target-result-contract","checksum":"test-checksum"}
                    }
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            targetServer.start()
        }

        @AfterAll
        @JvmStatic
        fun stopTargetContractServer() {
            targetServer.stop(0)
        }

        @DynamicPropertySource
        @JvmStatic
        fun targetProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.targets.registrations[0].id") { "contract-test-target" }
            registry.add("arl.targets.registrations[0].name") { "HTTP Scenario Contract Target" }
            registry.add("arl.targets.registrations[0].adapter-type") { "HTTP_TARGET" }
            registry.add("arl.targets.registrations[0].environment") { "LOCAL" }
            registry.add("arl.targets.registrations[0].base-url") { origin() }
            registry.add("arl.targets.registrations[0].allowed-origin") { origin() }
            registry.add("arl.targets.registrations[0].allowed-cidrs[0]") { "127.0.0.0/8" }
            registry.add("arl.targets.registrations[0].health-path") { "/actuator/health" }
            registry.add("arl.targets.registrations[0].source-repository") { "contract-test" }
            registry.add("arl.targets.registrations[0].identity-verification") { "CONFIGURATION_ONLY" }
            registry.add("arl.targets.registrations[0].capabilities[0]") { "HEALTH" }
            registry.add("arl.targets.registrations[0].capabilities[1]") { "HTTP_API" }
            registry.add("arl.targets.registrations[0].capabilities[2]") { "STOCK_CONCURRENCY" }
        }

        private fun origin(): String = "http://127.0.0.1:${targetServer.address.port}"
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeAnalysisModelConfiguration {
        @Bean
        @Primary
        fun fakeReliabilityAnalysisModel(): FakeReliabilityAnalysisModel = FakeReliabilityAnalysisModel()
    }
}

class FakeReliabilityAnalysisModel : ReliabilityAnalysisModel {
    val calls = AtomicInteger(0)
    val modelIds = ConcurrentLinkedQueue<String>()

    @Volatile
    var returnUnknownEvidenceId: Boolean = false

    override fun analyze(request: ReliabilityAnalysisModelRequest): ReliabilityAnalysisModelResponse {
        calls.incrementAndGet()
        modelIds.add(request.modelId)
        val evidenceId = if (returnUnknownEvidenceId) "not-in-the-bundle" else request.evidenceIds.first()
        val multiRole = Regex("You are ARL's ([A-Z]+) role").find(request.systemInstruction)?.groupValues?.get(1)
        if (request.systemInstruction.contains("follow-up test suggestion agent")) {
            return ReliabilityAnalysisModelResponse(
                """{"suggestions":[{"candidateId":"catalog-list","rationale":"The completed health result is grounded but does not cover the registered catalog endpoint.","evidenceIds":["$evidenceId"]}]}""",
                promptTokenCount = 17,
                completionTokenCount = 23,
                durationMillis = 5,
            )
        }
        if (request.systemInstruction.contains("root-cause hypothesis and improvement proposal agent")) {
            return ReliabilityAnalysisModelResponse(
                """{"hypotheses":[{"title":"No fault is evidenced in the completed read-only check","confidence":"MEDIUM","rationale":"The completed analysis and its cited evidence report the registered health check as passed.","falsifiability":"A repeatable failed health result or contradictory evidence would disprove this hypothesis.","evidenceIds":["$evidenceId"]}],"improvementProposals":[{"hypothesisOrdinal":1,"title":"Retain a bounded health regression check","proposedChange":"Keep the registered health check in the target's regression suite.","expectedEffect":"Future availability regressions can be detected with the same bounded evidence contract.","risk":"The recommendation may not cover faults outside the registered health operation.","evidenceIds":["$evidenceId"]}]}""",
                promptTokenCount = 17,
                completionTokenCount = 23,
                durationMillis = 5,
            )
        }
        val multiOutput = when (multiRole) {
            "SUPERVISOR" -> """{"objective":"Review the supplied deterministic reliability evidence.","evidenceIds":["$evidenceId"]}"""
            "PLANNER" -> """{"focusAreas":["Invariant outcome","Recorded evidence"],"evidenceIds":["$evidenceId"]}"""
            "ANALYST" -> """{"observations":["The deterministic evidence reports no oversell."],"proposedVerdict":"PASSED","evidenceIds":["$evidenceId"]}"""
            else -> null
        }
        if (multiOutput != null) {
            return ReliabilityAnalysisModelResponse(
                multiOutput,
                promptTokenCount = 17,
                completionTokenCount = 23,
                durationMillis = 5,
            )
        }
        return ReliabilityAnalysisModelResponse(
            """
            {
              "summary":"The supplied deterministic evidence shows no oversell.",
              "verdict":"PASSED",
              "findings":[{
                "severity":"INFO",
                "title":"Stock accounting is consistent",
                "rationale":"The target reported zero oversell and matching final stock values.",
                "evidenceIds":["$evidenceId"]
              }],
              "recommendations":[{
                "priority":"P3",
                "title":"Retain the regression coverage",
                "recommendedAction":"Keep the current invariant checks in the regression suite.",
                "rationale":"The supplied run passed all deterministic checks.",
                "evidenceIds":["$evidenceId"]
              }]
            }
            """.trimIndent(),
            promptTokenCount = 17,
            completionTokenCount = 23,
            durationMillis = 5,
        )
    }
}
