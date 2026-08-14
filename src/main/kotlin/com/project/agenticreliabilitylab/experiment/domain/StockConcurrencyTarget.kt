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

class ExternalOperationOutcomeUnknownException(message: String) : RuntimeException(message)
