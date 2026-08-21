package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.targetintelligence.application.port.TargetKnowledgeSnapshotStore
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceDocument
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceType
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeContent
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeSnapshot
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.infrastructure.JdbcTargetProfileRepository
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecProposalModel
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecProposalModelRequest
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecProposalModelResponse
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 20: the model's proposals are recorded with an outcome, and only a validator-accepted one is promoted. */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSpecGenerationApiIntegrationTests.FakeProposalModelConfiguration::class)
class TestSpecGenerationApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var profileRepository: JdbcTargetProfileRepository

    @Autowired
    private lateinit var snapshotStore: TargetKnowledgeSnapshotStore

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var fakeProposalModel: FakeTestSpecProposalModel

    private val objectMapper = ObjectMapper()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private lateinit var originalProfile: TargetProfileVersion
    private lateinit var executionProfile: TargetProfileVersion
    private lateinit var snapshotId: UUID

    @BeforeEach
    fun enableGenerationFixtures() {
        fakeProposalModel.calls.set(0)
        fakeProposalModel.nextResponse = FakeTestSpecProposalModel.CANNED_RESPONSE
        originalProfile = profileRepository.findActive(TARGET_ID) ?: error("Test target must have an active Profile")
        executionProfile = executionProfileFrom(originalProfile)
        assertTrue(profileRepository.createIfAbsent(executionProfile))
        assertTrue(profileRepository.activate(TARGET_ID, executionProfile.id, "phase20-test", Instant.now()))
        snapshotId = UUID.randomUUID()
        val snapshot = TargetKnowledgeSnapshot(
            id = snapshotId,
            targetSystemId = TARGET_ID,
            profileVersionId = executionProfile.id,
            checksum = "a".repeat(64),
            extractionVersion = "phase20-test-v1",
            content = TargetKnowledgeContent(
                sources = listOf(
                    KnowledgeSourceDocument(
                        type = KnowledgeSourceType.BRIEF,
                        byteCount = 0,
                        checksum = "b".repeat(64),
                    ),
                ),
                operations = emptyList(),
                workflows = emptyList(),
                domainHypotheses = emptyList(),
                invariants = emptyList(),
                riskSignals = emptyList(),
                warnings = emptyList(),
            ),
            createdBy = "phase20-test",
            createdCorrelationId = "phase20-setup",
            createdAt = Instant.now(),
        )
        assertTrue(snapshotStore.createIfAbsent(snapshot))
        assertTrue(snapshotStore.confirm(snapshotId, "phase20-test", "phase20-setup", Instant.now()))
    }

    @AfterEach
    fun restoreProfileAndCleanRuns() {
        profileRepository.activate(TARGET_ID, originalProfile.id, "phase20-test", Instant.now())
        jdbcClient.sql("delete from test_spec_generation_candidate").update()
        jdbcClient.sql("delete from test_spec_generation_run").update()
        jdbcClient.sql("delete from test_specification").update()
    }

    @Test
    fun `records every proposal and promotes only the one the validator accepts`() {
        val started = startGeneration("phase20-run-001")
        assertEquals(202, started.statusCode(), started.body())
        val runId = field(started.body(), "id")

        val completed = awaitTerminalRun(runId)
        assertContains(completed.body(), "\"status\":\"COMPLETED\"")
        val candidates = objectMapper.readTree(completed.body()).path("candidates")
        assertEquals(2, candidates.size(), completed.body())

        val accepted = candidates.values().first { it.path("specKey").asString() == "model-proposed-consistency" }
        assertEquals("ACCEPTED", accepted.path("outcome").asString())
        assertTrue(accepted.path("specificationId").asString().isNotBlank())

        val rejected = candidates.values().first { it.path("specKey").asString() == "model-proposed-invalid" }
        assertEquals("REJECTED", rejected.path("outcome").asString())
        assertContains(rejected.path("rejectionReason").asString(), "not registered")
        assertTrue(rejected.path("specificationId").isNull)

        assertEquals(
            1L,
            jdbcClient.sql("select count(*) from test_specification where spec_key = 'model-proposed-consistency'")
                .query(Long::class.java)
                .single(),
        )
    }

    @Test
    fun `returns the same run for a repeated idempotency key without calling the model again`() {
        val first = startGeneration("phase20-run-shared")
        awaitTerminalRun(field(first.body(), "id"))
        assertEquals(1, fakeProposalModel.calls.get())

        val second = startGeneration("phase20-run-shared")

        assertEquals(field(first.body(), "id"), field(second.body(), "id"))
        assertEquals(1, fakeProposalModel.calls.get())
    }

    @Test
    fun `completes the run and keeps the valid candidate when another candidate is too long to store`() {
        fakeProposalModel.nextResponse = """
            {"specifications":[
              {
                "specKey":"model-proposed-oversized-title",
                "version":1,
                "title":"${"x".repeat(TOO_LONG_TITLE_CHARACTERS)}",
                "category":"CONSISTENCY",
                "risk":"SAFE",
                "workload":[{"kind":"WAIT","name":"settle","duration":0}],
                "observations":[{"id":"responseCount","source":"RESPONSES","expr":"count(work[*].status)"}],
                "invariants":[
                  {"id":"noResponse","description":"unused","condition":"responseCount == 0"}
                ],
                "policy":{"trials":1},
                "cleanup":{"method":"NOT_REQUIRED"}
              },
              {
                "specKey":"model-proposed-valid-alongside-oversized",
                "version":1,
                "title":"Model proposed a valid consistency check",
                "category":"CONSISTENCY",
                "risk":"SAFE",
                "workload":[{"kind":"WAIT","name":"settle","duration":0}],
                "observations":[{"id":"responseCount","source":"RESPONSES","expr":"count(work[*].status)"}],
                "invariants":[
                  {"id":"noResponse","description":"unused","condition":"responseCount == 0"}
                ],
                "policy":{"trials":1},
                "cleanup":{"method":"NOT_REQUIRED"}
              }
            ]}
        """.trimIndent()

        val started = startGeneration("phase20-run-oversized-title")
        val completed = awaitTerminalRun(field(started.body(), "id"))
        assertContains(completed.body(), "\"status\":\"COMPLETED\"", message = completed.body())

        val candidates = objectMapper.readTree(completed.body()).path("candidates")
        assertEquals(2, candidates.size(), completed.body())

        val tooLong = candidates.values().first { it.path("specKey").asString() == "model-proposed-oversized-title" }
        assertEquals("REJECTED", tooLong.path("outcome").asString())
        assertContains(tooLong.path("rejectionReason").asString(), "characters")

        val valid = candidates.values()
            .first { it.path("specKey").asString() == "model-proposed-valid-alongside-oversized" }
        assertEquals("ACCEPTED", valid.path("outcome").asString())
        assertTrue(valid.path("specificationId").asString().isNotBlank())
        assertEquals(
            1L,
            jdbcClient.sql(
                "select count(*) from test_specification where spec_key = 'model-proposed-valid-alongside-oversized'",
            ).query(Long::class.java).single(),
        )
    }

    @Test
    fun `rejects reusing an idempotency key against a different Knowledge Snapshot`() {
        val first = startGeneration("phase20-run-snapshot-reuse")
        awaitTerminalRun(field(first.body(), "id"))

        val otherSnapshotId = UUID.randomUUID()
        val otherSnapshot = TargetKnowledgeSnapshot(
            id = otherSnapshotId,
            targetSystemId = TARGET_ID,
            profileVersionId = executionProfile.id,
            checksum = "c".repeat(64),
            extractionVersion = "phase20-test-v1",
            content = TargetKnowledgeContent(
                sources = emptyList(),
                operations = emptyList(),
                workflows = emptyList(),
                domainHypotheses = emptyList(),
                invariants = emptyList(),
                riskSignals = emptyList(),
                warnings = emptyList(),
            ),
            createdBy = "phase20-test",
            createdCorrelationId = "phase20-setup",
            createdAt = Instant.now(),
        )
        assertTrue(snapshotStore.createIfAbsent(otherSnapshot))
        assertTrue(snapshotStore.confirm(otherSnapshotId, "phase20-test", "phase20-setup", Instant.now()))

        val conflict = post(
            "/api/targets/$TARGET_ID/test-specification-generations",
            objectMapper.writeValueAsString(mapOf("knowledgeSnapshotId" to otherSnapshotId.toString())),
            "phase20-run-snapshot-reuse",
        )

        assertEquals(409, conflict.statusCode(), conflict.body())
        assertEquals("TEST_SPEC_GENERATION_IDEMPOTENCY_CONFLICT", field(conflict.body(), "code"))
    }

    private fun startGeneration(idempotencyKey: String): HttpResponse<String> = post(
        "/api/targets/$TARGET_ID/test-specification-generations",
        objectMapper.writeValueAsString(mapOf("knowledgeSnapshotId" to snapshotId.toString())),
        idempotencyKey,
    )

    private fun awaitTerminalRun(runId: String): HttpResponse<String> {
        val deadline = System.nanoTime() + Duration.ofSeconds(AWAIT_TIMEOUT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            val response = get("/api/test-specification-generations/$runId")
            val status = objectMapper.readTree(response.body()).path("status").asString()
            if (status == "COMPLETED" || status == "FAILED") return response
            Thread.sleep(AWAIT_POLL_MILLIS)
        }
        error("Test specification generation run '$runId' did not reach a terminal state in time")
    }

    private fun executionProfileFrom(base: TargetProfileVersion): TargetProfileVersion = base.copy(
        id = UUID.randomUUID(),
        status = TargetProfileStatus.DRAFT,
        checksum = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
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
                maxTrials = 3,
                stateChangingAllowed = false,
                reset = null,
            ),
        ),
        activatedBy = null,
        activatedAt = null,
    )

    private fun get(path: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(path: String, body: String, idempotencyKey: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun field(body: String, name: String): String =
        objectMapper.readValue(body, Map::class.java)[name] as String

    private companion object {
        const val TARGET_ID = "contract-test-target"
        const val AWAIT_TIMEOUT_SECONDS = 5L
        const val AWAIT_POLL_MILLIS = 25L
        const val TOO_LONG_TITLE_CHARACTERS = 600
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeProposalModelConfiguration {
        @Bean
        @Primary
        fun fakeTestSpecProposalModel(): FakeTestSpecProposalModel = FakeTestSpecProposalModel()
    }
}

class FakeTestSpecProposalModel : TestSpecProposalModel {
    val calls = AtomicInteger(0)
    var nextResponse: String = CANNED_RESPONSE

    override fun propose(request: TestSpecProposalModelRequest): TestSpecProposalModelResponse {
        calls.incrementAndGet()
        return TestSpecProposalModelResponse(content = nextResponse)
    }

    companion object {
        val CANNED_RESPONSE = """
            {"specifications":[
              {
                "specKey":"model-proposed-consistency",
                "version":1,
                "title":"Model proposed consistency check",
                "category":"CONSISTENCY",
                "risk":"SAFE",
                "workload":[{"kind":"WAIT","name":"settle","duration":0}],
                "observations":[{"id":"responseCount","source":"RESPONSES","expr":"count(work[*].status)"}],
                "invariants":[
                  {
                    "id":"noUnexpectedResponse",
                    "description":"No response was expected from a wait-only workload",
                    "condition":"responseCount == 0"
                  }
                ],
                "policy":{
                  "trials":1,
                  "aggregation":"ANY_VIOLATION_FAILS",
                  "stopPolicy":"STOP_ON_FIRST_VIOLATION",
                  "cleanupTiming":"AFTER_ALL",
                  "interval":0
                },
                "cleanup":{"method":"NOT_REQUIRED"}
              },
              {
                "specKey":"model-proposed-invalid",
                "version":1,
                "title":"Model proposed a call outside the active profile",
                "category":"CONTRACT_INPUT",
                "risk":"SAFE",
                "setup":[{"name":"wipe","call":{"method":"POST","path":"/admin/wipe"}}],
                "observations":[{"id":"responseCount","source":"RESPONSES","expr":"count(work[*].status)"}],
                "invariants":[{"id":"noResponse","description":"unused","condition":"responseCount == 0"}],
                "policy":{"trials":1},
                "cleanup":{"method":"NOT_REQUIRED"}
              }
            ]}
        """.trimIndent()
    }
}
