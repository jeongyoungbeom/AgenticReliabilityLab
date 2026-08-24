package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.infrastructure.JdbcTargetProfileRepository
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecRunStore
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proves the Phase 22-D target-wide regression trigger: batches [TestSpecificationService.execute] over targets. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestSpecRegressionRunApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var profileRepository: JdbcTargetProfileRepository

    @Autowired
    private lateinit var runStore: TestSpecRunStore

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private lateinit var originalProfile: TargetProfileVersion
    private lateinit var executionProfile: TargetProfileVersion

    @BeforeEach
    fun enableTestSpecificationExecution() {
        originalProfile = profileRepository.findActive(TARGET_ID)
            ?: error("Test target must have an active Profile")
        executionProfile = executionProfileFrom(originalProfile)
        assertTrue(profileRepository.createIfAbsent(executionProfile))
        assertTrue(profileRepository.activate(TARGET_ID, executionProfile.id, "phase22d-test", Instant.now()))
    }

    @AfterEach
    fun restoreProfileAndCleanRuns() {
        profileRepository.activate(TARGET_ID, originalProfile.id, "phase22d-test", Instant.now())
        jdbcClient.sql("delete from test_spec_reset_result").update()
        jdbcClient.sql("delete from test_spec_trial_result").update()
        jdbcClient.sql("delete from test_spec_run").update()
        jdbcClient.sql("delete from test_specification").update()
    }

    @Test
    fun `executes every approved specification across distinct specKeys and reports each outcome`() {
        val firstKey = "phase22d-a-${UUID.randomUUID()}"
        val secondKey = "phase22d-b-${UUID.randomUUID()}"
        approve(field(createSpecification(firstKey).body(), "id"))
        approve(field(createSpecification(secondKey).body(), "id"))

        val response = triggerRegressionRuns("phase22d-batch-001")

        assertEquals(200, response.statusCode(), response.body())
        val outcomes = runs(response.body())
        assertEquals(setOf(firstKey, secondKey), outcomes.map { outcome -> outcome["specKey"] }.toSet())
        outcomes.forEach { outcome ->
            assertEquals("1", outcome["version"].toString())
            assertEquals("COMPLETED", run(outcome)["status"])
            assertTrue(outcome["failureCode"] == null && outcome["failureMessage"] == null, response.body())
        }
    }

    @Test
    fun `runs only the highest approved version when a specKey has more than one approved version`() {
        val specKey = "phase22d-multi-${UUID.randomUUID()}"
        approve(field(createSpecification(specKey, version = 1).body(), "id"))
        approve(field(createSpecification(specKey, version = 2).body(), "id"))
        assertEquals(
            2L,
            jdbcClient.sql("select count(*) from test_specification where spec_key = :key and status = 'APPROVED'")
                .param("key", specKey)
                .query(Long::class.java)
                .single(),
        )

        val response = triggerRegressionRuns("phase22d-multi-version")

        val outcomes = runs(response.body()).filter { outcome -> outcome["specKey"] == specKey }
        assertEquals(1, outcomes.size, response.body())
        assertEquals("2", outcomes.single()["version"].toString())
    }

    @Test
    fun `returns an empty result for a target with no approved specifications`() {
        val response = triggerRegressionRuns("phase22d-empty")

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(runs(response.body()).isEmpty(), response.body())
    }

    @Test
    fun `replaying the same Idempotency-Key returns the same runs instead of executing again`() {
        val specKey = "phase22d-replay-${UUID.randomUUID()}"
        approve(field(createSpecification(specKey).body(), "id"))

        val first = triggerRegressionRuns("phase22d-replay-key")
        val second = triggerRegressionRuns("phase22d-replay-key")

        val firstRunId = run(runs(first.body()).single())["id"]
        val secondRunId = run(runs(second.body()).single())["id"]
        assertEquals(firstRunId, secondRunId)
        assertEquals(
            1L,
            jdbcClient.sql("select count(*) from test_spec_run where target_system_id = :target")
                .param("target", TARGET_ID)
                .query(Long::class.java)
                .single(),
        )
    }

    @Test
    fun `reports a per-specification failure without losing the rest of the batch`() {
        val blockedKey = "phase22d-blocked-${UUID.randomUUID()}"
        val specificationId = UUID.fromString(field(createSpecification(blockedKey).body(), "id"))
        approve(specificationId.toString())
        val blockingRun = TestSpecRun(
            id = UUID.randomUUID(),
            specificationId = specificationId,
            targetSystemId = TARGET_ID,
            profileVersionId = executionProfile.id,
            status = TestSpecRunStatus.PENDING,
            idempotencyKey = "phase22d-blocking-${UUID.randomUUID()}",
            requestHash = "c".repeat(64),
            requestedTrials = 1,
            createdBy = "phase22d-test",
            createdCorrelationId = "phase22d-recovery-test",
            createdAt = Instant.now(),
        )
        runStore.create(blockingRun)
        assertTrue(runStore.markFailed(blockingRun.id, true, "Cleanup could not be verified", Instant.now()))
        val otherKey = "phase22d-unblocked-${UUID.randomUUID()}"
        approve(field(createSpecification(otherKey).body(), "id"))

        val response = triggerRegressionRuns("phase22d-partial-failure")

        assertEquals(200, response.statusCode(), response.body())
        val outcomes = runs(response.body())
        assertEquals(2, outcomes.size, response.body())
        outcomes.forEach { outcome ->
            assertTrue(outcome["run"] == null, response.body())
            assertEquals("TEST_SPECIFICATION_RECOVERY_REQUIRED", outcome["failureCode"])
        }
    }

    @Test
    fun `completes one specification while another fails in the same batch (genuine mixed outcome)`() {
        val stableKey = "phase22d-stable-${UUID.randomUUID()}"
        val brokenKey = "phase22d-broken-${UUID.randomUUID()}"
        approve(field(createSpecification(stableKey, trials = 1).body(), "id"))
        approve(field(createSpecification(brokenKey, trials = 2).body(), "id"))

        // Narrows maxTrials to 1: stableKey (trials=1) still validates, brokenKey (trials=2) no longer does,
        // so reconcileProfileVersion() supersedes only brokenKey - a per-specification break, not a target-wide
        // block, unlike the "recovery required" scenario the other failure test above already covers.
        val narrowedProfile = executionProfileFrom(executionProfile, maxTrials = 1)
        assertTrue(profileRepository.createIfAbsent(narrowedProfile))
        assertTrue(profileRepository.activate(TARGET_ID, narrowedProfile.id, "phase22d-test", Instant.now()))

        val response = triggerRegressionRuns("phase22d-mixed-outcome")

        assertEquals(200, response.statusCode(), response.body())
        val outcomes = runs(response.body()).associateBy { outcome -> outcome["specKey"] }
        val stableOutcome = outcomes.getValue(stableKey)
        assertEquals("COMPLETED", run(stableOutcome)["status"], response.body())
        assertTrue(stableOutcome["failureCode"] == null, response.body())
        val brokenOutcome = outcomes.getValue(brokenKey)
        assertTrue(brokenOutcome["run"] == null, response.body())
        assertEquals("TEST_SPECIFICATION_PROFILE_VERSION_INACTIVE", brokenOutcome["failureCode"])
    }

    private fun createSpecification(
        specKey: String,
        version: Int = 1,
        waitMillis: Int = 0,
        trials: Int = 1,
    ): HttpResponse<String> = post(
        "/api/test-specifications",
        objectMapper.writeValueAsString(
            mapOf(
                "targetSystemId" to TARGET_ID,
                "source" to "RULE_GENERATED",
                "document" to specificationDocument(specKey, version, waitMillis, trials),
            ),
        ),
    )

    private fun specificationDocument(specKey: String, version: Int, waitMillis: Int, trials: Int): Map<String, Any> =
        mapOf(
            "specKey" to specKey,
            "version" to version,
            "title" to "Phase 22-D regression fixture v$version",
            "category" to "CONSISTENCY",
            "risk" to "SAFE",
            "workload" to listOf(mapOf("kind" to "WAIT", "name" to "settle", "duration" to waitMillis)),
            "observations" to listOf(
                mapOf("id" to "responseCount", "source" to "RESPONSES", "expr" to "count(work[*].status)"),
            ),
            "invariants" to listOf(
                mapOf(
                    "id" to "noUnexpectedResponse",
                    "description" to "No response was expected from a wait-only workload",
                    "condition" to "responseCount == 0",
                ),
            ),
            "policy" to mapOf(
                "trials" to trials,
                "aggregation" to "ANY_VIOLATION_FAILS",
                "stopPolicy" to "STOP_ON_FIRST_VIOLATION",
                "cleanupTiming" to "AFTER_ALL",
                "interval" to 0,
            ),
            "cleanup" to mapOf("method" to "NOT_REQUIRED"),
        )

    private fun executionProfileFrom(base: TargetProfileVersion, maxTrials: Int = 3): TargetProfileVersion = base.copy(
        id = UUID.randomUUID(),
        status = TargetProfileStatus.DRAFT,
        checksum = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", ""),
        definition = base.definition.copy(
            testSpecExecution = TestSpecExecutionProfileDefinition(
                executionEnabled = true,
                allowedCalls = emptyList(),
                authProfiles = emptySet(),
                observationSources = emptyList(),
                supportedFaults = emptySet(),
                infrastructureTargets = emptySet(),
                maxConcurrency = 4,
                maxRequestCount = 10,
                maxTrials = maxTrials,
                stateChangingAllowed = false,
                reset = null,
            ),
        ),
        activatedBy = null,
        activatedAt = null,
    )

    private fun approve(specificationId: String): HttpResponse<String> = post(
        "/api/test-specifications/$specificationId/approve",
        """{"confirmation":"$SAFE_CONFIRMATION"}""",
    )

    private fun triggerRegressionRuns(idempotencyKey: String): HttpResponse<String> =
        post("/api/targets/$TARGET_ID/test-specifications/regression-runs", null, idempotencyKey)

    private fun post(path: String, body: String?, idempotencyKey: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
        idempotencyKey?.let { key -> builder.header("Idempotency-Key", key) }
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        return httpClient.send(builder.POST(publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun field(body: String, name: String): String =
        objectMapper.readValue(body, Map::class.java)[name] as String

    @Suppress("UNCHECKED_CAST")
    private fun runs(body: String): List<Map<String, Any?>> =
        objectMapper.readValue(body, Map::class.java)["runs"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun run(outcome: Map<String, Any?>): Map<String, Any?> = outcome["run"] as Map<String, Any?>

    private companion object {
        const val TARGET_ID = "contract-test-target"
        const val SAFE_CONFIRMATION = "APPROVE_SAFE_TEST_SPECIFICATION"
    }
}
