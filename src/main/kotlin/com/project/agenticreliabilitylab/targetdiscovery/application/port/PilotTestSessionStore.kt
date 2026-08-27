package com.project.agenticreliabilitylab.targetdiscovery.application.port

import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSession
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItem
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionStatus
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import java.time.Instant
import java.util.UUID

/** Persistence boundary for the immutable summary of one Pilot template selection. */
interface PilotTestSessionStore {
    fun create(session: PilotTestSession)
    fun findById(id: UUID): PilotTestSession?
    fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): PilotTestSession?
    fun findByTarget(targetSystemId: String, limit: Int): List<PilotTestSession>
    fun findItems(sessionId: UUID): List<PilotTestSessionItem>
    fun complete(
        id: UUID,
        status: PilotTestSessionStatus,
        resultOutcome: TrialOutcome,
        cleanupVerified: Boolean,
        completedAt: Instant,
        failure: String?,
        items: List<PilotTestSessionItem>,
    ): Boolean

    /** A crash can leave external Target state unknown, so the session must not look complete after restart. */
    fun recoverIncompleteSessions(completedAt: Instant): Int
}
