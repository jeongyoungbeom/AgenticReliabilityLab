package com.project.agenticreliabilitylab.targetspec.api

import com.project.agenticreliabilitylab.experiment.api.ExperimentApiIntegrationTests
import com.project.agenticreliabilitylab.experiment.infrastructure.JdbcWorkloadLeaseRepository
import com.project.agenticreliabilitylab.analysis.application.RootCauseReportService
import com.project.agenticreliabilitylab.analysis.domain.RootCauseReportStatus
import com.project.agenticreliabilitylab.analysis.infrastructure.JdbcRootCauseReportRepository
import com.project.agenticreliabilitylab.analysis.application.port.NewRootCauseReportRun
import com.project.agenticreliabilitylab.analysis.infrastructure.RootCauseReportProperties
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.infrastructure.JdbcTargetSystemRepository
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
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
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ExperimentApiIntegrationTests.FakeAnalysisModelConfiguration::class)
class TargetTestBatchApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    @Autowired
    private lateinit var workloadLeaseRepository: JdbcWorkloadLeaseRepository

    @Autowired
    private lateinit var targetSystemRepository: JdbcTargetSystemRepository

    @Autowired
    private lateinit var rootCauseReportService: RootCauseReportService

    @Autowired
    private lateinit var rootCauseReportRepository: JdbcRootCauseReportRepository

    @Autowired
    private lateinit var rootCauseReportProperties: RootCauseReportProperties

    @Test
    fun `generates candidates then executes multiple approved read-only checks as a batch`() {
        val candidates = get("/api/targets/contract-test-target/test-candidates")

        assertEquals(200, candidates.statusCode())
        assertContains(candidates.body(), "health-reachability")
        assertContains(candidates.body(), "catalog-list")

        val created = post(
            "/api/test-batches",
            """{"targetSystemId":"contract-test-target","candidateIds":["health-reachability","catalog-list"]}""",
            mapOf("Idempotency-Key" to "generic-batch-001"),
        )
        assertEquals(202, created.statusCode())
        assertContains(created.body(), "PENDING_APPROVAL")
        val batchId = """"id":"([^"]+)""".toRegex().find(created.body())?.groupValues?.get(1)
            ?: error("Target test batch id was absent")

        val approved = post(
            "/api/test-batches/$batchId/approve",
            """{"confirmation":"EXECUTE_SAFE_HTTP_BATCH"}""",
        )
        assertEquals(202, approved.statusCode())

        val completed = awaitBatch(batchId)
        assertContains(completed, "\"status\":\"COMPLETED\"")
        assertContains(completed, "\"status\":\"PASSED\"")
        assertContains(completed, "responseSha256")
        assertFalse(completed.contains("sensitive-like-payload"))

        val analysis = post(
            "/api/test-batches/$batchId/analyses",
            "{\"modelKey\":\"GPT_OSS\"}",
            mapOf("Idempotency-Key" to "generic-batch-analysis-001"),
        )
        assertEquals(202, analysis.statusCode())
        assertContains(analysis.body(), "\"targetTestBatchId\":\"$batchId\"")
        val analysisRunId = """"id":"([^"]+)""".toRegex().find(analysis.body())?.groupValues?.get(1)
            ?: error("Analysis run id was absent")
        val completedAnalysis = awaitAnalysis(analysisRunId)
        assertContains(completedAnalysis, "\"status\":\"COMPLETED\"")
        assertContains(completedAnalysis, "\"inputEvidenceCount\":2")
        assertFalse(completedAnalysis.contains("sensitive-like-payload"))

        val comparison = post(
            "/api/test-batches/$batchId/analysis-comparisons",
            "{\"modelKeys\":[\"GPT_OSS\",\"QWEN\"]}",
            mapOf("Idempotency-Key" to "generic-batch-comparison-001"),
        )
        assertEquals(202, comparison.statusCode())
        assertContains(comparison.body(), "\"targetTestBatchId\":\"$batchId\"")
        assertContains(comparison.body(), "\"modelKeys\":[\"GPT_OSS\",\"QWEN\"]")

        val multi = post(
            "/api/test-batches/$batchId/multi-analyses",
            "{\"modelKey\":\"QWEN\"}",
            mapOf("Idempotency-Key" to "generic-batch-multi-001"),
        )
        assertEquals(202, multi.statusCode())
        assertContains(multi.body(), "\"agentType\":\"MULTI_RELIABILITY_AGENT\"")
        val multiAnalysisRunId = """"id":"([^"]+)""".toRegex().find(multi.body())?.groupValues?.get(1)
            ?: error("Multi-agent analysis run id was absent")
        val completedMultiAnalysis = awaitAnalysis(multiAnalysisRunId)
        assertContains(completedMultiAnalysis, "\"status\":\"COMPLETED\"")
        val multiDetails = get("/api/multi-analysis-runs/$multiAnalysisRunId")
        assertEquals(200, multiDetails.statusCode())
        assertEquals(4, Regex("\"toolPolicy\":\"NO_TOOLS\"").findAll(multiDetails.body()).count())
    }

    @Test
    fun `does not issue generic target requests while the shared workload lease is owned`() {
        val now = Instant.now()
        val lease = requireNotNull(workloadLeaseRepository.tryAcquire(
            hostResourceGroup = "contract-test-resource",
            ownerId = "test-blocker",
            leaseOwner = "test-blocker",
            now = now,
            expiresAt = now.plusSeconds(30),
        ))
        assertNotNull(lease)
        try {
            val created = post(
                "/api/test-batches",
                """{"targetSystemId":"contract-test-target","candidateIds":["health-reachability"]}""",
                mapOf("Idempotency-Key" to "generic-batch-lease-blocked"),
            )
            assertEquals(202, created.statusCode())
            val batchId = """"id":"([^"]+)""".toRegex().find(created.body())?.groupValues?.get(1)
                ?: error("Target test batch id was absent")
            assertEquals(
                202,
                post("/api/test-batches/$batchId/approve", """{"confirmation":"EXECUTE_SAFE_HTTP_BATCH"}""").statusCode(),
            )
            val failed = awaitBatchStatus(batchId, "FAILED")
            assertContains(failed, "WORKLOAD_LEASE_UNAVAILABLE")
        } finally {
            workloadLeaseRepository.release(lease)
        }
    }

    @Test
    fun `suggests a registered follow-up candidate without creating or executing a batch`() {
        val created = post(
            "/api/test-batches",
            """{"targetSystemId":"contract-test-target","candidateIds":["health-reachability"]}""",
            mapOf("Idempotency-Key" to "phase7-follow-up-batch-${Instant.now().toEpochMilli()}"),
        )
        assertEquals(202, created.statusCode(), created.body())
        val batchId = """"id":"([^"]+)""".toRegex().find(created.body())?.groupValues?.get(1) ?: error("Batch ID was absent")
        assertEquals(202, post("/api/test-batches/$batchId/approve", """{"confirmation":"EXECUTE_SAFE_HTTP_BATCH"}""").statusCode())
        awaitBatch(batchId)

        val analysis = post(
            "/api/test-batches/$batchId/analyses",
            "{\"modelKey\":\"GPT_OSS\"}",
            mapOf("Idempotency-Key" to "phase7-follow-up-analysis-${Instant.now().toEpochMilli()}"),
        )
        assertEquals(202, analysis.statusCode(), analysis.body())
        val analysisRunId = """"id":"([^"]+)""".toRegex().find(analysis.body())?.groupValues?.get(1) ?: error("Analysis run ID was absent")
        awaitAnalysis(analysisRunId)

        val suggestion = post(
            "/api/analysis-runs/$analysisRunId/follow-up-test-suggestions",
            "{\"modelKey\":\"GPT_OSS\"}",
            mapOf("Idempotency-Key" to "phase7-follow-up-suggestion-${Instant.now().toEpochMilli()}"),
        )
        assertEquals(202, suggestion.statusCode(), suggestion.body())
        val suggestionRunId = """"id":"([^"]+)""".toRegex().find(suggestion.body())?.groupValues?.get(1) ?: error("Suggestion run ID was absent")
        val completed = awaitSuggestion(suggestionRunId)
        assertContains(completed, "\"status\":\"COMPLETED\"")
        assertContains(completed, "\"candidateId\":\"catalog-list\"")
        assertContains(completed, "\"candidateTitle\":\"Catalog listing\"")
        assertFalse(completed.contains("sensitive-like-payload"))
    }

    @Test
    fun `stores and approves a local failure injection plan without any execution capability`() {
        val candidates = get("/api/targets/contract-test-target/failure-injection-candidates")
        assertEquals(200, candidates.statusCode(), candidates.body())
        assertContains(candidates.body(), "consumer-restart")
        assertContains(candidates.body(), "MODERATE")

        val created = post(
            "/api/failure-injection-plans",
            """{"targetSystemId":"contract-test-target","candidateIds":["consumer-restart"]}""",
            mapOf("Idempotency-Key" to "phase8-plan-${Instant.now().toEpochMilli()}"),
        )
        assertEquals(202, created.statusCode(), created.body())
        assertContains(created.body(), "\"status\":\"PENDING_APPROVAL\"")
        assertContains(created.body(), "\"executionAvailable\":false")
        val planId = """"id":"([^"]+)""".toRegex().find(created.body())?.groupValues?.get(1) ?: error("Plan ID was absent")

        val approved = post(
            "/api/failure-injection-plans/$planId/approve",
            """{"confirmation":"APPROVE_FAILURE_INJECTION_PLAN_ONLY"}""",
        )
        assertEquals(202, approved.statusCode(), approved.body())
        assertContains(approved.body(), "\"status\":\"APPROVED\"")
        assertContains(approved.body(), "\"executionAvailable\":false")
    }

    @Test
    fun `blocks existing failure injection plans after the target changes to staging`() {
        val created = post(
            "/api/failure-injection-plans",
            """{"targetSystemId":"contract-test-target","candidateIds":["consumer-restart"]}""",
            mapOf("Idempotency-Key" to "phase8-staging-${Instant.now().toEpochMilli()}"),
        )
        assertEquals(202, created.statusCode(), created.body())
        val planId = """"id":"([^"]+)"""".toRegex().find(created.body())?.groupValues?.get(1) ?: error("Plan ID was absent")
        val target = targetSystemRepository.findById("contract-test-target") ?: error("Target was absent")
        targetSystemRepository.upsert(target.copy(environment = TargetEnvironment.STAGING, updatedAt = Instant.now()))
        try {
            assertEquals(400, get("/api/failure-injection-plans/$planId").statusCode())
            assertEquals(
                400,
                post(
                    "/api/failure-injection-plans/$planId/approve",
                    """{"confirmation":"APPROVE_FAILURE_INJECTION_PLAN_ONLY"}""",
                ).statusCode(),
            )
        } finally {
            targetSystemRepository.upsert(target)
        }
    }

    @Test
    fun `returns evidence-grounded root cause hypotheses and advisory improvements without implementation capability`() {
        val batch = post(
            "/api/test-batches",
            """{"targetSystemId":"contract-test-target","candidateIds":["health-reachability"]}""",
            mapOf("Idempotency-Key" to "phase9-root-cause-batch-${Instant.now().toEpochMilli()}"),
        )
        assertEquals(202, batch.statusCode(), batch.body())
        val batchId = """"id":"([^"]+)"""".toRegex().find(batch.body())?.groupValues?.get(1) ?: error("Batch ID was absent")
        assertEquals(202, post("/api/test-batches/$batchId/approve", """{"confirmation":"EXECUTE_SAFE_HTTP_BATCH"}""").statusCode())
        awaitBatch(batchId)

        val analysis = post(
            "/api/test-batches/$batchId/analyses",
            """{"modelKey":"GPT_OSS"}""",
            mapOf("Idempotency-Key" to "phase9-root-cause-analysis-${Instant.now().toEpochMilli()}"),
        )
        assertEquals(202, analysis.statusCode(), analysis.body())
        val analysisRunId = """"id":"([^"]+)"""".toRegex().find(analysis.body())?.groupValues?.get(1) ?: error("Analysis run ID was absent")
        awaitAnalysis(analysisRunId)

        val report = post(
            "/api/analysis-runs/$analysisRunId/root-cause-reports",
            """{"modelKey":"GPT_OSS"}""",
            mapOf("Idempotency-Key" to "phase9-root-cause-report-${Instant.now().toEpochMilli()}"),
        )
        assertEquals(202, report.statusCode(), report.body())
        val reportId = """"id":"([^"]+)"""".toRegex().find(report.body())?.groupValues?.get(1) ?: error("Report ID was absent")
        val completed = awaitRootCauseReport(reportId)
        assertContains(completed, "\"status\":\"COMPLETED\"")
        assertContains(completed, "No fault is evidenced in the completed read-only check")
        assertContains(completed, "Retain a bounded health regression check")
        assertContains(completed, "\"implementationAvailable\":false")
        assertContains(completed, "\"outputChecksum\":")
    }

    @Test
    fun `does not resume requested root cause reports while the feature is disabled`() {
        val analysisRunId = createCompletedAnalysisForRootCause("disabled")
        val reportId = persistRootCauseReport(analysisRunId, "disabled")
        rootCauseReportProperties.enabled = false
        try {
            rootCauseReportService.recoverIncompleteRuns()
            assertEquals(
                RootCauseReportStatus.REQUESTED,
                rootCauseReportRepository.findById(UUID.fromString(reportId))?.status,
            )
        } finally {
            rootCauseReportProperties.enabled = true
            rootCauseReportRepository.fail(
                UUID.fromString(reportId),
                "TEST_CLEANUP",
                "Disabled recovery test cleanup",
                Instant.now(),
            )
        }
    }

    @Test
    fun `fails stale running reports before it schedules pending root cause reports during recovery`() {
        val analysisRunId = createCompletedAnalysisForRootCause("recovery")
        val staleReportId = persistRootCauseReport(analysisRunId, "stale")
        val pendingReportId = persistRootCauseReport(analysisRunId, "pending")
        assertEquals(true, rootCauseReportRepository.claim(UUID.fromString(staleReportId), Instant.now()))

        rootCauseReportService.recoverIncompleteRuns()

        assertEquals(
            RootCauseReportStatus.FAILED,
            rootCauseReportRepository.findById(UUID.fromString(staleReportId))?.status,
        )
        val completed = awaitRootCauseReport(pendingReportId)
        assertContains(completed, "\"status\":\"COMPLETED\"")
        assertContains(completed, "\"outputChecksum\":")
    }

    private fun awaitBatch(batchId: String): String {
        return awaitBatchStatus(batchId, "COMPLETED")
    }

    private fun awaitBatchStatus(batchId: String, status: String): String {
        repeat(80) {
            val response = get("/api/test-batches/$batchId")
            if (response.statusCode() == 200 && response.body().contains("\"status\":\"$status\"")) {
                return response.body()
            }
            Thread.sleep(25)
        }
        error("Target test batch '$batchId' did not reach $status")
    }

    private fun awaitAnalysis(analysisRunId: String): String {
        repeat(80) {
            val response = get("/api/analysis-runs/$analysisRunId")
            if (response.statusCode() == 200 && response.body().contains("\"status\":\"COMPLETED\"")) {
                return response.body()
            }
            Thread.sleep(25)
        }
        error("Analysis run '$analysisRunId' did not complete")
    }

    private fun awaitSuggestion(suggestionRunId: String): String {
        repeat(80) {
            val response = get("/api/follow-up-test-suggestion-runs/$suggestionRunId")
            if (response.statusCode() == 200 && (response.body().contains("\"status\":\"COMPLETED\"") || response.body().contains("\"status\":\"FAILED\""))) {
                return response.body()
            }
            Thread.sleep(25)
        }
        error("Follow-up suggestion '$suggestionRunId' did not complete")
    }

    private fun awaitRootCauseReport(reportId: String): String {
        repeat(80) {
            val response = get("/api/root-cause-reports/$reportId")
            if (response.statusCode() == 200 && (response.body().contains("\"status\":\"COMPLETED\"") || response.body().contains("\"status\":\"FAILED\""))) {
                return response.body()
            }
            Thread.sleep(25)
        }
        error("Root-cause report '$reportId' did not complete")
    }

    private fun createCompletedAnalysisForRootCause(label: String): String {
        val unique = UUID.randomUUID().toString()
        val batch = post(
            "/api/test-batches",
            """{"targetSystemId":"contract-test-target","candidateIds":["health-reachability"]}""",
            mapOf("Idempotency-Key" to "phase9-$label-batch-$unique"),
        )
        assertEquals(202, batch.statusCode(), batch.body())
        val batchId = """"id":"([^"]+)"""".toRegex().find(batch.body())?.groupValues?.get(1) ?: error("Batch ID was absent")
        assertEquals(202, post("/api/test-batches/$batchId/approve", """{"confirmation":"EXECUTE_SAFE_HTTP_BATCH"}""").statusCode())
        awaitBatch(batchId)
        val analysis = post(
            "/api/test-batches/$batchId/analyses",
            """{"modelKey":"GPT_OSS"}""",
            mapOf("Idempotency-Key" to "phase9-$label-analysis-$unique"),
        )
        assertEquals(202, analysis.statusCode(), analysis.body())
        val analysisRunId = """"id":"([^"]+)"""".toRegex().find(analysis.body())?.groupValues?.get(1) ?: error("Analysis run ID was absent")
        awaitAnalysis(analysisRunId)
        return analysisRunId
    }

    private fun persistRootCauseReport(analysisRunId: String, label: String): String {
        val id = UUID.randomUUID()
        rootCauseReportRepository.create(
            NewRootCauseReportRun(
                id,
                UUID.fromString(analysisRunId),
                "phase9-$label-$id",
                "test-configuration-hash",
                "GPT_OSS",
                "fake-gpt-oss",
                "root-cause-report-v1", "{}", "test-input-checksum", Instant.now(),
            ),
        )
        return id.toString()
    }

    private fun get(path: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer executor-test-token")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer executor-test-token")
        headers.forEach { (name, value) -> request.header(name, value) }
        return httpClient.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    companion object {
        private val targetServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
            server.createContext("/actuator/health") { exchange ->
                exchange.respond(200, "{\"status\":\"UP\"}")
            }
            server.createContext("/catalog") { exchange ->
                check(exchange.requestURI.rawQuery == null)
                exchange.respond(200, "{\"payload\":\"sensitive-like-payload\"}")
            }
            server.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun targetProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.access.mode") { "SECURED" }
            registry.add("arl.access.viewer-token") { "viewer-test-token" }
            registry.add("arl.access.profile-editor-token") { "profile-editor-test-token" }
            registry.add("arl.access.executor-token") { "executor-test-token" }
            val origin = "http://127.0.0.1:${targetServer.address.port}"
            registry.add("arl.targets.registrations[0].id") { "contract-test-target" }
            registry.add("arl.targets.registrations[0].name") { "Contract Test Target" }
            registry.add("arl.targets.registrations[0].adapter-type") { "HTTP_TARGET" }
            registry.add("arl.targets.registrations[0].environment") { "TEST" }
            registry.add("arl.targets.registrations[0].base-url") { origin }
            registry.add("arl.targets.registrations[0].allowed-origin") { origin }
            registry.add("arl.targets.registrations[0].allowed-cidrs[0]") { "127.0.0.0/8" }
            registry.add("arl.targets.registrations[0].health-path") { "/actuator/health" }
            registry.add("arl.targets.registrations[0].source-repository") { "contract-test-target" }
            registry.add("arl.targets.registrations[0].identity-verification") { "CONFIGURATION_ONLY" }
            registry.add("arl.targets.registrations[0].capabilities[0]") { "HEALTH" }
            registry.add("arl.targets.registrations[0].capabilities[1]") { "HTTP_API" }
            registry.add("arl.targets.registrations[0].enabled") { true }
            registry.add("arl.target-specs.registrations[0].target-system-id") { "contract-test-target" }
            registry.add("arl.target-specs.registrations[0].execution-enabled") { true }
            registry.add("arl.target-specs.registrations[0].host-resource-group") { "contract-test-resource" }
            registry.add("arl.target-specs.registrations[0].max-batch-size") { 3 }
            registry.add("arl.target-specs.registrations[0].request-timeout") { "2s" }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].id") { "catalog-list" }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].title") { "Catalog listing" }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].description") { "Catalog endpoint responds to a bounded read request." }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].path") { "/catalog" }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].expected-status-codes[0]") { 200 }
            registry.add("arl.target-specs.registrations[0].failure-injection-planning-enabled") { true }
            registry.add("arl.target-specs.registrations[0].failure-injection-candidates[0].id") { "consumer-restart" }
            registry.add("arl.target-specs.registrations[0].failure-injection-candidates[0].type") { "CONSUMER_RESTART" }
            registry.add("arl.target-specs.registrations[0].failure-injection-candidates[0].risk") { "MODERATE" }
            registry.add("arl.target-specs.registrations[0].failure-injection-candidates[0].title") { "Restart the registered consumer" }
            registry.add("arl.target-specs.registrations[0].failure-injection-candidates[0].description") { "Plan a controlled consumer restart." }
            registry.add("arl.target-specs.registrations[0].failure-injection-candidates[0].recovery-expectation") { "Consumer returns to healthy state and processes the next registered message." }
        }

        @JvmStatic
        @AfterAll
        fun stopTargetServer() {
            targetServer.stop(0)
        }

        private fun com.sun.net.httpserver.HttpExchange.respond(status: Int, body: String) {
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
