package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.infrastructure.JdbcTargetProfileRepository
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecRunStore
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecificationStore
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Proves the Phase 17 registration -> approval -> Runner -> persistence HTTP slice on H2. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
open class TestSpecificationApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var profileRepository: JdbcTargetProfileRepository

    @Autowired
    private lateinit var specificationStore: TestSpecificationStore

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
        assertTrue(profileRepository.activate(TARGET_ID, executionProfile.id, "phase17-test", Instant.now()))
    }

    @AfterEach
    fun restoreProfileAndCleanRuns() {
        profileRepository.activate(TARGET_ID, originalProfile.id, "phase17-test", Instant.now())
        jdbcClient.sql("delete from test_spec_reset_result").update()
        jdbcClient.sql("delete from test_spec_trial_result").update()
        jdbcClient.sql("delete from test_spec_run").update()
        jdbcClient.sql("delete from test_specification").update()
    }

    @Test
    fun `registers approves executes and returns the same run for an idempotent retry`() {
        val created = createSpecification()
        assertEquals(201, created.statusCode(), created.body())
        assertContains(created.body(), "\"status\":\"PENDING_APPROVAL\"")
        assertContains(created.body(), "\"requiredConfirmation\":\"$SAFE_CONFIRMATION\"")
        val specificationId = field(created.body(), "id")

        val wrongApproval = post(
            "/api/test-specifications/$specificationId/approve",
            """{"confirmation":"YES"}""",
        )
        assertEquals(400, wrongApproval.statusCode(), wrongApproval.body())

        val approved = approve(specificationId)
        assertEquals(200, approved.statusCode(), approved.body())
        assertContains(approved.body(), "\"status\":\"APPROVED\"")

        val executed = execute(specificationId, "phase17-run-001")
        assertEquals(201, executed.statusCode(), executed.body())
        assertContains(executed.body(), "\"status\":\"COMPLETED\"")
        assertContains(executed.body(), "\"resultOutcome\":\"INCONCLUSIVE\"")
        assertContains(executed.body(), "\"cleanupVerified\":true")
        val runId = field(executed.body(), "id")

        val retried = execute(specificationId, "phase17-run-001")
        assertEquals(runId, field(retried.body(), "id"))
        val fetched = get("/api/test-spec-runs/$runId")
        assertEquals(200, fetched.statusCode(), fetched.body())
        assertContains(fetched.body(), "\"notEvaluatedReason\":\"OBSERVATION_MISSING\"")
    }

    @Test
    fun `refuses an unapproved specification and conflicting reuse of a run idempotency key`() {
        val firstId = field(createSpecification().body(), "id")
        val unapproved = execute(firstId, "phase17-unapproved")
        assertEquals(409, unapproved.statusCode(), unapproved.body())
        assertContains(unapproved.body(), "TEST_SPECIFICATION_NOT_APPROVED")

        approve(firstId)
        val firstRun = execute(firstId, "phase17-shared-key")
        assertEquals(201, firstRun.statusCode(), firstRun.body())

        val secondId = field(createSpecification().body(), "id")
        approve(secondId)
        val conflicting = execute(secondId, "phase17-shared-key")
        assertEquals(409, conflicting.statusCode(), conflicting.body())
        assertContains(conflicting.body(), "TEST_SPECIFICATION_RUN_IDEMPOTENCY_CONFLICT")
    }

    @Test
    fun `supersedes and blocks a specification when a Profile Version bump breaks a reference it relies on`() {
        val specificationId = field(createSpecification().body(), "id")
        approve(specificationId)
        // maxTrials = 0 genuinely breaks this fixture's policy.trials = 1 - the only Profile-derived
        // constraint this WAIT-only specification actually depends on. A capability-identical bump must
        // NOT supersede (see the test below); only a bump that breaks a reference should.
        val replacement = executionProfileFrom(executionProfile, maxTrials = 0)
        assertTrue(profileRepository.createIfAbsent(replacement))
        assertTrue(profileRepository.activate(TARGET_ID, replacement.id, "phase17-test", Instant.now()))

        val response = execute(specificationId, "phase17-inactive-profile")

        assertEquals(409, response.statusCode(), response.body())
        assertContains(response.body(), "TEST_SPECIFICATION_PROFILE_VERSION_INACTIVE")
        val stored = get("/api/test-specifications/$specificationId")
        assertContains(stored.body(), "\"status\":\"SUPERSEDED\"")
        assertContains(stored.body(), "\"profileVersionActive\":false")
    }

    @Test
    fun `keeps a specification approved and executable across a compatible Profile Version bump`() {
        val specificationId = field(createSpecification().body(), "id")
        approve(specificationId)
        val approvedView = get("/api/test-specifications/$specificationId")
        val originalProfileVersionId = field(approvedView.body(), "profileVersionId")

        // Same capabilities as the currently active profile - nothing this specification references changed.
        val compatibleBump = executionProfileFrom(executionProfile)
        assertTrue(profileRepository.createIfAbsent(compatibleBump))
        assertTrue(profileRepository.activate(TARGET_ID, compatibleBump.id, "phase17-test", Instant.now()))

        val response = execute(specificationId, "phase17-compatible-bump")

        assertEquals(201, response.statusCode(), response.body())
        assertContains(response.body(), "\"status\":\"COMPLETED\"")
        val stored = get("/api/test-specifications/$specificationId")
        assertContains(stored.body(), "\"status\":\"APPROVED\"")
        assertContains(stored.body(), "\"profileVersionActive\":true")
        val revisedProfileVersionId = field(stored.body(), "profileVersionId")
        assertEquals(compatibleBump.id.toString(), revisedProfileVersionId)
        assertTrue(revisedProfileVersionId != originalProfileVersionId)
    }

    @Test
    fun `blocks a new execution while an earlier run still requires recovery`() {
        val specificationId = UUID.fromString(field(createSpecification().body(), "id"))
        approve(specificationId.toString())
        val specification = assertNotNull(specificationStore.findById(specificationId))
        val failedRun = TestSpecRun(
            id = UUID.randomUUID(),
            specificationId = specification.id,
            targetSystemId = TARGET_ID,
            profileVersionId = specification.profileVersionId,
            status = TestSpecRunStatus.PENDING,
            idempotencyKey = "earlier-run-${UUID.randomUUID()}",
            requestHash = "a".repeat(64),
            requestedTrials = 1,
            createdBy = "phase17-test",
            createdCorrelationId = "phase17-recovery-test",
            createdAt = Instant.now(),
        )
        runStore.create(failedRun)
        assertTrue(runStore.markFailed(failedRun.id, true, "Cleanup could not be verified", Instant.now()))

        val response = execute(specificationId.toString(), "phase17-blocked-by-recovery")

        assertEquals(409, response.statusCode(), response.body())
        assertContains(response.body(), "TEST_SPECIFICATION_RECOVERY_REQUIRED")
    }

    @Test
    fun `blocks a new execution while an earlier claimed run is still running`() {
        val specificationId = UUID.fromString(field(createSpecification().body(), "id"))
        approve(specificationId.toString())
        val specification = assertNotNull(specificationStore.findById(specificationId))
        val running = TestSpecRun(
            id = UUID.randomUUID(),
            specificationId = specification.id,
            targetSystemId = TARGET_ID,
            profileVersionId = specification.profileVersionId,
            status = TestSpecRunStatus.PENDING,
            idempotencyKey = "orphaned-run-${UUID.randomUUID()}",
            requestHash = "b".repeat(64),
            requestedTrials = 1,
            createdBy = "phase17-test",
            createdCorrelationId = "phase17-running-test",
            createdAt = Instant.now(),
        )
        runStore.create(running)
        assertTrue(runStore.markRunning(running.id, Instant.now()))

        val response = execute(specificationId.toString(), "phase17-blocked-by-running")

        assertEquals(409, response.statusCode(), response.body())
        assertContains(response.body(), "TEST_SPECIFICATION_RECOVERY_REQUIRED")
    }

    @Test
    fun `lists every specification for a target across specKeys and statuses`() {
        val pendingId = field(createSpecification().body(), "id")
        val approvedId = field(createSpecification().body(), "id")
        approve(approvedId)

        val listed = get("/api/targets/$TARGET_ID/test-specifications")

        assertEquals(200, listed.statusCode(), listed.body())
        val ids = objectMapper.readValue(listed.body(), List::class.java)
            .map { entry -> (entry as Map<*, *>)["id"] as String }
        assertContains(ids, pendingId)
        assertContains(ids, approvedId)
    }

    @Test
    fun `returns an empty list for a target with no specifications`() {
        val listed = get("/api/targets/no-such-target-${UUID.randomUUID()}/test-specifications")

        assertEquals(200, listed.statusCode(), listed.body())
        assertEquals(emptyList<Any>(), objectMapper.readValue(listed.body(), List::class.java))
    }

    @Test
    fun `rejects an oversized specification before materializing its document`() {
        val response = post("/api/test-specifications", "x".repeat(OVERSIZED_PAYLOAD_BYTES))

        assertEquals(413, response.statusCode(), response.body())
    }

    @Test
    fun `executes one run when the same idempotency key arrives concurrently`() {
        val specificationId = field(createSpecification(WAIT_FOR_OVERLAP_MILLIS).body(), "id")
        approve(specificationId)

        val responses = concurrently { execute(specificationId, "phase17-concurrent-run") }
        val bodies = responses.joinToString { response -> "${response.statusCode()} ${response.body()}" }

        assertTrue(responses.none { response -> response.statusCode() >= 500 }, bodies)
        assertEquals(1, responses.map { response -> field(response.body(), "id") }.distinct().size, bodies)
        assertEquals(
            1L,
            jdbcClient.sql("select count(*) from test_spec_run where idempotency_key = :key")
                .param("key", "phase17-concurrent-run")
                .query(Long::class.java)
                .single(),
        )
    }

    private fun createSpecification(waitMillis: Int = 0): HttpResponse<String> = post(
        "/api/test-specifications",
        objectMapper.writeValueAsString(
            mapOf(
                "targetSystemId" to TARGET_ID,
                "source" to "RULE_GENERATED",
                "document" to specificationDocument(waitMillis),
            ),
        ),
    )

    private fun specificationDocument(waitMillis: Int): Map<String, Any> = mapOf(
        "specKey" to "phase17-api-${UUID.randomUUID()}",
        "version" to 1,
        "title" to "Deterministic Phase 17 API slice",
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
            "trials" to 1,
            "aggregation" to "ANY_VIOLATION_FAILS",
            "stopPolicy" to "STOP_ON_FIRST_VIOLATION",
            "cleanupTiming" to "AFTER_ALL",
            "interval" to 0,
        ),
        "cleanup" to mapOf("method" to "NOT_REQUIRED"),
    )

    private fun executionProfileFrom(
        base: TargetProfileVersion,
        maxTrials: Int = 3,
    ): TargetProfileVersion = base.copy(
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

    private fun execute(specificationId: String, idempotencyKey: String): HttpResponse<String> =
        post("/api/test-specifications/$specificationId/runs", null, idempotencyKey)

    private fun concurrently(request: () -> HttpResponse<String>): List<HttpResponse<String>> {
        val executor = Executors.newFixedThreadPool(CONCURRENT_CALLS)
        val ready = CountDownLatch(CONCURRENT_CALLS)
        val start = CountDownLatch(1)
        return try {
            val futures = (1..CONCURRENT_CALLS).map {
                executor.submit<HttpResponse<String>> {
                    ready.countDown()
                    start.await()
                    request()
                }
            }
            ready.await()
            start.countDown()
            futures.map { future -> future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun get(path: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

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

    private companion object {
        const val TARGET_ID = "contract-test-target"
        const val SAFE_CONFIRMATION = "APPROVE_SAFE_TEST_SPECIFICATION"
        const val CONCURRENT_CALLS = 2
        const val CALL_TIMEOUT_SECONDS = 10L
        const val WAIT_FOR_OVERLAP_MILLIS = 200
        const val OVERSIZED_PAYLOAD_BYTES = 300_000
    }
}
