package com.project.agenticreliabilitylab.targetspec.application

import com.project.agenticreliabilitylab.experiment.application.port.WorkloadLeasePort
import com.project.agenticreliabilitylab.experiment.domain.WorkloadLease
import com.project.agenticreliabilitylab.target.application.TargetSystemNotFoundException
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetReadResponse
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.target.domain.TargetReadTransportException
import com.project.agenticreliabilitylab.targetspec.application.model.TargetTestBatchItemExecution
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestBatchStore
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestExecutionSettings
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemStatus
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchStatus
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Runs only already-approved read-only HTTP batch items; creation and approval live elsewhere. */
@Service
@Suppress("TooGenericExceptionCaught") // A durable batch must record unexpected failures after a Target call.
class TargetTestBatchExecutionService(
    private val targetPolicy: TargetTestBatchTargetPolicy,
    private val repository: TargetTestBatchStore,
    private val workloadLeaseRepository: WorkloadLeasePort,
    private val targetHttpTransport: TargetReadTransport,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun execute(batchId: UUID) {
        if (!repository.claimForExecution(batchId, clock.instant())) return

        val execution = ExecutionState()
        try {
            val batch = repository.findById(batchId) ?: throw TargetTestBatchNotFoundException(batchId)
            val settings = targetPolicy.requireBatchProfileIsActive(batch.targetSystemId, batch.profileVersionId)
            val target = targetPolicy.requireExecutableTarget(batch.targetSystemId)
            val batchItems = repository.findItems(batchId)
            execution.lease = acquireLease(batch.id, settings, batchItems.size)
            if (execution.lease == null) {
                repository.completeBatch(
                    batchId,
                    TargetTestBatchStatus.FAILED,
                    "WORKLOAD_LEASE_UNAVAILABLE: Another workload currently owns '${settings.hostResourceGroup}'",
                    clock.instant(),
                )
                return
            }
            executeItems(target, batchItems, execution)
            completeBatch(batchId)
        } catch (exception: TargetReadTransportException) {
            execution.preserveLease = true
            markRecoveryRequired(
                batchId,
                "A Target HTTP request may have reached the Target but its outcome was not confirmed: " +
                    exception.message,
            )
        } catch (exception: TargetSystemNotFoundException) {
            repository.completeBatch(batchId, TargetTestBatchStatus.FAILED, exception.message, clock.instant())
        } catch (exception: RuntimeException) {
            handleExecutionFailure(batchId, execution, exception)
        } finally {
            execution.lease?.takeUnless { execution.preserveLease }?.let(workloadLeaseRepository::release)
        }
    }

    private fun acquireLease(
        batchId: UUID,
        settings: TargetTestExecutionSettings,
        itemCount: Int,
    ): WorkloadLease? {
        val now = clock.instant()
        return workloadLeaseRepository.tryAcquire(
            hostResourceGroup = settings.hostResourceGroup,
            ownerId = batchId.toString(),
            leaseOwner = "target-test-batch:$batchId",
            now = now,
            expiresAt = now.plus(settings.requestTimeout.multipliedBy(itemCount.toLong())).plus(LEASE_GRACE),
            mode = "TARGET_HTTP_BATCH",
            ownerType = "TARGET_TEST_BATCH",
        )
    }

    private fun executeItems(
        target: RegisteredTarget,
        batchItems: List<TargetTestBatchItemRecord>,
        execution: ExecutionState,
    ) {
        batchItems.forEach { item ->
            if (!repository.claimItem(item.id, clock.instant())) return@forEach
            execution.externalRequestStarted = true
            val itemExecution = executeItem(target, item)
            repository.completeItem(
                itemId = item.id,
                status = itemExecution.status,
                httpStatus = itemExecution.httpStatus,
                latencyMs = itemExecution.latencyMs,
                resultJson = itemExecution.resultJson,
                failureMessage = itemExecution.failureMessage,
                now = clock.instant(),
            )
        }
    }

    private fun completeBatch(batchId: UUID) {
        val hasFailures = repository.findItems(batchId).any { it.status == TargetTestBatchItemStatus.FAILED }
        repository.completeBatch(
            batchId,
            if (hasFailures) TargetTestBatchStatus.FAILED else TargetTestBatchStatus.COMPLETED,
            if (hasFailures) "One or more HTTP status assertions failed" else null,
            clock.instant(),
        )
    }

    private fun handleExecutionFailure(batchId: UUID, execution: ExecutionState, exception: RuntimeException) {
        if (execution.externalRequestStarted) {
            execution.preserveLease = true
            markRecoveryRequired(
                batchId,
                "ARL could not durably finish a generic Target HTTP request: ${exception.javaClass.simpleName}",
            )
            return
        }
        repository.completeBatch(
            batchId,
            if (exception.message?.startsWith(PROFILE_VERSION_INACTIVE) == true) {
                TargetTestBatchStatus.CANCELLED
            } else {
                TargetTestBatchStatus.FAILED
            },
            exception.message ?: "Generic Target test execution failed",
            clock.instant(),
        )
    }

    private fun markRecoveryRequired(batchId: UUID, message: String) {
        repository.markRecoveryRequired(batchId, clock.instant(), message)
    }

    private fun executeItem(target: RegisteredTarget, item: TargetTestBatchItemRecord): TargetTestBatchItemExecution {
        val uri = target.allowedOrigin.resolve(item.path)
        require(uri.normalizedOrigin() == target.allowedOrigin.normalizedOrigin()) {
            "Candidate '${item.candidateId}' resolves outside Target '${target.id}' allowed origin"
        }
        val startedAt = System.nanoTime()
        val response = targetHttpTransport.send(
            target,
            uri,
            item.method,
            emptyMap(),
            ByteArray(0),
            item.timeout,
        )
        val latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
        val passed = response.statusCode in item.expectedStatusCodes
        return TargetTestBatchItemExecution(
            status = if (passed) TargetTestBatchItemStatus.PASSED else TargetTestBatchItemStatus.FAILED,
            httpStatus = response.statusCode,
            latencyMs = latencyMs,
            resultJson = objectMapper.writeValueAsString(response.toEvidence(target.id, item, latencyMs)),
            failureMessage = if (passed) null else {
                "Expected HTTP ${item.expectedStatusCodes.sorted().joinToString()} but received ${response.statusCode}"
            },
        )
    }

    private fun TargetReadResponse.toEvidence(
        targetSystemId: String,
        item: TargetTestBatchItemRecord,
        latencyMs: Long,
    ): Map<String, Any> = linkedMapOf(
        "targetSystemId" to targetSystemId,
        "candidateId" to item.candidateId,
        "candidateKind" to item.kind.name,
        "method" to item.method,
        "path" to item.path,
        "expectedStatusCodes" to item.expectedStatusCodes.sorted(),
        "actualStatusCode" to statusCode,
        "latencyMs" to latencyMs,
        "responseByteCount" to body.size,
        "responseSha256" to body.sha256(),
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun URI.normalizedOrigin(): String {
        val effectivePort = when {
            port >= 0 -> port
            scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        return "${scheme.lowercase()}://${host.lowercase()}:$effectivePort"
    }

    private companion object {
        const val PROFILE_VERSION_INACTIVE = "PROFILE_VERSION_INACTIVE"
        val LEASE_GRACE: Duration = Duration.ofMinutes(2)
    }

    private class ExecutionState {
        var externalRequestStarted: Boolean = false
        var lease: WorkloadLease? = null
        var preserveLease: Boolean = false
    }
}
