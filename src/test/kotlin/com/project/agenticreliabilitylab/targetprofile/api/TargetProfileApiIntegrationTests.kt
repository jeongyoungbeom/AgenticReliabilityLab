package com.project.agenticreliabilitylab.targetprofile.api

import com.project.agenticreliabilitylab.testspec.application.port.TestSpecExecutionProfileCatalog
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TargetProfileApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var testSpecProfiles: TestSpecExecutionProfileCatalog

    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @Test
    fun `validates imports and explicitly activates a versioned profile`() {
        val targetId = "imported-${UUID.randomUUID().toString().take(8)}"
        val import = post("/api/target-profiles", profileJson(validProfile(targetId)), authorizationHeader())

        assertEquals(202, import.statusCode(), import.body())
        assertContains(import.body(), "\"status\":\"DRAFT\"")
        val versionId = "\"id\":\"([^\"]+)\"".toRegex().find(import.body())?.groupValues?.get(1)
            ?: error("Imported Target Profile version id was absent")

        val draft = get("/api/target-profiles/$versionId")
        assertEquals(200, draft.statusCode())
        assertContains(draft.body(), "\"status\":\"DRAFT\"")

        val inactiveCandidates = get("/api/targets/$targetId/test-candidates")
        assertEquals(404, inactiveCandidates.statusCode())

        val activated = post(
            "/api/target-profiles/$versionId/activate",
            "{\"confirmation\":\"ACTIVATE_TARGET_PROFILE_VERSION\"}",
            authorizationHeader(),
        )
        assertEquals(202, activated.statusCode())
        assertContains(activated.body(), "\"status\":\"ACTIVE\"")

        val executionProfile = testSpecProfiles.requireActive(targetId)
        assertEquals(UUID.fromString(versionId), executionProfile.profileVersionId)
        assertTrue(
            executionProfile.capabilities.allows(
                SpecHttpCall("GET", "/api/read-only", "reader", emptyMap(), null),
            ),
        )
        assertEquals(2, executionProfile.capabilities.maxConcurrency)
        assertEquals(
            "PROMETHEUS",
            executionProfile.capabilities.observationSources.getValue("metrics").kind.name,
        )
        assertEquals(
            "up",
            executionProfile.capabilities.observationSources.getValue("metrics").queries["httpUp"],
        )
        assertEquals(CleanupMethod.NOT_REQUIRED, executionProfile.resetPlan.method)

        val candidates = get("/api/targets/$targetId/test-candidates")
        assertEquals(200, candidates.statusCode())
        assertContains(candidates.body(), "imported-read-only")
        assertFalse(candidates.body().contains("access_token"))

        assertPendingBatchIsCancelledWhenItsProfileIsReplaced(targetId)
    }

    private fun assertPendingBatchIsCancelledWhenItsProfileIsReplaced(targetId: String) {
        val unapprovedCaller = post(
            "/api/test-batches",
            "{\"targetSystemId\":\"$targetId\",\"candidateIds\":[\"imported-read-only\"]}",
            mapOf("Idempotency-Key" to "unauthorized-$targetId"),
        )
        assertEquals(403, unapprovedCaller.statusCode())

        val pendingBatch = post(
            "/api/test-batches",
            "{\"targetSystemId\":\"$targetId\",\"candidateIds\":[\"imported-read-only\"]}",
            executorAuthorizationHeader() + mapOf("Idempotency-Key" to "pending-$targetId"),
        )
        assertEquals(202, pendingBatch.statusCode())
        val batchId = "\"id\":\"([^\"]+)\"".toRegex().find(pendingBatch.body())?.groupValues?.get(1)
            ?: error("Target test batch id was absent")

        val replacement = post(
            "/api/target-profiles",
            profileJson(validProfile(targetId, executionEnabled = false)),
            authorizationHeader(),
        )
        assertEquals(202, replacement.statusCode(), replacement.body())
        val replacementVersionId = "\"id\":\"([^\"]+)\"".toRegex().find(replacement.body())?.groupValues?.get(1)
            ?: error("Replacement Target Profile version id was absent")
        assertEquals(
            202,
            post(
                "/api/target-profiles/$replacementVersionId/activate",
                "{\"confirmation\":\"ACTIVATE_TARGET_PROFILE_VERSION\"}",
                authorizationHeader(),
            ).statusCode(),
        )

        val approval = post(
            "/api/test-batches/$batchId/approve",
            "{\"confirmation\":\"EXECUTE_SAFE_HTTP_BATCH\"}",
            executorAuthorizationHeader(),
        )
        assertEquals(202, approval.statusCode())
        assertContains(approval.body(), "\"status\":\"CANCELLED\"")
    }

    @Test
    fun `rejects unauthorised imports and query strings in Profile paths`() {
        val targetId = "unsafe-${UUID.randomUUID().toString().take(8)}"
        val unauthorised = post("/api/target-profiles", profileJson(validProfile(targetId)))
        assertEquals(403, unauthorised.statusCode())

        val unsafePath = validProfile(targetId).replace("/api/read-only", "/api/read-only?access_token=secret")
        val validation = post("/api/target-profiles/validate", profileJson(unsafePath), authorizationHeader())
        assertEquals(400, validation.statusCode(), validation.body())
        assertContains(validation.body(), "fixed relative HTTP path")

        val unsafeOrigin = validProfile(targetId).replace("http://127.0.0.1:18080", "http://127.0.0.1:18080/api/v1")
        val originValidation = post("/api/target-profiles/validate", profileJson(unsafeOrigin), authorizationHeader())
        assertEquals(400, originValidation.statusCode(), originValidation.body())
        assertContains(originValidation.body(), "only an HTTP(S) origin")

        val mismatchedQuery = validProfile(targetId).replace("httpUp: up", "otherField: up")
        val sourceValidation = post(
            "/api/target-profiles/validate",
            profileJson(mismatchedQuery),
            authorizationHeader(),
        )
        assertEquals(400, sourceValidation.statusCode(), sourceValidation.body())
        assertContains(sourceValidation.body(), "exactly one query for every field")

        val malformedPrometheus = validProfile(targetId)
            .replace("http://127.0.0.1:19090/prometheus", "http://[")
        val malformedSourceValidation = post(
            "/api/target-profiles/validate",
            profileJson(malformedPrometheus),
            authorizationHeader(),
        )
        assertEquals(400, malformedSourceValidation.statusCode(), malformedSourceValidation.body())
        assertContains(malformedSourceValidation.body(), "not a valid URI")

        val oversized = post("/api/target-profiles/validate", profileJson("x".repeat(262_144)))
        assertEquals(413, oversized.statusCode())
    }

    private fun authorizationHeader(): Map<String, String> =
        mapOf("Authorization" to "Bearer profile-editor-test-token")

    private fun executorAuthorizationHeader(): Map<String, String> =
        mapOf("Authorization" to "Bearer executor-test-token")

    private fun validProfile(targetId: String, executionEnabled: Boolean = true): String = """
        arl:
          targets:
            registrations:
              - id: $targetId
                name: Imported Test Target
                adapter-type: HTTP_TARGET
                environment: TEST
                base-url: http://127.0.0.1:18080
                allowed-origin: http://127.0.0.1:18080
                allowed-cidrs: [127.0.0.0/8]
                health-path: /actuator/health
                source-repository: imported-test-target
                identity-verification: CONFIGURATION_ONLY
                capabilities: [HEALTH, HTTP_API]
          target-specs:
            registrations:
              - target-system-id: $targetId
                execution-enabled: $executionEnabled
                host-resource-group: imported-test-target
                max-batch-size: 2
                request-timeout: 2s
                read-only-operations:
                  - id: imported-read-only
                    title: Imported read-only operation
                    description: Verifies that an imported Profile supplies a safe read-only candidate.
                    path: /api/read-only
                    expected-status-codes: [200]
          test-spec-execution:
            registrations:
              - target-system-id: $targetId
                execution-enabled: true
                allowed-calls:
                  - method: GET
                    path: /api/read-only
                    auth-profile: reader
                auth-profiles: [reader]
                observation-sources:
                  - name: harness
                    kind: HARNESS_STATE
                    endpoint: /harness/state
                    fields: [dbStock]
                    auth-profile: reader
                  - name: metrics
                    kind: PROMETHEUS
                    endpoint: http://127.0.0.1:19090/prometheus
                    fields: [httpUp]
                    queries:
                      httpUp: up
                max-concurrency: 2
                max-request-count: 20
                max-trials: 5
                state-changing-allowed: false
                reset:
                  method: NOT_REQUIRED
    """.trimIndent()

    private fun profileJson(yaml: String): String = "{\"yaml\":${objectMapper.writeValueAsString(yaml)}}"

    private fun get(path: String): HttpResponse<String> = httpClient.send(
        request(path)
            .header("Authorization", "Bearer profile-editor-test-token")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val request = request(path).header("Content-Type", "application/json")
        headers.forEach { (name, value) -> request.header(name, value) }
        return httpClient.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI("http://127.0.0.1:$serverPort$path"))
        .timeout(Duration.ofSeconds(3))

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun accessProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.access.mode") { "SECURED" }
            registry.add("arl.access.profile-editor-token") { "profile-editor-test-token" }
            registry.add("arl.access.executor-token") { "executor-test-token" }
            registry.add("arl.access.viewer-token") { "viewer-test-token" }
        }
    }
}
