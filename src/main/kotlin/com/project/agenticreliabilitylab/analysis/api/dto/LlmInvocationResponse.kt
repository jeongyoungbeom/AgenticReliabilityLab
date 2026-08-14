package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.LlmInvocationRecord
import com.project.agenticreliabilitylab.analysis.domain.LlmInvocationStatus
import java.time.Instant

data class LlmInvocationResponse(
    val id: String,
    val agentStepRunId: String,
    val ordinal: Int,
    val status: LlmInvocationStatus,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val toolCallCount: Int,
    val inputChecksum: String,
    val outputChecksum: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
) {
    companion object {
        fun from(invocation: LlmInvocationRecord) = LlmInvocationResponse(
            id = invocation.id.toString(),
            agentStepRunId = invocation.agentStepRunId.toString(),
            ordinal = invocation.ordinal,
            status = invocation.status,
            modelKey = invocation.modelKey,
            modelId = invocation.modelId,
            promptVersion = invocation.promptVersion,
            toolCallCount = invocation.toolCallCount,
            inputChecksum = invocation.inputChecksum,
            outputChecksum = invocation.outputChecksum,
            promptTokenCount = invocation.promptTokenCount,
            completionTokenCount = invocation.completionTokenCount,
            durationMillis = invocation.durationMillis,
            failureCode = invocation.failureCode,
            failureMessage = invocation.failureMessage,
            startedAt = invocation.startedAt,
            completedAt = invocation.completedAt,
        )
    }
}
