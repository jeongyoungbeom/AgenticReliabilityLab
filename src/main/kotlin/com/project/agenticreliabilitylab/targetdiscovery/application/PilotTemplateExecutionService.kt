package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightService
import com.project.agenticreliabilitylab.targetcredential.application.TargetCredentialPreflightStatus
import com.project.agenticreliabilitylab.testspec.application.CreateTestSpecification
import com.project.agenticreliabilitylab.testspec.application.TestSpecificationService
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import org.springframework.stereotype.Service

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
) {
    fun execute(command: ExecutePilotTemplates, actor: String, correlationId: String): PilotTemplateExecution {
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
        val ready = discovery.find(command.targetSystemId).candidates
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

        // TestSpecificationService owns the per-Target execution slot. Calling it in selected order makes one
        // Target's writes strictly sequential; one outcome can fail without erasing earlier outcomes.
        return PilotTemplateExecution(
            targetSystemId = command.targetSystemId,
            outcomes = selected.map { candidate -> executeOne(command, candidate.id, actor, correlationId) },
        )
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
            confirmationFor(specification.risk),
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

    private fun confirmationFor(risk: SpecRisk): String = when (risk) {
        SpecRisk.SAFE -> "APPROVE_SAFE_TEST_SPECIFICATION"
        SpecRisk.MODERATE -> "APPROVE_MODERATE_TEST_SPECIFICATION"
        SpecRisk.DESTRUCTIVE -> "APPROVE_DESTRUCTIVE_TEST_SPECIFICATION"
    }

    private companion object {
        const val REQUIRED_CONFIRMATION = "EXECUTE_PILOT_TEMPLATES"
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 120
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,$MAX_IDEMPOTENCY_KEY_LENGTH}")
    }
}
