package com.project.agenticreliabilitylab.targetspec.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.target.application.TargetSystemService
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetprofile.application.port.ActiveTargetProfileVersionCatalog
import com.project.agenticreliabilitylab.targetprofile.application.port.TargetApprovalAuditStore
import com.project.agenticreliabilitylab.targetprofile.domain.TargetApprovalAggregateType
import com.project.agenticreliabilitylab.targetprofile.domain.TargetApprovalAuditEvent
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanRecord
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanStatus
import com.project.agenticreliabilitylab.targetspec.application.port.FailureInjectionPlanStore
import com.project.agenticreliabilitylab.targetspec.application.port.NewFailureInjectionPlan
import com.project.agenticreliabilitylab.targetspec.application.port.NewFailureInjectionPlanCandidate
import com.project.agenticreliabilitylab.targetspec.application.model.CreateFailureInjectionPlan
import com.project.agenticreliabilitylab.targetspec.application.model.FailureInjectionPlanDetails
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestCatalog
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Stores a human-approved plan only. This service intentionally has no command,
 * HTTP transport, worker, credential, shell, Docker, or Target adapter dependency.
 */
@Service
class FailureInjectionPlanService(
    private val targetSystemService: TargetSystemService,
    private val targetTestCatalog: TargetTestCatalog,
    private val activeProfileVersions: ActiveTargetProfileVersionCatalog,
    private val repository: FailureInjectionPlanStore,
    private val approvalAuditStore: TargetApprovalAuditStore,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    fun candidates(targetSystemId: String): List<FailureInjectionCandidate> {
        requirePlanningTarget(targetSystemId)
        return targetTestCatalog.failureInjectionCandidates(targetSystemId)
    }

    fun create(command: CreateFailureInjectionPlan, idempotencyKey: String): FailureInjectionPlanDetails {
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) { "Idempotency-Key must contain 1 to 200 allowed characters" }
        requirePlanningTarget(command.targetSystemId)
        val profileVersionId = activeProfileVersions.requireActiveVersionId(command.targetSystemId)
        require(command.candidateIds.size in 1..MAX_PLAN_ITEMS && command.candidateIds.distinct().size == command.candidateIds.size) {
            "candidateIds must contain 1 to $MAX_PLAN_ITEMS distinct registered candidates"
        }
        val selected = command.candidateIds.map { id -> candidates(command.targetSystemId).associateBy { it.id }[id]
            ?: throw FailureInjectionPlanRequestException("UNKNOWN_FAILURE_INJECTION_CANDIDATE", "Candidate '$id' is not registered for Target '${command.targetSystemId}'") }
        val requestHash = (command.targetSystemId + "|" + selected.joinToString("|") { listOf(it.id, it.type, it.risk, it.title, it.recoveryExpectation).joinToString("~") }).sha256()
        repository.findByTargetAndIdempotencyKey(command.targetSystemId, idempotencyKey)?.let { existing ->
            ensureSameRequest(existing, requestHash)
            return find(existing.id)
        }
        val plan = NewFailureInjectionPlan(
            id = identifierGenerator.next(),
            targetSystemId = command.targetSystemId,
            profileVersionId = profileVersionId,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            candidates = selected.map { NewFailureInjectionPlanCandidate(identifierGenerator.next(), it) },
            createdAt = clock.instant(),
        )
        val id = try {
            transactionTemplate.execute {
                repository.findByTargetAndIdempotencyKey(command.targetSystemId, idempotencyKey)?.let {
                    ensureSameRequest(it, requestHash)
                    return@execute it.id
                }
                repository.create(plan)
                plan.id
            }
        } catch (_: DuplicateKeyException) {
            val existing = repository.findByTargetAndIdempotencyKey(command.targetSystemId, idempotencyKey)
                ?: throw FailureInjectionPlanRequestException("FAILURE_INJECTION_PLAN_CREATE_RACE", "Could not recover duplicate failure injection plan")
            ensureSameRequest(existing, requestHash)
            existing.id
        }
        return find(id)
    }

    @Transactional
    fun approve(planId: UUID, actor: String, correlationId: String): FailureInjectionPlanDetails {
        val plan = find(planId).plan
        requirePlanningTarget(plan.targetSystemId)
        if (plan.status == FailureInjectionPlanStatus.PENDING_APPROVAL) {
            val profileVersionId = requireNotNull(plan.profileVersionId) {
                "Failure injection plan was created before Profile Version binding was available"
            }
            require(activeProfileVersions.requireActiveVersionId(plan.targetSystemId) == profileVersionId) {
                "Target Profile Version is no longer active"
            }
            val now = clock.instant()
            if (repository.approve(plan.id, actor, correlationId, now)) {
                approvalAuditStore.append(
                    TargetApprovalAuditEvent(
                        id = identifierGenerator.next(),
                        targetSystemId = plan.targetSystemId,
                        profileVersionId = profileVersionId,
                        aggregateType = TargetApprovalAggregateType.FAILURE_INJECTION_PLAN,
                        aggregateId = plan.id,
                        actor = actor,
                        correlationId = correlationId,
                        occurredAt = now,
                    ),
                )
            }
        }
        // Approval is deliberately a record only. Phase 8 has no execution API or worker.
        return find(plan.id)
    }

    fun find(planId: UUID): FailureInjectionPlanDetails {
        val plan = repository.findById(planId) ?: throw FailureInjectionPlanNotFoundException(planId)
        requirePlanningTarget(plan.targetSystemId)
        return FailureInjectionPlanDetails(plan, repository.findItems(plan.id))
    }

    private fun requirePlanningTarget(targetSystemId: String) {
        val target = targetSystemService.findById(targetSystemId)
        require(target.enabled) { "Target system '$targetSystemId' is disabled" }
        require(target.environment in setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)) {
            "Failure injection planning is allowed only for LOCAL or TEST Targets"
        }
        targetTestCatalog.requireFailureInjectionPlanningEnabled(targetSystemId)
    }
    private fun ensureSameRequest(existing: FailureInjectionPlanRecord, requestHash: String) {
        if (existing.requestHash != requestHash) throw FailureInjectionPlanRequestException("FAILURE_INJECTION_PLAN_IDEMPOTENCY_CONFLICT", "Idempotency-Key is already associated with a different plan")
    }
    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private companion object { val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,200}"); const val MAX_PLAN_ITEMS = 5 }
}

class FailureInjectionPlanNotFoundException(id: UUID) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Failure injection plan", id)
class FailureInjectionPlanRequestException(
    override val code: String,
    message: String,
) : com.project.agenticreliabilitylab.common.ClientRequestException(code, message)
