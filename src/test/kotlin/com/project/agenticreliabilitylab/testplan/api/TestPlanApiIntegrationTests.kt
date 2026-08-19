package com.project.agenticreliabilitylab.testplan.api

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.infrastructure.JdbcTargetProfileRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestPlanApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var profileRepository: JdbcTargetProfileRepository

    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val objectMapper = ObjectMapper()

    @Test
    fun `selects approves and dispatches a read-only plan to the existing batch engine`() {
        val candidateId = readOnlyCandidateId(generationBody())

        val created = createPlan(candidateId, "test-plan-flow-001")
        assertEquals(201, created.statusCode(), created.body())
        assertContains(created.body(), "\"status\":\"PENDING_APPROVAL\"")
        assertContains(created.body(), "\"requiredConfirmation\":\"EXECUTE_SAFE_TEST_PLAN\"")
        val planId = field(created.body(), "id")

        val wrongPhrase = post("/api/test-plans/$planId/approve", """{"confirmation":"YES"}""")
        assertEquals(400, wrongPhrase.statusCode(), wrongPhrase.body())

        val approved = post(
            "/api/test-plans/$planId/approve",
            """{"confirmation":"EXECUTE_SAFE_TEST_PLAN"}""",
        )
        assertEquals(200, approved.statusCode(), approved.body())
        assertContains(approved.body(), "\"status\":\"APPROVED\"")

        val dispatched = post("/api/test-plans/$planId/dispatch", "")
        assertEquals(202, dispatched.statusCode(), dispatched.body())
        assertContains(dispatched.body(), "\"status\":\"DISPATCHED\"")
        assertContains(dispatched.body(), "\"kind\":\"TARGET_TEST_BATCH\"")

        val redispatched = post("/api/test-plans/$planId/dispatch", "")
        assertEquals(202, redispatched.statusCode(), redispatched.body())
        assertEquals(
            executionReferenceIds(dispatched.body()),
            executionReferenceIds(redispatched.body()),
        )
    }

    /**
     * Two dispatches racing on one plan must still hand over exactly one Batch.
     *
     * The sequential re-dispatch above cannot catch this: there the first call has already committed, so the second
     * one reads DISPATCHED and exits early. Only overlapping transactions can both read APPROVED and both try to
     * create a Batch, which is the case that would duplicate real execution against a Target.
     */
    @Test
    fun `hands over one batch when the same plan is dispatched concurrently`() {
        val planId = field(createPlan(readOnlyCandidateId(generationBody()), "test-plan-concurrent-001").body(), "id")
        val approved = post("/api/test-plans/$planId/approve", APPROVAL_BODY)
        assertEquals(200, approved.statusCode(), approved.body())

        val responses = concurrently { post("/api/test-plans/$planId/dispatch", "") }
        val bodies = responses.joinToString { response -> "${response.statusCode()} ${response.body()}" }

        assertTrue(responses.none { response -> response.statusCode() >= 500 }, bodies)
        assertTrue(responses.any { response -> response.statusCode() == 202 }, bodies)
        val references = executionReferenceIds(get("/api/test-plans/$planId").body())
        assertEquals(1, references.size, references.toString())
    }

    /**
     * Approving twice at once must not rewrite who approved the plan or when.
     *
     * The approval record is what an audit reads back, so a second caller overwriting it would silently replace the
     * accountable actor and timestamp of a decision that was already made.
     */
    @Test
    fun `keeps one approval record when the same plan is approved concurrently`() {
        val planId = field(
            createPlan(readOnlyCandidateId(generationBody()), "test-plan-concurrent-approve-001").body(),
            "id",
        )

        val responses = concurrently { post("/api/test-plans/$planId/approve", APPROVAL_BODY) }
        val bodies = responses.joinToString { response -> "${response.statusCode()} ${response.body()}" }

        assertTrue(responses.none { response -> response.statusCode() >= 500 }, bodies)
        assertContains(get("/api/test-plans/$planId").body(), """"status":"APPROVED"""")
        assertEquals(1, responses.map { response -> field(response.body(), "approvedAt") }.distinct().size, bodies)
    }

    /** Runs the same request from several threads released together, so the calls actually overlap. */
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

    @Test
    fun `refuses to plan a candidate that has no safe execution path yet`() {
        val body = generationBody()
        val unbound = candidates(body).first { candidate ->
            candidate["binding"].let { it as Map<*, *> }["kind"] != "READ_ONLY_BATCH"
        }

        val response = createPlan(unbound["id"] as String, "test-plan-unbound-001")

        assertEquals(409, response.statusCode(), response.body())
        assertTrue(
            response.body().contains("EXECUTION_BINDING_NOT_SUPPORTED") ||
                response.body().contains("TEST_CANDIDATE_NOT_EXECUTABLE"),
            response.body(),
        )
    }

    @Test
    fun `supersedes an approved plan when the profile version changes before dispatch`() {
        val planId = field(createPlan(readOnlyCandidateId(generationBody()), "test-plan-superseded-001").body(), "id")
        post("/api/test-plans/$planId/approve", """{"confirmation":"EXECUTE_SAFE_TEST_PLAN"}""")
        val active = profileRepository.findActive(TARGET_ID) ?: error("Test target must have an active Profile")
        val next = active.copy(
            id = UUID.randomUUID(),
            checksum = "phase13-superseding-checksum",
            status = TargetProfileStatus.DRAFT,
            activatedBy = null,
            activatedAt = null,
        )

        try {
            profileRepository.createIfAbsent(next)
            profileRepository.activate(TARGET_ID, next.id, "phase13-test", Instant.now())

            val dispatched = post("/api/test-plans/$planId/dispatch", "")

            assertEquals(202, dispatched.statusCode(), dispatched.body())
            assertContains(dispatched.body(), "\"status\":\"SUPERSEDED\"")
            assertContains(dispatched.body(), "\"executionReferences\":[]")
        } finally {
            profileRepository.activate(TARGET_ID, active.id, "phase13-test", Instant.now())
        }
    }

    private fun generationBody(): String {
        val snapshot = post(
            "/api/target-knowledge-snapshots",
            objectMapper.writeValueAsString(
                mapOf(
                    "targetSystemId" to TARGET_ID,
                    "openApiDocument" to OPENAPI_DOCUMENT,
                    "readmeDocument" to "# Plan\n재고는 음수가 되지 않는다.\n",
                ),
            ),
        )
        assertEquals(201, snapshot.statusCode(), snapshot.body())
        val generation = post(
            "/api/test-candidate-generations",
            """{"knowledgeSnapshotId":"${field(snapshot.body(), "id")}"}""",
        )
        assertEquals(201, generation.statusCode(), generation.body())
        return generation.body()
    }

    @Suppress("UNCHECKED_CAST")
    private fun candidates(generationBody: String): List<Map<String, Any?>> =
        (objectMapper.readValue(generationBody, Map::class.java)["candidates"] as List<Map<String, Any?>>)

    private fun readOnlyCandidateId(generationBody: String): String = candidates(generationBody)
        .first { candidate -> candidate["readiness"] == "EXECUTABLE" }
        .let { candidate -> candidate["id"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun executionReferenceIds(planBody: String): List<Any?> =
        (objectMapper.readValue(planBody, Map::class.java)["executionReferences"] as List<Map<String, Any?>>)
            .map { reference -> reference["referenceId"] }

    private fun field(body: String, name: String): String =
        objectMapper.readValue(body, Map::class.java)[name] as String

    private fun createPlan(candidateId: String, idempotencyKey: String): HttpResponse<String> {
        val generationId = field(generationBody(), "id")
        return post(
            "/api/test-plans",
            """{"generationId":"$generationId","candidateIds":["$candidateId"]}""",
            idempotencyKey,
        )
    }

    private fun get(path: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer executor-plan-token")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(path: String, body: String, idempotencyKey: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer executor-plan-token")
            .header("Content-Type", "application/json")
        idempotencyKey?.let { key -> builder.header("Idempotency-Key", key) }
        return httpClient.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private companion object {
        const val TARGET_ID = "contract-test-target"
        const val CONCURRENT_CALLS = 2
        const val CALL_TIMEOUT_SECONDS = 10L
        const val APPROVAL_BODY = """{"confirmation":"EXECUTE_SAFE_TEST_PLAN"}"""

        val OPENAPI_DOCUMENT = """
            {
              "openapi":"3.1.0",
              "info":{"title":"Commerce"},
              "paths":{
                "/catalog":{"get":{"operationId":"catalog-list","responses":{"200":{"description":"ok"}}}},
                "/orders":{"post":{"operationId":"create-order","responses":{"201":{"description":"created"}}}}
              }
            }
        """.trimIndent()

        @JvmStatic
        @DynamicPropertySource
        fun planProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.access.mode") { "SECURED" }
            registry.add("arl.access.viewer-token") { "viewer-plan-token" }
            registry.add("arl.access.profile-editor-token") { "executor-plan-token" }
            registry.add("arl.access.executor-token") { "executor-plan-token" }
            registry.add("arl.target-specs.registrations[0].target-system-id") { TARGET_ID }
            registry.add("arl.target-specs.registrations[0].execution-enabled") { true }
            registry.add("arl.target-specs.registrations[0].host-resource-group") { "plan-test-resource" }
            registry.add("arl.target-specs.registrations[0].max-batch-size") { 3 }
            registry.add("arl.target-specs.registrations[0].request-timeout") { "2s" }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].id") { "catalog-list" }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].title") { "Catalog listing" }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].description") { "Catalog read." }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].path") { "/catalog" }
            registry.add("arl.target-specs.registrations[0].read-only-operations[0].expected-status-codes[0]") { 200 }
        }
    }
}
