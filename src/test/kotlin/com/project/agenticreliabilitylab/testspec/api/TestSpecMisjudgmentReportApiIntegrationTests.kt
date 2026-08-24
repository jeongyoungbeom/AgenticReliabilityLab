package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.infrastructure.JdbcTargetProfileRepository
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecRunStore
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecificationStore
import com.project.agenticreliabilitylab.testspec.domain.InvariantOutcome
import com.project.agenticreliabilitylab.testspec.domain.InvariantVerdict
import com.project.agenticreliabilitylab.testspec.domain.SpecRunOutcome
import com.project.agenticreliabilitylab.testspec.domain.SpecificationResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import com.project.agenticreliabilitylab.testspec.domain.TrialResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 22-C: a reviewer's misjudgment claim is only ever promoted through the same `create`/`approve` gate every
 * other specification uses - including the Phase 22-B nullification check, proven here by composition rather than
 * by re-testing 22-B's own rules.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSpecMisjudgmentReportApiIntegrationTests.FakeProposalModelConfiguration::class)
class TestSpecMisjudgmentReportApiIntegrationTests {
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

    @Autowired
    private lateinit var fakeProposalModel: FakeTestSpecProposalModel

    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private lateinit var originalProfile: TargetProfileVersion
    private lateinit var executionProfile: TargetProfileVersion

    @BeforeEach
    fun enableMisjudgmentFixtures() {
        fakeProposalModel.calls.set(0)
        fakeProposalModel.nextResponse = NARROW_EXCEPTION_RESPONSE
        originalProfile = profileRepository.findActive(TARGET_ID) ?: error("Test target must have an active Profile")
        executionProfile = originalProfile.copy(
            id = UUID.randomUUID(),
            status = TargetProfileStatus.DRAFT,
            checksum = UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", ""),
            definition = originalProfile.definition.copy(
                testSpecExecution = TestSpecExecutionProfileDefinition(
                    executionEnabled = true,
                    allowedCalls = emptyList(),
                    authProfiles = emptySet(),
                    observationSources = emptyList(),
                    supportedFaults = emptySet(),
                    infrastructureTargets = emptySet(),
                    maxConcurrency = 4,
                    maxRequestCount = 10,
                    maxTrials = 3,
                    stateChangingAllowed = false,
                    reset = null,
                ),
            ),
            activatedBy = null,
            activatedAt = null,
        )
        assertTrue(profileRepository.createIfAbsent(executionProfile))
        assertTrue(profileRepository.activate(TARGET_ID, executionProfile.id, "phase22c-test", Instant.now()))
    }

    @AfterEach
    fun restoreProfileAndCleanUp() {
        profileRepository.activate(TARGET_ID, originalProfile.id, "phase22c-test", Instant.now())
        jdbcClient.sql("delete from test_spec_misjudgment_report").update()
        jdbcClient.sql("delete from test_spec_reset_result").update()
        jdbcClient.sql("delete from test_spec_trial_result").update()
        jdbcClient.sql("delete from test_spec_run").update()
        jdbcClient.sql("delete from test_specification").update()
    }

    @Test
    fun `drafts a narrow exception and lets the resulting specification be approved`() {
        val specificationId = field(createSpecification("phase22c-drafted").body(), "id")
        approve(specificationId)
        val runId = createViolatedRun(specificationId, "phase22c-run-drafted")

        val started = reportMisjudgment(specificationId, runId, "phase22c-report-drafted")
        assertEquals(202, started.statusCode(), started.body())
        val reportId = field(started.body(), "id")

        val terminal = awaitTerminalReport(reportId)
        assertContains(terminal.body(), "\"status\":\"DRAFTED\"", message = terminal.body())
        val resultingId = field(terminal.body(), "resultingSpecificationId")
        assertTrue(resultingId.isNotBlank())

        val resulting = get("/api/test-specifications/$resultingId")
        assertContains(resulting.body(), "\"status\":\"PENDING_APPROVAL\"")
        assertContains(resulting.body(), "\"version\":2")
        assertContains(resulting.body(), "responseCount == 3")

        val approved = approve(resultingId)
        assertEquals(200, approved.statusCode(), approved.body())
        assertContains(approved.body(), "\"status\":\"APPROVED\"")
    }

