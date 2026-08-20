package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileReadTimingDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.infrastructure.JdbcTargetProfileRepository
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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

/** The DESIGN3 Phase 17 exit criterion: three document shapes, one unchanged execution and judging engine. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Phase17CompletionIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var profileRepository: JdbcTargetProfileRepository

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private lateinit var originalProfile: TargetProfileVersion

    @BeforeEach
    fun activateCompletionProfile() {
        fixture.reset()
        originalProfile = profileRepository.findActive(TARGET_ID)
            ?: error("Completion Target must have an active Profile")
        val profile = completionProfile(originalProfile)
        assertTrue(profileRepository.createIfAbsent(profile))
        assertTrue(profileRepository.activate(TARGET_ID, profile.id, "phase17-completion", Instant.now()))
    }

    @AfterEach
    fun restoreProfileAndCleanSpecifications() {
        profileRepository.activate(TARGET_ID, originalProfile.id, "phase17-completion", Instant.now())
        jdbcClient.sql("delete from test_spec_reset_result").update()
        jdbcClient.sql("delete from test_spec_trial_result").update()
        jdbcClient.sql("delete from test_spec_run").update()
        jdbcClient.sql("delete from test_specification").update()
    }

    @Test
    fun `judges concurrency idempotency and consistency specifications without engine changes`() {
        val documents = listOf(
            concurrencySpecification(),
            idempotencySpecification(),
            consistencySpecification(),
        )

        val runs = documents.mapIndexed { index, document -> executeApproved(document, "phase17-exit-$index") }

        runs.forEach { response ->
            assertEquals(201, response.statusCode(), response.body())
            assertContains(response.body(), "\"status\":\"COMPLETED\"")
            assertContains(response.body(), "\"resultOutcome\":\"PASSED\"")
            assertContains(response.body(), "\"trialsViolated\":0")
            assertContains(response.body(), "\"trialsInconclusive\":0")
            assertContains(response.body(), "\"cleanupVerified\":true")
        }
        assertEquals(
            FixtureAudit(
                orderRequests = CONCURRENCY_TRIALS * CONCURRENT_REQUESTS,
                paymentRequests = IDEMPOTENCY_TRIALS * CONCURRENT_REQUESTS,
                transferRequests = CONSISTENCY_TRIALS,
                resetRequests = CONCURRENCY_TRIALS + IDEMPOTENCY_TRIALS + CONSISTENCY_TRIALS,
            ),
            fixture.audit(),
        )
    }

    private fun executeApproved(document: Map<String, Any>, idempotencyKey: String): HttpResponse<String> {
        val created = post(
            "/api/test-specifications",
            objectMapper.writeValueAsString(
                mapOf("targetSystemId" to TARGET_ID, "source" to "RULE_GENERATED", "document" to document),
            ),
        )
        assertEquals(201, created.statusCode(), created.body())
        val specificationId = field(created.body(), "id")
        val approved = post(
            "/api/test-specifications/$specificationId/approve",
            """{"confirmation":"$MODERATE_CONFIRMATION"}""",
        )
        assertEquals(200, approved.statusCode(), approved.body())
        return post("/api/test-specifications/$specificationId/runs", null, idempotencyKey)
    }

    private fun concurrencySpecification(): Map<String, Any> = baseSpecification(
        key = "stock-concurrency",
        category = "CONCURRENCY",
        trials = CONCURRENCY_TRIALS,
        setupPath = "/scenario/concurrency",
        workload = callStep("orders", "/orders", CONCURRENT_REQUESTS, CONCURRENT_REQUESTS),
        observations = listOf(
            responseObservation("acceptedQuantity", "sum(orders[*].body.acceptedQuantity)"),
            apiObservation("remainingStock", "response.body.stock"),
        ),
        invariants = listOf(
            invariant("stockNonNegative", "Stock never becomes negative", "remainingStock >= 0"),
            invariant(
                "stockConserved",
                "Accepted quantity and remaining stock conserve the initial stock",
                "acceptedQuantity + remainingStock == 1",
            ),
        ),
    )

    private fun idempotencySpecification(): Map<String, Any> = baseSpecification(
        key = "payment-idempotency",
        category = "IDEMPOTENCY",
        trials = IDEMPOTENCY_TRIALS,
        setupPath = "/scenario/idempotency",
        workload = callStep(
            name = "payments",
            path = "/payments",
            requestCount = CONCURRENT_REQUESTS,
            concurrency = CONCURRENT_REQUESTS,
            headers = mapOf("Idempotency-Key" to "payment-{{trialNumber}}"),
        ),
        observations = listOf(
            responseObservation("createdEffects", "sum(payments[*].body.createdEffect)"),
            apiObservation("paymentCount", "response.body.paymentCount"),
        ),
        invariants = listOf(
            invariant("oneEffect", "One idempotency key creates one effect", "createdEffects == 1"),
            invariant("onePayment", "Only one payment is stored", "paymentCount == 1"),
        ),
    )

    private fun consistencySpecification(): Map<String, Any> = baseSpecification(
        key = "transfer-consistency",
        category = "CONSISTENCY",
        trials = CONSISTENCY_TRIALS,
        setupPath = "/scenario/consistency",
        workload = callStep("transfers", "/transfers", 1, 1),
        observations = listOf(
            apiObservation("totalBalance", "response.body.totalBalance"),
            apiObservation("ledgerCount", "response.body.ledgerCount"),
        ),
        invariants = listOf(
            invariant("balanceConserved", "A transfer conserves total balance", "totalBalance == 200"),
            invariant("ledgerWritten", "A transfer writes one ledger entry", "ledgerCount == 1"),
        ),
    )

    @Suppress("LongParameterList") // The document dimensions are explicit because this is the format exit test.
    private fun baseSpecification(
        key: String,
        category: String,
        trials: Int,
        setupPath: String,
        workload: Map<String, Any>,
        observations: List<Map<String, Any>>,
        invariants: List<Map<String, Any>>,
    ): Map<String, Any> = mapOf(
        "specKey" to key,
        "version" to 1,
        "title" to "Phase 17 $key",
        "category" to category,
        "risk" to "MODERATE",
        "setup" to listOf(
            mapOf("name" to "scenario", "call" to mapOf("method" to "POST", "path" to setupPath)),
        ),
        "workload" to listOf(workload),
        "observations" to observations,
        "invariants" to invariants,
        "policy" to mapOf(
            "trials" to trials,
            "aggregation" to "ANY_VIOLATION_FAILS",
            "stopPolicy" to "RUN_ALL",
            "cleanupTiming" to "EACH_TRIAL",
            "interval" to 0,
        ),
        "cleanup" to mapOf("method" to "ENVIRONMENT_RESET"),
    )

    private fun callStep(
        name: String,
        path: String,
        requestCount: Int,
        concurrency: Int,
        headers: Map<String, String> = emptyMap(),
    ): Map<String, Any> = mapOf(
        "kind" to "CALL",
        "name" to name,
        "call" to mapOf("method" to "POST", "path" to path, "headers" to headers),
        "requestCount" to requestCount,
        "concurrency" to concurrency,
        "captureAs" to name,
    )

    private fun responseObservation(id: String, expression: String): Map<String, Any> =
        mapOf("id" to id, "source" to "RESPONSES", "expr" to expression)

    private fun apiObservation(id: String, expression: String): Map<String, Any> = mapOf(
        "id" to id,
        "source" to "API",
        "call" to mapOf("method" to "GET", "path" to "/state"),
        "expr" to expression,
    )

    private fun invariant(id: String, description: String, condition: String): Map<String, Any> =
        mapOf("id" to id, "description" to description, "condition" to condition)

    private fun completionProfile(base: TargetProfileVersion): TargetProfileVersion = base.copy(
        id = UUID.randomUUID(),
        status = TargetProfileStatus.DRAFT,
        checksum = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", ""),
        definition = base.definition.copy(testSpecExecution = executionDefinition()),
        activatedBy = null,
        activatedAt = null,
    )

    private fun executionDefinition() = TestSpecExecutionProfileDefinition(
        executionEnabled = true,
        allowedCalls = listOf(
            ProfileHttpCallDefinition("POST", "/scenario/concurrency"),
            ProfileHttpCallDefinition("POST", "/scenario/idempotency"),
            ProfileHttpCallDefinition("POST", "/scenario/consistency"),
            ProfileHttpCallDefinition("POST", "/orders"),
            ProfileHttpCallDefinition("POST", "/payments"),
            ProfileHttpCallDefinition("POST", "/transfers"),
            ProfileHttpCallDefinition("POST", "/reset"),
            ProfileHttpCallDefinition("GET", "/state"),
        ),
        authProfiles = emptySet(),
        observationSources = emptyList(),
        supportedFaults = emptySet(),
        infrastructureTargets = emptySet(),
        maxConcurrency = CONCURRENT_REQUESTS,
        maxRequestCount = CONCURRENT_REQUESTS,
        maxTrials = CONCURRENCY_TRIALS,
        stateChangingAllowed = true,
        reset = ProfileResetDefinition(
            method = CleanupMethod.ENVIRONMENT_RESET,
            hook = ProfileHttpCallDefinition("POST", "/reset"),
            expectedDuration = Duration.ofSeconds(1),
            verifications = listOf(
                ProfileResetVerificationDefinition(
                    id = "environmentClean",
                    call = ProfileHttpCallDefinition("GET", "/state"),
                    expression = "response.body.clean",
                    condition = "environmentClean == true",
                    readTiming = ProfileReadTimingDefinition(
                        StabilityRule.IMMEDIATE,
                        Duration.ZERO,
                        Duration.ZERO,
                    ),
                ),
            ),
        ),
    )

    private fun post(path: String, body: String?, idempotencyKey: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
        idempotencyKey?.let { key -> builder.header("Idempotency-Key", key) }
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        return httpClient.send(builder.POST(publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun field(body: String, name: String): String =
        objectMapper.readValue(body, Map::class.java)[name] as String

    companion object {
        private val fixture = Phase17FixtureTarget.start()

        @JvmStatic
        @DynamicPropertySource
        fun targetProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.targets.registrations[0].id") { TARGET_ID }
            registry.add("arl.targets.registrations[0].name") { "Phase 17 Completion Target" }
            registry.add("arl.targets.registrations[0].adapter-type") { "HTTP_TARGET" }
            registry.add("arl.targets.registrations[0].environment") { "LOCAL" }
            registry.add("arl.targets.registrations[0].base-url") { fixture.origin }
            registry.add("arl.targets.registrations[0].allowed-origin") { fixture.origin }
            registry.add("arl.targets.registrations[0].allowed-cidrs[0]") { "127.0.0.0/8" }
            registry.add("arl.targets.registrations[0].health-path") { "/state" }
            registry.add("arl.targets.registrations[0].source-repository") { "phase17-fixture" }
            registry.add("arl.targets.registrations[0].identity-verification") { "CONFIGURATION_ONLY" }
            registry.add("arl.targets.registrations[0].capabilities[0]") { "HEALTH" }
            registry.add("arl.targets.registrations[0].capabilities[1]") { "HTTP_API" }
            registry.add("arl.targets.registrations[0].enabled") { true }
        }

        @JvmStatic
        @AfterAll
        fun stopFixture() {
            fixture.stop()
        }

        const val TARGET_ID = "contract-test-target"
        const val MODERATE_CONFIRMATION = "APPROVE_MODERATE_TEST_SPECIFICATION"
        const val CONCURRENT_REQUESTS = 2
        const val CONCURRENCY_TRIALS = 3
        const val IDEMPOTENCY_TRIALS = 2
        const val CONSISTENCY_TRIALS = 2
    }
}
