package com.project.agenticreliabilitylab.experiment.domain

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import java.util.UUID

/**
 * A Target Package extension point. ARL Core owns run state and invariants;
 * the adapter owns only the registered target-specific execution contract.
 */
interface ExperimentTargetAdapter {
    val adapterId: String

    fun executeStockConcurrency(
        request: StockConcurrencyTargetExecutionRequest,
    ): StockConcurrencyExecutionResult
}

data class StockConcurrencyTargetExecutionRequest(
    val target: RegisteredTarget,
    val profile: TargetExperimentProfile,
    val runId: UUID,
    val actionId: String,
    val parameters: StockConcurrencyParameters,
)

data class StockConcurrencyExecutionResult(
    val targetOperationId: String,
    val executionStatus: String,
    val message: String,
    val productId: String?,
    val successCount: Int,
    val failureCount: Int,
    val oversellCount: Int,
    val finalRedisStock: Int?,
    val finalDbStock: Int?,
    val durationSeconds: Long,
    val cleanupVerified: Boolean,
    val artifactReference: String,
    val artifactChecksum: String,
    val resources: List<TargetResource> = emptyList(),
)

data class TargetResource(
    val type: String,
    val id: String,
    val namespace: String,
)

class ExternalOperationOutcomeUnknownException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * The Target was not ready to accept this run, and nothing was dispatched to it.
 *
 * The engine records an action as dispatched before it calls an adapter, so any other failure has to be treated as a
 * possible side effect that needs manual recovery. An adapter raises this instead when it can still guarantee the
 * Target was only read: the run then fails validation cleanly, the workload lease is released and later runs are not
 * blocked.
 */
class TargetPreflightFailedException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
