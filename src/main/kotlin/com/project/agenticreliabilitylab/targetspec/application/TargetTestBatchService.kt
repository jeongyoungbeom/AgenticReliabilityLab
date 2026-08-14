package com.project.agenticreliabilitylab.targetspec.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.execution.application.OutboxJobPublisher
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import com.project.agenticreliabilitylab.targetprofile.application.port.TargetApprovalAuditStore
import com.project.agenticreliabilitylab.targetprofile.domain.TargetApprovalAggregateType
import com.project.agenticreliabilitylab.targetprofile.domain.TargetApprovalAuditEvent
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import com.project.agenticreliabilitylab.targetspec.application.port.NewTargetTestBatch
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestBatchStore
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

@Service
class TargetTestBatchService(
    private val targetPolicy: TargetTestBatchTargetPolicy,
    private val repository: TargetTestBatchStore,
    private val outboxJobPublisher: OutboxJobPublisher,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
    private val executionService: TargetTestBatchExecutionService,
    private val approvalAuditStore: TargetApprovalAuditStore,
) {

    fun candidates(targetSystemId: String): List<TargetTestCandidate> = targetPolicy.candidates(targetSystemId)

    @Transactional
    @Suppress("ReturnCount") // Idempotency recovery has explicit early exits by design.
    fun create(command: CreateTargetTestBatch, idempotencyKey: String): TargetTestBatchRecord {
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            "Idempotency-Key must contain 1 to 200 letters, numbers, '.', '_', ':' or '-'"
        }
        val target = targetPolicy.requireExecutableTarget(command.targetSystemId)
        targetPolicy.requireExecutionEnabled(target.id)
        val maxBatchSize = targetPolicy.maxBatchSize(target.id)
        require(command.candidateIds.isNotEmpty() && command.candidateIds.size <= maxBatchSize) {
            "candidateIds must contain between 1 and $maxBatchSize items"
        }
        require(command.candidateIds.distinct().size == command.candidateIds.size) {
            "candidateIds must not contain duplicates"
        }

        val candidatesById = candidates(target.id).associateBy { it.id }
        val selectedCandidates = command.candidateIds.map { candidateId ->
            candidatesById[candidateId] ?: throw TargetTestBatchRequestException(
                "UNKNOWN_CANDIDATE",
                "Candidate '$candidateId' is not available for Target '${target.id}'",
            )
        }
        val requestHash = requestHash(target.id, selectedCandidates)
        repository.findByTargetAndIdempotencyKey(target.id, idempotencyKey)?.let { existing ->
            ensureSameRequest(existing, requestHash)
            return existing
        }

        val now = clock.instant()
        val batch = NewTargetTestBatch(
            id = identifierGenerator.next(),
            targetSystemId = target.id,
            profileVersionId = targetPolicy.requireActiveProfileVersion(target.id),
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            candidates = selectedCandidates,
            queuedAt = now,
        )
        try {
            repository.create(batch)
        } catch (exception: DuplicateKeyException) {
            val existing = repository.findByTargetAndIdempotencyKey(target.id, idempotencyKey)
                ?: throw exception
            ensureSameRequest(existing, requestHash)
            return existing
        }
        return find(batch.id)
    }

    @Transactional
    fun approve(batchId: UUID, actor: String, correlationId: String): TargetTestBatchRecord {
        val batch = find(batchId)
        if (batch.status == TargetTestBatchStatus.PENDING_APPROVAL) {
            val profileVersionId = requireNotNull(batch.profileVersionId)
            try {
                targetPolicy.requireBatchProfileIsActive(batch.targetSystemId, profileVersionId)
            } catch (exception: IllegalArgumentException) {
                repository.cancelPendingApproval(
                    batch.id,
                    clock.instant(),
                    exception.message ?: PROFILE_VERSION_INACTIVE,
                )
                return find(batch.id)
            }
            val now = clock.instant()
            if (repository.approve(batch.id, actor, correlationId, now)) {
                approvalAuditStore.append(
                    TargetApprovalAuditEvent(
                        id = identifierGenerator.next(),
                        targetSystemId = batch.targetSystemId,
                        profileVersionId = profileVersionId,
                        aggregateType = TargetApprovalAggregateType.TARGET_TEST_BATCH,
                        aggregateId = batch.id,
                        actor = actor,
                        correlationId = correlationId,
                        occurredAt = now,
                    ),
                )
                scheduleExecution(batch.id)
            }
        }
        return find(batch.id)
    }

    fun find(batchId: UUID): TargetTestBatchRecord =
        repository.findById(batchId) ?: throw TargetTestBatchNotFoundException(batchId)

    fun items(batchId: UUID): List<TargetTestBatchItemRecord> {
        find(batchId)
        return repository.findItems(batchId)
    }

    fun recoverIncompleteBatches() {
        repository.findRunningBatchIds().forEach { batchId ->
            repository.markRecoveryRequired(
                batchId,
                clock.instant(),
                "ARL restarted while a generic Target HTTP request could have been in progress",
            )
        }
        repository.findApprovedBatchIds().forEach(::scheduleExecution)
    }

    private fun scheduleExecution(batchId: UUID) =
        outboxJobPublisher.enqueue(OutboxJobType.TARGET_TEST_BATCH_EXECUTION, batchId)

    fun executeOutboxJob(batchId: UUID) = executionService.execute(batchId)

    private fun ensureSameRequest(existing: TargetTestBatchRecord, requestHash: String) {
        if (existing.requestHash != requestHash) {
            throw TargetTestBatchRequestException(
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key is already associated with a different Target test batch",
            )
        }
    }

    private fun requestHash(targetSystemId: String, candidates: List<TargetTestCandidate>): String =
        (targetSystemId + "|" + candidates.joinToString("|") { candidate ->
            listOf(
                candidate.id,
                candidate.kind.name,
                candidate.method,
                candidate.path,
                candidate.expectedStatusCodes.sorted().joinToString(","),
                candidate.timeout.toMillis().toString(),
            ).joinToString("~")
        }).sha256()

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PROFILE_VERSION_INACTIVE = "PROFILE_VERSION_INACTIVE"
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,200}")
    }
}
