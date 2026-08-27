package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightService
import com.project.agenticreliabilitylab.targetdiscovery.application.port.PilotTestSessionStore
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSession
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItem
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItemStatus
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionStatus
import com.project.agenticreliabilitylab.testspec.application.TestSpecificationService
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

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

    private fun requestHash(targetSystemId: String, candidates: List<String>): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((targetSystemId + "|" + candidates.joinToString("|")).toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}
