package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightService
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightStatus
import com.project.agenticreliabilitylab.targetdiscovery.application.port.PilotTestSessionStore
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSession
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItem
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionItemStatus
import com.project.agenticreliabilitylab.targetdiscovery.domain.PilotTestSessionStatus
import com.project.agenticreliabilitylab.testspec.application.CreateTestSpecification
import com.project.agenticreliabilitylab.testspec.application.TestSpecificationService
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Converts a checked Pilot candidate into an approved fixed specification and runs selected candidates serially.
 *
 * Selection remains human-driven: the endpoint requires the explicit pilot confirmation and performs no discovery
 * or model-generation itself. Target credentials are checked by role but never read, logged or persisted here.
 */
@Service
class PilotTemplateExecutionService(
    private val discovery: PilotDiscoveryService,
    private val specifications: TestSpecificationService,
    private val preflight: TargetCredentialPreflightService,
    private val templates: PilotTestTemplateFactory,
    private val sessions: PilotTestSessionStore,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
) {
    fun execute(command: ExecutePilotTemplates, actor: String, correlationId: String): PilotTestSessionView {
        require(command.confirmation == REQUIRED_CONFIRMATION) {
            "confirmation must equal $REQUIRED_CONFIRMATION"
        }
        require(command.candidateIds.isNotEmpty()) { "At least one ready pilot candidate must be selected" }
        require(command.candidateIds.distinct().size == command.candidateIds.size) {
            "A pilot candidate can be selected only once"
        }
        require(IDEMPOTENCY_KEY_PATTERN.matches(command.idempotencyKey)) {
            "Idempotency-Key must contain 1 to $MAX_IDEMPOTENCY_KEY_LENGTH safe characters"
        }
        val requestHash = command.requestHash()
        sessions.findByTargetAndIdempotencyKey(command.targetSystemId, command.idempotencyKey)?.let { existing ->
            ensureSameRequest(existing, requestHash)
            return view(existing)
        }
        val catalogue = discovery.find(command.targetSystemId)
        val ready = catalogue.candidates
            .filter { candidate -> candidate.readiness == PilotCandidateReadiness.READY }
            .associateBy(PilotTestCandidate::id)
        val selected = command.candidateIds.map { candidateId ->
            ready[candidateId] ?: throw ClientRequestException(
                "PILOT_CANDIDATE_NOT_READY",
                "Pilot candidate '$candidateId' is not ready for Target '${command.targetSystemId}'",
            )
        }
        requireReadyCredentials(
            command.targetSystemId,
            selected.map(PilotTestCandidate::id),
            command.credentialSessionId,
        )

        val session = PilotTestSession(
            id = identifierGenerator.next(),
            targetSystemId = command.targetSystemId,
            profileVersionId = UUID.fromString(catalogue.profileVersionId),
            status = PilotTestSessionStatus.RUNNING,
            idempotencyKey = command.idempotencyKey,
            requestHash = requestHash,
            createdBy = actor,
            createdCorrelationId = correlationId,
            createdAt = clock.instant(),
        )
        return when (val started = createOrReplay(session)) {
            is PilotTestSessionStart.Replayed -> started.view
            is PilotTestSessionStart.Created -> completeSession(
                started.session,
                selected.map { candidate -> executeOne(command, candidate.id, actor, correlationId) },
            )
        }
    }

    private fun createOrReplay(session: PilotTestSession): PilotTestSessionStart = try {
        sessions.create(session)
        PilotTestSessionStart.Created(session)
    } catch (exception: DuplicateKeyException) {
        val existing = sessions.findByTargetAndIdempotencyKey(session.targetSystemId, session.idempotencyKey)
            ?: throw exception
        ensureSameRequest(existing, session.requestHash)
        PilotTestSessionStart.Replayed(view(existing))
    }

    private fun completeSession(
        session: PilotTestSession,
        outcomes: List<PilotTemplateExecutionOutcome>,
    ): PilotTestSessionView {
        // TestSpecificationService owns the per-Target execution slot. Calling it in selected order makes one
        // Target's writes strictly sequential; one outcome can fail without erasing earlier outcomes. The enclosing
        // session records the entire human-approved selection, including a rejected candidate that created no Run.
        val completedAt = clock.instant()
        val items = outcomes
            .mapIndexed { index, outcome -> outcome.toSessionItem(session.id, index + 1, completedAt) }
        val status = if (items.any { item -> item.status == PilotTestSessionItemStatus.RECOVERY_REQUIRED }) {
            PilotTestSessionStatus.RECOVERY_REQUIRED
        } else {
            PilotTestSessionStatus.COMPLETED
        }
        val resultOutcome = when {
            items.all { item ->
                item.status == PilotTestSessionItemStatus.COMPLETED && item.resultOutcome == TrialOutcome.PASSED
            } -> TrialOutcome.PASSED
            items.any { item -> item.resultOutcome == TrialOutcome.VIOLATED } -> TrialOutcome.VIOLATED
            else -> TrialOutcome.INCONCLUSIVE
        }
        val cleanupVerified = items.filter { item -> item.testSpecRunId != null }
            .all { item -> item.cleanupVerified == true }
        val failure = items.firstOrNull { item -> item.status == PilotTestSessionItemStatus.RECOVERY_REQUIRED }
            ?.failureMessage
        check(
            sessions.complete(session.id, status, resultOutcome, cleanupVerified, completedAt, failure, items),
        ) { "Pilot test session '${session.id}' could not be completed" }
        return findSession(session.id)
    }

    fun findSession(sessionId: UUID): PilotTestSessionView = view(
        sessions.findById(sessionId)
            ?: throw ClientRequestException(
                "PILOT_TEST_SESSION_NOT_FOUND",
                "Pilot test session '$sessionId' was not found",
            ),
    )

    fun findSessions(targetSystemId: String): List<PilotTestSessionView> =
        sessions.findByTarget(targetSystemId, MAX_LISTED_SESSIONS).map(::view)

    fun recoverIncompleteSessions() {
        sessions.recoverIncompleteSessions(clock.instant())
    }

    private fun view(session: PilotTestSession): PilotTestSessionView =
        PilotTestSessionView(session, sessions.findItems(session.id))

    private fun ensureSameRequest(session: PilotTestSession, requestHash: String) {
        if (session.requestHash != requestHash) {
            throw ClientRequestException(
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key is already associated with a different Pilot test session",
            )
        }
    }

    private fun requireReadyCredentials(
        targetSystemId: String,
        candidateIds: List<String>,
        credentialSessionId: String?,
    ) {
        val required = candidateIds.flatMap(PilotTestTemplateFactory::requiredAuthProfiles).toSet()
        val resultByRole = preflight.preflight(targetSystemId, credentialSessionId)
            .associateBy { result -> result.role }
        val nonReady = required.associateWith { role ->
            resultByRole[role]?.status ?: TargetCredentialPreflightStatus.TARGET_CREDENTIAL_MISSING
        }
            .filterValues { status -> status != TargetCredentialPreflightStatus.READY }
        if (nonReady.isNotEmpty()) {
            val firstStatus = nonReady.values.first()
            val code = when (firstStatus) {
                TargetCredentialPreflightStatus.TARGET_CREDENTIAL_MISSING -> "TARGET_CREDENTIAL_MISSING"
                TargetCredentialPreflightStatus.TARGET_CREDENTIAL_EXPIRED -> "TARGET_CREDENTIAL_EXPIRED"
                TargetCredentialPreflightStatus.TARGET_UNREACHABLE -> "TARGET_UNREACHABLE"
                TargetCredentialPreflightStatus.PREFLIGHT_NOT_CONFIGURED -> "TARGET_CREDENTIAL_PREFLIGHT_NOT_CONFIGURED"
                TargetCredentialPreflightStatus.TARGET_PREFLIGHT_FAILED -> "TARGET_PREFLIGHT_FAILED"
                TargetCredentialPreflightStatus.READY -> error("READY credentials were filtered out")
            }
            throw ClientRequestException(
                code,
                "Target credential preflight is not ready for: " +
                    nonReady.entries.sortedBy { entry -> entry.key }
                        .joinToString(", ") { (role, status) -> "$role ($status)" },
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // A batch reports each candidate instead of dropping completed ones.
    private fun executeOne(
        command: ExecutePilotTemplates,
        candidateId: String,
        actor: String,
        correlationId: String,
    ): PilotTemplateExecutionOutcome = try {
        val version = nextTemplateVersion(command.targetSystemId, candidateId)
        val specification = specifications.create(
            CreateTestSpecification(
                targetSystemId = command.targetSystemId,
                source = SpecSource.RULE_GENERATED,
                documentJson = templates.document(candidateId, version),
            ),
            actor,
            correlationId,
        ).specification
        specifications.approve(
            specification.id,
            specification.risk.confirmation(),
            actor,
            correlationId,
        )
        PilotTemplateExecutionOutcome(
            candidateId = candidateId,
            specificationId = specification.id,
            run = specifications.execute(
                specification.id,
                "${command.idempotencyKey}:$candidateId",
                actor,
                correlationId,
                command.credentialSessionId,
            ),
            failureCode = null,
            failureMessage = null,
        )
    } catch (exception: ClientRequestException) {
        PilotTemplateExecutionOutcome(candidateId, null, null, exception.code, exception.message)
    } catch (exception: IllegalArgumentException) {
        PilotTemplateExecutionOutcome(candidateId, null, null, "PILOT_TEMPLATE_REJECTED", exception.message)
    } catch (exception: Exception) {
        PilotTemplateExecutionOutcome(
            candidateId, null, null, "PILOT_TEMPLATE_EXECUTION_FAILED",
            exception.message ?: exception.javaClass.simpleName,
        )
    }

    private fun nextTemplateVersion(targetSystemId: String, candidateId: String): Int {
        val existing = specifications.findByTarget(targetSystemId)
            .filter { view -> view.specification.specKey == "pilot-$candidateId" }
        return (existing.maxOfOrNull { view -> view.specification.version } ?: 0) + 1
    }

    private companion object {
        const val MAX_LISTED_SESSIONS = 30
        const val REQUIRED_CONFIRMATION = "EXECUTE_PILOT_TEMPLATES"
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 120
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,$MAX_IDEMPOTENCY_KEY_LENGTH}")
    }
}

private sealed interface PilotTestSessionStart {
    data class Created(val session: PilotTestSession) : PilotTestSessionStart
    data class Replayed(val view: PilotTestSessionView) : PilotTestSessionStart
}

private fun ExecutePilotTemplates.requestHash(): String =
    (targetSystemId + "|" + candidateIds.joinToString("|")).sha256()

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun PilotTemplateExecutionOutcome.toSessionItem(
    sessionId: UUID,
    sequenceNumber: Int,
    completedAt: Instant,
): PilotTestSessionItem {
    val run = run?.run
    val status = when (run?.status) {
        null -> PilotTestSessionItemStatus.FAILED
        TestSpecRunStatus.RECOVERY_REQUIRED,
        TestSpecRunStatus.PENDING,
        TestSpecRunStatus.RUNNING,
        -> PilotTestSessionItemStatus.RECOVERY_REQUIRED

        TestSpecRunStatus.FAILED -> PilotTestSessionItemStatus.FAILED
        TestSpecRunStatus.COMPLETED -> PilotTestSessionItemStatus.COMPLETED
    }
    val runFailureCode = if (status == PilotTestSessionItemStatus.COMPLETED) null else {
        run?.let { value -> "TEST_SPEC_RUN_${value.status.name}" }
    }
    return PilotTestSessionItem(
        sessionId = sessionId,
        sequenceNumber = sequenceNumber,
        candidateId = candidateId,
        specificationId = specificationId,
        testSpecRunId = run?.id,
        status = status,
        resultOutcome = run?.resultOutcome,
        cleanupVerified = run?.cleanupVerified,
        failureCode = failureCode ?: runFailureCode,
        failureMessage = failureMessage ?: run?.failure,
        completedAt = completedAt,
    )
}

private fun SpecRisk.confirmation(): String = when (this) {
    SpecRisk.SAFE -> "APPROVE_SAFE_TEST_SPECIFICATION"
    SpecRisk.MODERATE -> "APPROVE_MODERATE_TEST_SPECIFICATION"
    SpecRisk.DESTRUCTIVE -> "APPROVE_DESTRUCTIVE_TEST_SPECIFICATION"
}
