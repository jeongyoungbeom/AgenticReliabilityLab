package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.api.common.ApiExceptionHandler
import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.common.ResourceNotFoundException
import com.project.agenticreliabilitylab.diagnosis.FailureDiagnosis
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightService
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightResult
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightStatus
import com.project.agenticreliabilitylab.targetdiscovery.application.port.PilotTestSessionStore
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSession
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItem
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItemStatus
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionStatus
import com.project.agenticreliabilitylab.testspec.application.TestSpecificationService
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PilotTemplateExecutionServiceTests {
    @Test
    fun `replays an existing matching session without touching discovery credentials or the Target`() {
        val discovery = Mockito.mock(PilotDiscoveryService::class.java)
        val specifications = Mockito.mock(TestSpecificationService::class.java)
        val preflight = Mockito.mock(TargetCredentialPreflightService::class.java)
        val templates = Mockito.mock(PilotTestTemplateFactory::class.java)
        val sessions = Mockito.mock(PilotTestSessionStore::class.java)
        val session = PilotTestSession(
            id = UUID.randomUUID(),
            targetSystemId = "sideproject-local",
            profileVersionId = UUID.randomUUID(),
            status = PilotTestSessionStatus.COMPLETED,
            idempotencyKey = "pilot-template-replay-1",
            requestHash = requestHash("sideproject-local", listOf("availability")),
            createdBy = "operator",
            createdCorrelationId = "test",
            createdAt = Instant.EPOCH,
            resultOutcome = TrialOutcome.PASSED,
            cleanupVerified = true,
            completedAt = Instant.EPOCH.plusSeconds(1),
        )
        val item = PilotTestSessionItem(
            sessionId = session.id,
            sequenceNumber = 1,
            candidateId = "availability",
            specificationId = UUID.randomUUID(),
            testSpecRunId = UUID.randomUUID(),
            status = PilotTestSessionItemStatus.COMPLETED,
            resultOutcome = TrialOutcome.PASSED,
            cleanupVerified = true,
            failureCode = null,
            failureMessage = null,
            completedAt = Instant.EPOCH.plusSeconds(1),
        )
        Mockito.`when`(sessions.findByTargetAndIdempotencyKey("sideproject-local", session.idempotencyKey))
            .thenReturn(session)
        Mockito.`when`(sessions.findItems(session.id)).thenReturn(listOf(item))
        val service = PilotTemplateExecutionService(
            discovery,
            specifications,
            preflight,
            templates,
            sessions,
            IdentifierGenerator { error("A replay must not create an identifier") },
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        )

        val result = service.execute(
            ExecutePilotTemplates(
                "sideproject-local",
                listOf("availability"),
                "EXECUTE_PILOT_TEMPLATES",
                session.idempotencyKey,
                "credential-session",
            ),
            "operator",
            "test",
        )

        assertEquals(session, result.session)
        assertEquals(listOf(item), result.items)
        Mockito.verifyNoInteractions(discovery, specifications, preflight, templates)
    }

    @Test
    fun `records no cleanup verification and the candidate failure when no Test Spec Run starts`() {
        val discovery = Mockito.mock(PilotDiscoveryService::class.java)
        val specifications = Mockito.mock(TestSpecificationService::class.java)
        val preflight = Mockito.mock(TargetCredentialPreflightService::class.java)
        val templates = Mockito.mock(PilotTestTemplateFactory::class.java)
        val sessions = InMemoryPilotTestSessionStore()
        val sessionId = UUID.randomUUID()
        Mockito.`when`(discovery.find("sideproject-local")).thenReturn(readyDiscovery())
        Mockito.`when`(preflight.preflight("sideproject-local", "credential-session"))
            .thenReturn(listOf(readyHarnessPreflight()))
        Mockito.`when`(templates.document("availability", 1))
            .thenThrow(ClientRequestException("TEST_SPECIFICATION_RECOVERY_REQUIRED", "Target requires recovery"))
        val service = newService(discovery, specifications, preflight, templates, sessions, sessionId)

        val result = service.execute(command(), "operator", "test")

        assertEquals(PilotTestSessionStatus.COMPLETED, result.session.status)
        assertEquals(TrialOutcome.INCONCLUSIVE, result.session.resultOutcome)
        assertEquals(null, result.session.cleanupVerified)
        assertEquals("이전 실행의 정리 상태를 확인해야 합니다.", result.session.failure)
        assertEquals(PilotTestSessionItemStatus.FAILED, result.items.single().status)
        assertEquals("TEST_SPECIFICATION_RECOVERY_REQUIRED", result.items.single().failureCode)
        assertEquals("RECOVERY", result.items.single().diagnosis?.stage?.name)
    }

    @Test
    fun `does not persist an unexpected exception message in a Pilot session result`() {
        val discovery = Mockito.mock(PilotDiscoveryService::class.java)
        val specifications = Mockito.mock(TestSpecificationService::class.java)
        val preflight = Mockito.mock(TargetCredentialPreflightService::class.java)
        val templates = Mockito.mock(PilotTestTemplateFactory::class.java)
        val sessions = InMemoryPilotTestSessionStore()
        Mockito.`when`(discovery.find("sideproject-local")).thenReturn(readyDiscovery())
        Mockito.`when`(preflight.preflight("sideproject-local", "credential-session"))
            .thenReturn(listOf(readyHarnessPreflight()))
        val unsafeTargetFailure = "Authorization: Bearer pilot-secret response body={\"token\":\"response-secret\"}"
        Mockito.`when`(templates.document("availability", 1)).thenThrow(IllegalStateException(unsafeTargetFailure))
        val service = newService(discovery, specifications, preflight, templates, sessions, UUID.randomUUID())

        val result = service.execute(command(), "operator", "test")

        assertEquals("PILOT_TEMPLATE_EXECUTION_FAILED", result.items.single().failureCode)
        assertEquals("파일럿 실행을 완료하지 못했습니다.", result.items.single().failureMessage)
        assertEquals("파일럿 실행을 완료하지 못했습니다.", result.session.failure)
        assertEquals("EXECUTION", result.items.single().diagnosis?.stage?.name)
        assertFalse(result.items.single().toString().contains("pilot-secret"))
        assertFalse(result.items.single().toString().contains("response-secret"))
    }

    @Test
    fun `reports a missing Pilot session as HTTP not found`() {
        val service = newService(
            Mockito.mock(PilotDiscoveryService::class.java),
            Mockito.mock(TestSpecificationService::class.java),
            Mockito.mock(TargetCredentialPreflightService::class.java),
            Mockito.mock(PilotTestTemplateFactory::class.java),
            InMemoryPilotTestSessionStore(),
            UUID.randomUUID(),
        )

        val exception = assertIs<ResourceNotFoundException>(
            kotlin.runCatching { service.findSession(UUID.randomUUID()) }.exceptionOrNull(),
        )
        assertEquals(HttpStatus.NOT_FOUND, ApiExceptionHandler().notFound(exception).statusCode)
    }

    private fun newService(
        discovery: PilotDiscoveryService,
        specifications: TestSpecificationService,
        preflight: TargetCredentialPreflightService,
        templates: PilotTestTemplateFactory,
        sessions: PilotTestSessionStore,
        sessionId: UUID,
    ) = PilotTemplateExecutionService(
        discovery,
        specifications,
        preflight,
        templates,
        sessions,
        IdentifierGenerator { sessionId },
        Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC),
    )

    private fun command() = ExecutePilotTemplates(
        "sideproject-local",
        listOf("availability"),
        "EXECUTE_PILOT_TEMPLATES",
        "pilot-template-test-1",
        "credential-session",
    )

    private fun readyDiscovery() = PilotDiscovery(
        targetSystemId = "sideproject-local",
        profileVersionId = UUID.randomUUID().toString(),
        openApiPath = "/api-docs",
        openApiPaths = listOf("/api-docs"),
        snapshotId = "snapshot-1",
        snapshotChecksum = "checksum-1",
        snapshotChecksums = listOf("checksum-1"),
        discoveredOperations = emptyList(),
        ignoredOperationCount = 0,
        candidates = listOf(
            PilotTestCandidate(
                "availability",
                "Availability",
                "Safe read check",
                PilotCandidateReadiness.READY,
                emptyList(),
                emptyList(),
            ),
        ),
    )

    private fun readyHarnessPreflight() = TargetCredentialPreflightResult(
        role = "harness",
        status = TargetCredentialPreflightStatus.READY,
        method = "GET",
        path = "/api/harness/state",
        httpStatus = 200,
    )

    private fun requestHash(targetSystemId: String, candidates: List<String>): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((targetSystemId + "|" + candidates.joinToString("|")).toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

private class InMemoryPilotTestSessionStore : PilotTestSessionStore {
    private val sessions = mutableMapOf<UUID, PilotTestSession>()
    private val items = mutableMapOf<UUID, List<PilotTestSessionItem>>()

    override fun create(session: PilotTestSession) {
        sessions[session.id] = session
    }

    override fun findById(id: UUID): PilotTestSession? = sessions[id]

    override fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): PilotTestSession? =
        sessions.values.singleOrNull { it.targetSystemId == targetSystemId && it.idempotencyKey == idempotencyKey }

    override fun findByTarget(targetSystemId: String, limit: Int): List<PilotTestSession> =
        sessions.values.filter { it.targetSystemId == targetSystemId }.take(limit)

    override fun findItems(sessionId: UUID): List<PilotTestSessionItem> = items[sessionId].orEmpty()

    override fun complete(
        id: UUID,
        status: PilotTestSessionStatus,
        resultOutcome: TrialOutcome,
        cleanupVerified: Boolean?,
        completedAt: Instant,
        failure: String?,
        diagnosis: FailureDiagnosis?,
        items: List<PilotTestSessionItem>,
    ): Boolean {
        val existing = sessions[id] ?: return false
        sessions[id] = existing.copy(
            status = status,
            resultOutcome = resultOutcome,
            cleanupVerified = cleanupVerified,
            completedAt = completedAt,
            failure = failure,
            diagnosis = diagnosis,
        )
        this.items[id] = items
        return true
    }

    override fun recoverIncompleteSessions(completedAt: Instant): Int = 0
}
