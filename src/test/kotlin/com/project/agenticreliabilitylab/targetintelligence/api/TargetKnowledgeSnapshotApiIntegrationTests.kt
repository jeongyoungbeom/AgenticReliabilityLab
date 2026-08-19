package com.project.agenticreliabilitylab.targetintelligence.api

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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TargetKnowledgeSnapshotApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var profileRepository: JdbcTargetProfileRepository

    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val objectMapper = ObjectMapper()

    @Test
    fun `builds a cited snapshot from supplied documents without contacting the target`() {
        val response = create(intakeBody(openApi = OPENAPI_DOCUMENT, readme = README_DOCUMENT, brief = BRIEF))

        assertEquals(201, response.statusCode(), response.body())
        val body = response.body()
        assertContains(body, "\"profileVersionActive\":true")
        assertContains(body, "\"extractionVersion\":\"target-knowledge-v1\"")
        assertContains(body, "\"confirmed\":false")
        assertContains(body, "\"mutability\":\"WRITE\"")
        assertContains(body, "\"mutability\":\"READ\"")
        assertContains(body, "paths./orders.post")
        assertContains(body, "\"confidence\":\"STATED\"")
        assertContains(body, "\"confidence\":\"ASSUMPTION\"")
        assertContains(body, "\"type\":\"EVENT\"")
        assertContains(body, "\"type\":\"RETRY\"")
        assertContains(body, "\"type\":\"ASYNC\"")
        assertContains(body, "OPENAPI_NO_TARGET_CONTACT")
        assertContains(body, "README_ASSUMPTION_ONLY")
    }

    @Test
    fun `returns the existing snapshot when the same documents are submitted again`() {
        val first = create(intakeBody(readme = "# Idempotent Service\nGET /health - health check\n"))
        val second = create(intakeBody(readme = "# Idempotent Service\nGET /health - health check\n"))

        assertEquals(201, first.statusCode(), first.body())
        assertEquals(201, second.statusCode(), second.body())
        assertEquals(snapshotId(first.body()), snapshotId(second.body()))
    }

    @Test
    fun `rejects an external OpenAPI reference and never fetches it`() {
        val document = """{"openapi":"3.1.0","paths":{"/safe":{"${'$'}ref":"https://example.test/remote.yaml"}}}"""

        val response = create(intakeBody(openApi = document))

        assertEquals(400, response.statusCode(), response.body())
        assertFalse(response.body().contains("example.test/remote.yaml"))
    }

    @Test
    fun `rejects an intake body before MVC materializes it`() {
        val response = create(intakeBody(openApi = "x".repeat(OVERSIZED_DOCUMENT_CHARACTERS)))

        assertEquals(413, response.statusCode())
    }

    @Test
    fun `records one user confirmation and rejects an unknown confirmation phrase`() {
        val created = create(intakeBody(readme = "# Confirmable Service\nGET /status - status check\n"))
        val snapshotId = snapshotId(created.body())

        val wrongPhrase = post("/api/target-knowledge-snapshots/$snapshotId/confirmation", """{"confirmation":"YES"}""")
        assertEquals(400, wrongPhrase.statusCode(), wrongPhrase.body())

        val confirmed = post(
            "/api/target-knowledge-snapshots/$snapshotId/confirmation",
            """{"confirmation":"CONFIRM_TARGET_KNOWLEDGE"}""",
        )
        assertEquals(200, confirmed.statusCode(), confirmed.body())
        assertContains(confirmed.body(), "\"confirmed\":true")

        val repeated = post(
            "/api/target-knowledge-snapshots/$snapshotId/confirmation",
            """{"confirmation":"CONFIRM_TARGET_KNOWLEDGE"}""",
        )
        assertEquals(200, repeated.statusCode(), repeated.body())
        assertContains(repeated.body(), "\"confirmed\":true")
    }

    @Test
    fun `reports a snapshot bound to a superseded profile version as unusable`() {
        val created = create(intakeBody(readme = "# Superseded Service\nGET /ping - ping check\n"))
        val snapshotId = snapshotId(created.body())
        val active = profileRepository.findActive(TARGET_ID) ?: error("Test target must have an active Profile")
        val superseding = active.copy(
            id = UUID.randomUUID(),
            checksum = "phase11-superseding-checksum",
            status = TargetProfileStatus.DRAFT,
            activatedBy = null,
            activatedAt = null,
        )

        try {
            profileRepository.createIfAbsent(superseding)
            profileRepository.activate(TARGET_ID, superseding.id, "phase11-test", Instant.now())

            val reread = get("/api/target-knowledge-snapshots/$snapshotId")
            assertEquals(200, reread.statusCode(), reread.body())
            assertContains(reread.body(), "\"profileVersionActive\":false")

            val confirmation = post(
                "/api/target-knowledge-snapshots/$snapshotId/confirmation",
                """{"confirmation":"CONFIRM_TARGET_KNOWLEDGE"}""",
            )
            assertEquals(409, confirmation.statusCode(), confirmation.body())
            assertContains(confirmation.body(), "KNOWLEDGE_SNAPSHOT_PROFILE_VERSION_INACTIVE")
        } finally {
            profileRepository.activate(TARGET_ID, active.id, "phase11-test", Instant.now())
        }
    }

    private fun intakeBody(
        openApi: String? = null,
        readme: String? = null,
        brief: Map<String, Any>? = null,
    ): String {
        val payload = buildMap<String, Any> {
            put("targetSystemId", TARGET_ID)
            openApi?.let { document -> put("openApiDocument", document) }
            readme?.let { document -> put("readmeDocument", document) }
            brief?.let { input -> put("brief", input) }
        }
        return objectMapper.writeValueAsString(payload)
    }

    private fun snapshotId(body: String): String =
        """"id":"([^"]+)""".toRegex().find(body)?.groupValues?.get(1)
            ?: error("Snapshot id was absent: $body")

    private fun create(body: String): HttpResponse<String> = post("/api/target-knowledge-snapshots", body)

    private fun post(path: String, body: String): HttpResponse<String> = httpClient.send(
        request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun get(path: String): HttpResponse<String> = httpClient.send(
        request(path).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI("http://127.0.0.1:$serverPort$path"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer profile-editor-knowledge-token")

    private companion object {
        const val TARGET_ID = "contract-test-target"

        /**
         * Just past the intake filter bound, mirroring the Draft payload test.
         *
         * The filter answers 413 and closes the connection without draining the request, so a large overshoot makes the
         * client fail while still writing instead of reading the refusal. Overshooting by a couple of kilobytes keeps
         * the refusal observable.
         */
        const val OVERSIZED_DOCUMENT_CHARACTERS = 2_688_962

        val OPENAPI_DOCUMENT = """
            {
              "openapi":"3.1.0",
              "info":{"title":"Commerce"},
              "paths":{
                "/orders":{"post":{"operationId":"create-order",
                  "requestBody":{"content":{"application/json":{}}},
                  "responses":{"201":{"description":"created"},"202":{"description":"accepted"}}}},
                "/catalog":{"get":{"operationId":"catalog-list","responses":{"200":{"description":"ok"}}}}
              }
            }
        """.trimIndent()

        val README_DOCUMENT = """
            # Commerce Service
            GET /catalog - 카탈로그 조회
            POST /orders - 주문 생성
            재고는 음수가 되지 않는다.
            결제 실패 시 재시도 정책을 적용한다.
        """.trimIndent()

        val BRIEF = mapOf(
            "domainTerms" to listOf("order"),
            "invariants" to listOf("동일 멱등성 키의 주문은 한 번만 생성된다."),
            "components" to listOf("kafka"),
            "workflows" to listOf(mapOf("title" to "주문 생성", "steps" to listOf("POST /orders"))),
        )

        @JvmStatic
        @DynamicPropertySource
        fun accessProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.access.mode") { "SECURED" }
            registry.add("arl.access.viewer-token") { "viewer-knowledge-token" }
            registry.add("arl.access.profile-editor-token") { "profile-editor-knowledge-token" }
            registry.add("arl.access.executor-token") { "executor-knowledge-token" }
        }
    }
}