    @Test
    fun `rejects a drafted exception that would nullify the invariant instead of narrowing it`() {
        fakeProposalModel.nextResponse = NULLIFYING_EXCEPTION_RESPONSE
        val specificationId = field(createSpecification("phase22c-nullifying").body(), "id")
        approve(specificationId)
        val runId = createViolatedRun(specificationId, "phase22c-run-nullifying")

        val started = reportMisjudgment(specificationId, runId, "phase22c-report-nullifying")
        val reportId = field(started.body(), "id")

        val terminal = awaitTerminalReport(reportId)
        assertContains(terminal.body(), "\"status\":\"REJECTED\"", message = terminal.body())
        assertContains(terminal.body(), "nullify")
        assertEquals(
            1L,
            jdbcClient.sql("select count(*) from test_specification where spec_key = 'phase22c-nullifying'")
                .query(Long::class.java)
                .single(),
        )
    }

    @Test
    fun `returns the same report for a repeated idempotency key without calling the model again`() {
        val specificationId = field(createSpecification("phase22c-idempotent").body(), "id")
        approve(specificationId)
        val runId = createViolatedRun(specificationId, "phase22c-run-idempotent")

        val first = reportMisjudgment(specificationId, runId, "phase22c-report-shared")
        awaitTerminalReport(field(first.body(), "id"))
        assertEquals(1, fakeProposalModel.calls.get())

        val second = reportMisjudgment(specificationId, runId, "phase22c-report-shared")

        assertEquals(field(first.body(), "id"), field(second.body(), "id"))
        assertEquals(1, fakeProposalModel.calls.get())
    }

    @Test
    fun `refuses a report when the run has no VIOLATED verdict for the named invariant`() {
        val specificationId = field(createSpecification("phase22c-no-verdict").body(), "id")
        approve(specificationId)
        val runId = createPassedRun(specificationId, "phase22c-run-no-verdict")

        val response = reportMisjudgment(specificationId, runId, "phase22c-report-no-verdict")

        assertEquals(409, response.statusCode(), response.body())
        assertContains(response.body(), "TEST_SPEC_MISJUDGMENT_VERDICT_NOT_FOUND")
    }

    @Test
    fun `refuses a report that references a trial the run does not have`() {
        val specificationId = field(createSpecification("phase22c-no-trial").body(), "id")
        approve(specificationId)
        val runId = createViolatedRun(specificationId, "phase22c-run-no-trial")

        val response = reportMisjudgment(specificationId, runId, "phase22c-report-no-trial", trialNumber = 2)

        assertEquals(409, response.statusCode(), response.body())
        assertContains(response.body(), "TEST_SPEC_MISJUDGMENT_TRIAL_NOT_FOUND")
    }

    private fun createSpecification(specKey: String): HttpResponse<String> = post(
        "/api/test-specifications",
        objectMapper.writeValueAsString(
            mapOf(
                "targetSystemId" to TARGET_ID,
                "source" to "RULE_GENERATED",
                "document" to specificationDocument(specKey),
            ),
        ),
    )

