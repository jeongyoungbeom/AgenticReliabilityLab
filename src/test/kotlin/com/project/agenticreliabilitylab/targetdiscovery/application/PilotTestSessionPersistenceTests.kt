package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSession
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItem
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItemStatus
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionStatus
import com.project.agenticreliabilitylab.targetdiscovery.infrastructure.JdbcPilotTestSessionRepository
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PilotTestSessionPersistenceTests {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var sessions: JdbcPilotTestSessionRepository

    private lateinit var profileVersionId: UUID

    @BeforeEach
    fun createReferencedProfileVersion() {
        profileVersionId = UUID.randomUUID()
        jdbc.sql(
            """
            insert into target_profile_version (
                id, target_system_id, source, status, checksum, config_json, created_by, created_at
            ) values (
                :id, 'contract-test-target', 'USER_IMPORT', 'ACTIVE', :checksum, '{}', 'test', :now
            )
            """,
        ).params(
            mapOf(
                "id" to profileVersionId,
                "checksum" to "pilot-session-${profileVersionId.toString().replace("-", "")}",
                "now" to Instant.parse("2026-08-27T00:00:00Z"),
            ),
        ).update()
    }

    @Test
    fun `persists a completed human-approved selection and its ordered result references`() {
        val createdAt = Instant.parse("2026-08-27T00:00:00Z")
        val completedAt = createdAt.plusSeconds(5)
        val session = newSession(createdAt)
        val item = PilotTestSessionItem(
            sessionId = session.id,
            sequenceNumber = 1,
            candidateId = "availability",
            specificationId = null,
            testSpecRunId = null,
            status = PilotTestSessionItemStatus.COMPLETED,
            resultOutcome = TrialOutcome.PASSED,
            cleanupVerified = true,
            failureCode = null,
            failureMessage = null,
            completedAt = completedAt,
        )

        sessions.create(session)

        assertTrue(
            sessions.complete(
                session.id,
                PilotTestSessionStatus.COMPLETED,
                TrialOutcome.PASSED,
                true,
                completedAt,
                null,
                listOf(item),
            ),
        )
        val completed = assertNotNull(sessions.findById(session.id))
        assertEquals(PilotTestSessionStatus.COMPLETED, completed.status)
        assertEquals(TrialOutcome.PASSED, completed.resultOutcome)
        assertEquals(true, completed.cleanupVerified)
        assertEquals(listOf(item), sessions.findItems(session.id))
        assertTrue(sessions.findByTarget("contract-test-target", 10).any { it.id == session.id })
        assertEquals(
            session.id,
            sessions.findByTargetAndIdempotencyKey("contract-test-target", session.idempotencyKey)?.id,
        )
    }

    @Test
    fun `marks an interrupted selection recovery-required on restart`() {
        val session = newSession(Instant.parse("2026-08-27T00:00:00Z"))
        sessions.create(session)

        assertEquals(1, sessions.recoverIncompleteSessions(Instant.parse("2026-08-27T00:01:00Z")))

        val recovered = assertNotNull(sessions.findById(session.id))
        assertEquals(PilotTestSessionStatus.RECOVERY_REQUIRED, recovered.status)
        assertEquals(TrialOutcome.INCONCLUSIVE, recovered.resultOutcome)
        assertEquals(false, recovered.cleanupVerified)
    }

    private fun newSession(createdAt: Instant) = PilotTestSession(
        id = UUID.randomUUID(),
        targetSystemId = "contract-test-target",
        profileVersionId = profileVersionId,
        status = PilotTestSessionStatus.RUNNING,
        idempotencyKey = "pilot-session-${UUID.randomUUID()}",
        requestHash = "a".repeat(64),
        createdBy = "test",
        createdCorrelationId = "pilot-session-test",
        createdAt = createdAt,
    )
}
