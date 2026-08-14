package com.project.agenticreliabilitylab.analysis.application.port

import com.project.agenticreliabilitylab.analysis.domain.AgentStepRunRecord
import com.project.agenticreliabilitylab.analysis.domain.LlmInvocationRecord
import com.project.agenticreliabilitylab.analysis.domain.MultiAgentRole
import java.time.Instant
import java.util.UUID

/** Persistence boundary for the durable multi-agent step and invocation workflow. */
@Suppress("TooManyFunctions") // One durable workflow owns steps, invocations, and recovery queries.
interface MultiAgentAnalysisStore {
    fun create(configuration: NewMultiAgentAnalysis, steps: List<NewAgentStepRun>)
    fun findConfiguration(analysisRunId: UUID): MultiAgentConfigurationRecord?
    fun findSteps(analysisRunId: UUID): List<AgentStepRunRecord>
    fun findInvocations(analysisRunId: UUID): List<LlmInvocationRecord>
    fun claimStep(stepId: UUID, inputChecksum: String, inputContextJson: String, now: Instant): Boolean
    fun createInvocation(invocation: NewLlmInvocation)
    fun completeInvocation(id: UUID, completion: LlmInvocationCompletion, now: Instant)
    fun failInvocation(id: UUID, failureCode: String, failureMessage: String, now: Instant)
    fun completeStep(id: UUID, completion: AgentStepCompletion, now: Instant)
    fun failStep(id: UUID, failureCode: String, failureMessage: String, now: Instant)
    fun failIncompleteSteps(analysisRunId: UUID, failureCode: String, failureMessage: String, now: Instant)
    fun findRequestedAnalysisRunIds(): List<UUID>
    fun findRunningAnalysisRunIds(): List<UUID>
}

data class NewMultiAgentAnalysis(
    val analysisRunId: UUID,
    val configurationJson: String,
    val configurationHash: String,
    val createdAt: Instant,
)

data class MultiAgentConfigurationRecord(
    val analysisRunId: UUID,
    val configurationJson: String,
    val configurationHash: String,
    val createdAt: Instant,
)

data class NewAgentStepRun(
    val id: UUID,
    val analysisRunId: UUID,
    val sequenceNumber: Int,
    val role: MultiAgentRole,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val requestedAt: Instant,
)

data class NewLlmInvocation(
    val id: UUID,
    val agentStepRunId: UUID,
    val ordinal: Int,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputChecksum: String,
    val startedAt: Instant,
)

data class LlmInvocationCompletion(
    val outputChecksum: String,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
)

data class AgentStepCompletion(
    val outputJson: String,
    val outputChecksum: String,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
)