    private fun specificationDocument(specKey: String): Map<String, Any> = mapOf(
        "specKey" to specKey,
        "version" to 1,
        "title" to "Phase 22-C misjudgment fixture",
        "category" to "CONSISTENCY",
        "risk" to "SAFE",
        "workload" to listOf(mapOf("kind" to "WAIT", "name" to "settle", "duration" to 0)),
        "observations" to listOf(
            mapOf("id" to "responseCount", "source" to "RESPONSES", "expr" to "count(work[*].status)"),
        ),
        "invariants" to listOf(
            mapOf(
                "id" to INVARIANT_ID,
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

    private fun createViolatedRun(specificationId: String, idempotencyKey: String): String {
        val verdict = InvariantVerdict(
            invariantId = INVARIANT_ID,
            description = "No response was expected from a wait-only workload",
            outcome = InvariantOutcome.VIOLATED,
            condition = "responseCount == 0",
            observedValues = mapOf("responseCount" to "3"),
        )
        return createRun(specificationId, idempotencyKey, TrialOutcome.VIOLATED, listOf(verdict))
    }

    private fun createPassedRun(specificationId: String, idempotencyKey: String): String {
        val verdict = InvariantVerdict(
            invariantId = INVARIANT_ID,
            description = "No response was expected from a wait-only workload",
            outcome = InvariantOutcome.PASSED,
            condition = "responseCount == 0",
            observedValues = mapOf("responseCount" to "0"),
        )
        return createRun(specificationId, idempotencyKey, TrialOutcome.PASSED, listOf(verdict))
    }

    private fun createRun(
        specificationId: String,
        idempotencyKey: String,
        trialOutcome: TrialOutcome,
        verdicts: List<InvariantVerdict>,
    ): String {
        val specification = specificationStore.findById(UUID.fromString(specificationId))
            ?: error("Test specification '$specificationId' was not stored")
        val runId = UUID.randomUUID()
        val run = TestSpecRun(
            id = runId,
            specificationId = specification.id,
            targetSystemId = TARGET_ID,
            profileVersionId = specification.profileVersionId,
            status = TestSpecRunStatus.PENDING,
            idempotencyKey = idempotencyKey,
            requestHash = "a".repeat(REQUEST_HASH_CHARACTERS),
            requestedTrials = 1,
            createdBy = "phase22c-test",
            createdCorrelationId = "phase22c-setup",
            createdAt = Instant.now(),
        )
        runStore.create(run)
        assertTrue(runStore.markRunning(runId, Instant.now()))
        val trial = TrialResult(trialNumber = 1, outcome = trialOutcome, verdicts = verdicts)
        val execution = TrialExecution(
            trialNumber = 1,
            bindings = emptyMap(),
            responses = emptyMap(),
            timings = emptyList(),
            stateChanged = false,
        )
        val outcome = SpecRunOutcome(
            runId = runId.toString(),
            result = SpecificationResult(
                outcome = trialOutcome,
                trialsRun = 1,
                trialsViolated = if (trialOutcome == TrialOutcome.VIOLATED) 1 else 0,
                trialsInconclusive = 0,
                trials = listOf(trial),
            ),
            executions = listOf(execution),
            resets = emptyList(),
            cleanupVerified = true,
        )
        assertTrue(runStore.complete(runId, outcome, Instant.now()))
        return runId.toString()
    }

    private fun reportMisjudgment(
        specificationId: String,
        runId: String,
        idempotencyKey: String,
        trialNumber: Int = 1,
    ): HttpResponse<String> = post(
        "/api/targets/$TARGET_ID/test-spec-misjudgment-reports",
        objectMapper.writeValueAsString(
            mapOf(
                "specificationId" to specificationId,
                "runId" to runId,
                "trialNumber" to trialNumber,
                "invariantId" to INVARIANT_ID,
                "reason" to "The 3 responses observed in this scenario are expected, not a defect",
            ),
        ),
        idempotencyKey,
    )

    private fun awaitTerminalReport(reportId: String): HttpResponse<String> {
        val deadline = System.nanoTime() + Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            val response = get("/api/test-spec-misjudgment-reports/$reportId")
            val status = objectMapper.readTree(response.body()).path("status").asString()
            if (status == "DRAFTED" || status == "REJECTED" || status == "FAILED") return response
            Thread.sleep(AWAIT_POLL_MILLIS)
        }
        error("Misjudgment report '$reportId' did not reach a terminal state in time")
    }

    private fun approve(specificationId: String): HttpResponse<String> = post(
        "/api/test-specifications/$specificationId/approve",
        """{"confirmation":"$SAFE_CONFIRMATION"}""",
    )

    private fun get(path: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(path: String, body: String, idempotencyKey: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
        idempotencyKey?.let { key -> builder.header("Idempotency-Key", key) }
        return httpClient.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun field(body: String, name: String): String =
        objectMapper.readValue(body, Map::class.java)[name] as String

    private companion object {
        const val TARGET_ID = "contract-test-target"
        const val SAFE_CONFIRMATION = "APPROVE_SAFE_TEST_SPECIFICATION"
        const val INVARIANT_ID = "responseCountZero"
        const val AWAIT_TIMEOUT_SECONDS = 5L
        const val AWAIT_POLL_MILLIS = 25L
        const val REQUEST_HASH_CHARACTERS = 64

        val NARROW_EXCEPTION_RESPONSE = """
            {"exception":{
                "condition":"responseCount == 3",
                "description":"Reviewer confirmed 3 responses is expected in this scenario"
            }}
        """.trimIndent()

        val NULLIFYING_EXCEPTION_RESPONSE = """
            {"exception":{"condition":"true","description":"Always applies"}}
        """.trimIndent()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeProposalModelConfiguration {
        @Bean
        @Primary
        fun fakeTestSpecProposalModel(): FakeTestSpecProposalModel = FakeTestSpecProposalModel()
    }
}
