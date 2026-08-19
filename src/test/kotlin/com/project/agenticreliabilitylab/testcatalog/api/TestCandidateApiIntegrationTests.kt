package com.project.agenticreliabilitylab.testcatalog.api

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
class TestCandidateApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var profileRepository: JdbcTargetProfileRepository

    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val objectMapper = ObjectMapper()

    @Test
    fun `binds registered read-only operations and leaves everything else unbound with a reason`() {
        val generation = generateFrom(snapshotId(createSnapshot(README_DOCUMENT)))

        assertEquals(201, generation.statusCode(), generation.body())
        val body = generation.body()
        assertContains(body, "\"profileVersionActive\":true")
        assertContains(body, "\"generatorVersion\":\"test-candidate-rules-v1\"")

        assertContains(body, "\"kind\":\"READ_ONLY_BATCH\"")
        assertContains(body, "\"targetTestCandidateIds\":[\"catalog-list\"]")
        assertContains(body, "\"readiness\":\"EXECUTABLE\"")

        assertContains(body, "\"category\":\"CONTRACT_INPUT\"")
        assertContains(body, "\"unresolvedReason\":\"NO_SAFE_EXECUTION_PATH\"")
        assertContains(body, "\"readiness\":\"NEEDS_USER_INPUT\"")

        assertContains(body, "\"category\":\"CONCURRENCY\"")
        assertContains(body, "\"experimentType\":\"STOCK_CONCURRENCY\"")

        assertContains(body, "\"unresolvedReason\":\"UNSUPPORTED_TEST_TYPE\"")
        assertContains(body, "\"readiness\":\"UNSUPPORTED\"")
    }

    @Test
    fun `returns the existing generation when the same snapshot is generated again`() {
        val snapshot = snapshotId(createSnapshot("# Idempotent\nGET /catalog - catalog\n"))

        val first = generateFrom(snapshot)
        val second = generateFrom(snapshot)

        assertEquals(201, first.statusCode(), first.body())
        assertEquals(201, second.statusCode(), second.body())
        assertEquals(identifier(first.body()), identifier(second.body()))
    }

    @Test
    fun `accepts a directly requested test and names what is still missing`() {
        val snapshot = snapshotId(createSnapshot("# Direct\nGET /catalog - catalog\n"))

        val bound = post(
            "/api/test-candidate-requests",
            objectMapper.writeValueAsString(
                mapOf(
                    "knowledgeSnapshotId" to snapshot,
                    "category" to "CONCURRENCY",
                    "title" to "상품 재고 동시 차감 테스트",
                    "description" to "같은 재고에 병렬 주문을 보낸다",
                    "invariantStatement" to "재고는 음수가 되지 않는다",
                ),
            ),
        )
        assertEquals(201, bound.statusCode(), bound.body())
        assertContains(bound.body(), "\"source\":\"DIRECT_REQUEST\"")
        assertContains(bound.body(), "\"experimentType\":\"STOCK_CONCURRENCY\"")
        assertContains(bound.body(), "\"readiness\":\"EXECUTABLE\"")

        val missingInvariant = post(
            "/api/test-candidate-requests",
            objectMapper.writeValueAsString(
                mapOf(
                    "knowledgeSnapshotId" to snapshot,
                    "category" to "CONSISTENCY",
                    "title" to "정합성 확인 요청",
                    "description" to "이벤트 반영 후 상태 비교",
                ),
            ),
        )
        assertEquals(201, missingInvariant.statusCode(), missingInvariant.body())
        assertContains(missingInvariant.body(), "\"unresolvedReason\":\"MISSING_INVARIANT\"")
        assertContains(missingInvariant.body(), "\"readiness\":\"NEEDS_USER_INPUT\"")
    }

    @Test
    fun `stores a candidate whose generated title would overflow the column`() {
        val longWorkflowTitle = "주".repeat(MAX_TITLE_CHARACTERS)
        val snapshot = post(
            "/api/target-knowledge-snapshots",
            objectMapper.writeValueAsString(
                mapOf(
                    "targetSystemId" to TARGET_ID,
                    "readmeDocument" to "# Overflow\nGET /catalog - catalog\n",
                    "brief" to mapOf(
                        "workflows" to listOf(mapOf("title" to longWorkflowTitle, "steps" to listOf("POST /orders"))),
                    ),
                ),
            ),
        )

        val generation = generateFrom(snapshotId(snapshot))

        assertEquals(201, generation.statusCode(), generation.body())
        assertContains(generation.body(), "\"category\":\"WORKFLOW\"")
    }

    @Test
    fun `recomputes readiness from current capability instead of the stored value`() {
        val generationId = identifier(generateFrom(snapshotId(createSnapshot(README_DOCUMENT))).body())
        val active = profileRepository.findActive(TARGET_ID) ?: error("Test target must have an active Profile")
        val disabled = active.copy(
            id = UUID.randomUUID(),
            checksum = "phase12-generic-execution-disabled",
            status = TargetProfileStatus.DRAFT,
            activatedBy = null,
            activatedAt = null,
            definition = active.definition.copy(
                genericHttp = active.definition.genericHttp?.copy(executionEnabled = false),
                experiment = active.definition.experiment?.copy(executionEnabled = false),
            ),
        )

        try {
            profileRepository.createIfAbsent(disabled)
            profileRepository.activate(TARGET_ID, disabled.id, "phase12-test", Instant.now())

            val reread = get("/api/test-candidate-generations/$generationId")

            assertEquals(200, reread.statusCode(), reread.body())
            assertContains(reread.body(), "\"profileVersionActive\":false")
            assertContains(reread.body(), "\"readiness\":\"CAPABILITY_UNAVAILABLE\"")
            assertFalse(reread.body().contains("\"readiness\":\"EXECUTABLE\""))
        } finally {
            profileRepository.activate(TARGET_ID, active.id, "phase12-test", Instant.now())
        }
    }

    private fun createSnapshot(readme: String): HttpResponse<String> = post(
        "/api/target-knowledge-snapshots",
        objectMapper.writeValueAsString(
            mapOf(
                "targetSystemId" to TARGET_ID,
                "openApiDocument" to OPENAPI_DOCUMENT,
                "readmeDocument" to readme,
                "brief" to BRIEF,
            ),
        ),
    )

    private fun generateFrom(knowledgeSnapshotId: String): HttpResponse<String> = post(
        "/api/test-candidate-generations",
        """{"knowledgeSnapshotId":"$knowledgeSnapshotId"}""",
    )

    private fun snapshotId(response: HttpResponse<String>): String {
        assertEquals(201, response.statusCode(), response.body())
        return identifier(response.body())
    }

    private fun identifier(body: String): String =
        """"id":"([^"]+)""".toRegex().find(body)?.groupValues?.get(1)
            ?: error("Identifier was absent: $body")

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
        .header("Authorization", "Bearer profile-editor-catalog-token")

    private companion object {
        const val TARGET_ID = "contract-test-target"
        const val MAX_TITLE_CHARACTERS = 200

        val OPENAPI_DOCUMENT = """
            {
              "openapi":"3.1.0",
              "info":{"title":"Commerce"},
              "paths":{
                "/catalog":{"get":{"operationId":"catalog-list","responses":{"200":{"description":"ok"}}}},
                "/orders":{"post":{"operationId":"create-order",
                  "requestBody":{"content":{"application/json":{}}},
                  "responses":{"201":{"description":"created"},"202":{"description":"accepted"}}}}
              }
            }
        """.trimIndent()

        val README_DOCUMENT = """
            # Commerce Service
            GET /catalog - 카탈로그 조회
            POST /orders - 주문 생성
            재고는 음수가 되지 않는다.
            결제 실패 시 재시도 정책을 적용한다.
            동일 멱등성 키의 주문은 한 번만 생성된다.
        """.trimIndent()

        val BRIEF = mapOf(
            "domainTerms" to listOf("order"),
            "invariants" to listOf("재고는 음수가 되지 않는다."),
            "components" to listOf("kafka"),
            "workflows" to listOf(mapOf("title" to "주문 생성", "steps" to listOf("POST /orders"))),
        )

        @JvmStatic
        @DynamicPropertySource
        fun catalogProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.access.mode") { "SECURED" }
            registry.add("arl.access.viewer-token") { "viewer-catalog-token" }
            registry.add("arl.access.profile-editor-token") { "profile-editor-catalog-token" }
            registry.add("arl.access.executor-token") { "executor-catalog-token" }
            registry.add("arl.target-specs.registrations[0].target-system-id") { TARGET_ID }
            registry.add("arl.target-specs.registrations[0].execution-enabled") { true }
            registry.add("arl.target-specs.registrations[0].host-resource-group") { "catalog-test-resource" }
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
