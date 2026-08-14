package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AgentStepRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AgentStepRunStatus
import com.project.agenticreliabilitylab.analysis.domain.MultiAgentRole
import java.time.Instant

data class AgentStepRunResponse(
    val id: String,
    val sequenceNumber: Int,
    val role: MultiAgentRole,
    val status: AgentStepRunStatus,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val toolPolicy: String,
    val inputChecksum: String?,
    val inputContextJson: String?,
    val outputJson: String?,
    val outputChecksum: String?,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
) {
    companion object {
        fun from(step: AgentStepRunRecord) = AgentStepRunResponse(
            id = step.id.toString(), sequenceNumber = step.sequenceNumber, role = step.role, status = step.status,
            modelKey = step.modelKey, modelId = step.modelId, promptVersion = step.promptVersion,
            toolPolicy = step.toolPolicy, inputChecksum = step.inputChecksum, inputContextJson = step.inputContextJson,
            outputJson = step.outputJson,
            outputChecksum = step.outputChecksum,
            promptTokenCount = step.promptTokenCount,
            completionTokenCount = step.completionTokenCount, durationMillis = step.durationMillis,
            failureCode = step.failureCode, failureMessage = step.failureMessage, requestedAt = step.requestedAt,
            startedAt = step.startedAt, completedAt = step.completedAt,
        )
    }
}
