package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.AgentStepCompletion
import com.project.agenticreliabilitylab.analysis.application.port.LlmInvocationCompletion
import com.project.agenticreliabilitylab.analysis.application.port.MultiAgentAnalysisStore
import com.project.agenticreliabilitylab.analysis.application.port.MultiAgentConfigurationRecord
import com.project.agenticreliabilitylab.analysis.application.port.NewAgentStepRun
import com.project.agenticreliabilitylab.analysis.application.port.NewLlmInvocation
import com.project.agenticreliabilitylab.analysis.application.port.NewMultiAgentAnalysis
import com.project.agenticreliabilitylab.analysis.domain.AgentStepRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AgentStepRunStatus
import com.project.agenticreliabilitylab.analysis.domain.LlmInvocationRecord
import com.project.agenticreliabilitylab.analysis.domain.LlmInvocationStatus
import com.project.agenticreliabilitylab.analysis.domain.MultiAgentRole
import com.project.agenticreliabilitylab.analysis.infrastructure.sql.MultiAgentAnalysisSql
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
@Suppress("TooManyFunctions") // One durable workflow owns steps, invocations, and recovery queries.
class JdbcMultiAgentAnalysisRepository(
    private val jdbcClient: JdbcClient,
) : MultiAgentAnalysisStore {
    @Transactional
    override fun create(configuration: NewMultiAgentAnalysis, steps: List<NewAgentStepRun>) {
        jdbcClient.sql(MultiAgentAnalysisSql.INSERT_CONFIGURATION).params(
            mapOf(
                "analysisRunId" to configuration.analysisRunId,
                "configurationJson" to configuration.configurationJson,
                "configurationHash" to configuration.configurationHash,
                "createdAt" to Timestamp.from(configuration.createdAt),
            ),
        ).update()
        steps.forEach(::createStep)
    }

    override fun findConfiguration(analysisRunId: UUID): MultiAgentConfigurationRecord? =
        jdbcClient.sql(MultiAgentAnalysisSql.FIND_CONFIGURATION).param("analysisRunId", analysisRunId)
            .query { resultSet, _ -> resultSet.toConfiguration() }
            .optional()
            .orElse(null)

    override fun findSteps(analysisRunId: UUID): List<AgentStepRunRecord> =
        jdbcClient.sql(MultiAgentAnalysisSql.FIND_STEPS).param("analysisRunId", analysisRunId)
            .query { resultSet, _ -> resultSet.toStepRun() }
            .list()

    override fun findInvocations(analysisRunId: UUID): List<LlmInvocationRecord> =
        jdbcClient.sql(MultiAgentAnalysisSql.FIND_INVOCATIONS).param("analysisRunId", analysisRunId)
            .query { resultSet, _ -> resultSet.toInvocation() }
            .list()

    override fun claimStep(
        stepId: UUID,
        inputChecksum: String,
        inputContextJson: String,
        now: Instant,
    ): Boolean = jdbcClient.sql(MultiAgentAnalysisSql.CLAIM_STEP).params(
        mapOf(
            "id" to stepId,
            "requested" to AgentStepRunStatus.REQUESTED.name,
            "running" to AgentStepRunStatus.RUNNING.name,
            "inputChecksum" to inputChecksum,
            "inputContextJson" to inputContextJson,
            "startedAt" to Timestamp.from(now),
        ),
    ).update() == 1

    override fun createInvocation(invocation: NewLlmInvocation) {
        jdbcClient.sql(MultiAgentAnalysisSql.INSERT_INVOCATION).params(
            mapOf(
                "id" to invocation.id,
                "agentStepRunId" to invocation.agentStepRunId,
                "ordinal" to invocation.ordinal,
                "status" to LlmInvocationStatus.RUNNING.name,
                "modelKey" to invocation.modelKey,
                "modelId" to invocation.modelId,
                "promptVersion" to invocation.promptVersion,
                "inputChecksum" to invocation.inputChecksum,
                "startedAt" to Timestamp.from(invocation.startedAt),
            ),
        ).update()
    }

    override fun completeInvocation(id: UUID, completion: LlmInvocationCompletion, now: Instant) {
        jdbcClient.sql(MultiAgentAnalysisSql.COMPLETE_INVOCATION).params(
            mapOf(
                "id" to id,
                "running" to LlmInvocationStatus.RUNNING.name,
                "completed" to LlmInvocationStatus.COMPLETED.name,
                "outputChecksum" to completion.outputChecksum,
                "promptTokenCount" to completion.promptTokenCount,
                "completionTokenCount" to completion.completionTokenCount,
                "durationMillis" to completion.durationMillis,
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun failInvocation(id: UUID, failureCode: String, failureMessage: String, now: Instant) {
        jdbcClient.sql(MultiAgentAnalysisSql.FAIL_INVOCATION).params(
            mapOf(
                "id" to id,
                "running" to LlmInvocationStatus.RUNNING.name,
                "failed" to LlmInvocationStatus.FAILED.name,
                "failureCode" to failureCode.take(100),
                "failureMessage" to failureMessage.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun completeStep(id: UUID, completion: AgentStepCompletion, now: Instant) {
        jdbcClient.sql(MultiAgentAnalysisSql.COMPLETE_STEP).params(
            mapOf(
                "id" to id,
                "running" to AgentStepRunStatus.RUNNING.name,
                "completed" to AgentStepRunStatus.COMPLETED.name,
                "outputJson" to completion.outputJson,
                "outputChecksum" to completion.outputChecksum,
                "promptTokenCount" to completion.promptTokenCount,
                "completionTokenCount" to completion.completionTokenCount,
                "durationMillis" to completion.durationMillis,
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun failStep(id: UUID, failureCode: String, failureMessage: String, now: Instant) {
        jdbcClient.sql(MultiAgentAnalysisSql.FAIL_STEP).params(
            mapOf(
                "id" to id,
                "requested" to AgentStepRunStatus.REQUESTED.name,
                "running" to AgentStepRunStatus.RUNNING.name,
                "failed" to AgentStepRunStatus.FAILED.name,
                "failureCode" to failureCode.take(100),
                "failureMessage" to failureMessage.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun failIncompleteSteps(analysisRunId: UUID, failureCode: String, failureMessage: String, now: Instant) {
        jdbcClient.sql(MultiAgentAnalysisSql.FAIL_INCOMPLETE_STEPS).params(
            mapOf(
                "analysisRunId" to analysisRunId,
                "requested" to AgentStepRunStatus.REQUESTED.name,
                "running" to AgentStepRunStatus.RUNNING.name,
                "failed" to AgentStepRunStatus.FAILED.name,
                "failureCode" to failureCode.take(100),
                "failureMessage" to failureMessage.take(1000),
                "completedAt" to Timestamp.from(now),
            ),
        ).update()
    }

    override fun findRequestedAnalysisRunIds(): List<UUID> = findAnalysisRunIdsByStatus("REQUESTED")

    override fun findRunningAnalysisRunIds(): List<UUID> = findAnalysisRunIdsByStatus("RUNNING")

    private fun createStep(step: NewAgentStepRun) {
        jdbcClient.sql(MultiAgentAnalysisSql.INSERT_STEP).params(
            mapOf(
                "id" to step.id,
                "analysisRunId" to step.analysisRunId,
                "sequenceNumber" to step.sequenceNumber,
                "role" to step.role.name,
                "status" to AgentStepRunStatus.REQUESTED.name,
                "modelKey" to step.modelKey,
                "modelId" to step.modelId,
                "promptVersion" to step.promptVersion,
                "requestedAt" to Timestamp.from(step.requestedAt),
            ),
        ).update()
    }

    private fun findAnalysisRunIdsByStatus(status: String): List<UUID> = jdbcClient.sql(
        MultiAgentAnalysisSql.FIND_ANALYSIS_RUN_IDS_BY_STATUS,
    ).param("status", status).query { resultSet, _ -> resultSet.getObject("id", UUID::class.java) }.list()

    private fun ResultSet.toConfiguration(): MultiAgentConfigurationRecord = MultiAgentConfigurationRecord(
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        configurationJson = getString("configuration_json"),
        configurationHash = getString("configuration_hash"),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private fun ResultSet.toStepRun(): AgentStepRunRecord = AgentStepRunRecord(
        id = getObject("id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        sequenceNumber = getInt("sequence_number"),
        role = MultiAgentRole.valueOf(getString("agent_role")),
        status = AgentStepRunStatus.valueOf(getString("status")),
        modelKey = getString("model_key"),
        modelId = getString("model_id"),
        promptVersion = getString("prompt_version"),
        toolPolicy = getString("tool_policy"),
        inputChecksum = getString("input_checksum"),
        inputContextJson = getString("input_context_json"),
        outputJson = getString("output_json"),
        outputChecksum = getString("output_checksum"),
        promptTokenCount = getObject("prompt_token_count") as Int?,
        completionTokenCount = getObject("completion_token_count") as Int?,
        durationMillis = getObject("duration_millis") as Long?,
        failureCode = getString("failure_code"),
        failureMessage = getString("failure_message"),
        requestedAt = getTimestamp("requested_at").toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )

    private fun ResultSet.toInvocation(): LlmInvocationRecord = LlmInvocationRecord(
        id = getObject("id", UUID::class.java),
        agentStepRunId = getObject("agent_step_run_id", UUID::class.java),
        ordinal = getInt("invocation_ordinal"),
        status = LlmInvocationStatus.valueOf(getString("status")),
        modelKey = getString("model_key"),
        modelId = getString("model_id"),
        promptVersion = getString("prompt_version"),
        toolCallCount = getInt("tool_call_count"),
        inputChecksum = getString("input_checksum"),
        outputChecksum = getString("output_checksum"),
        promptTokenCount = getObject("prompt_token_count") as Int?,
        completionTokenCount = getObject("completion_token_count") as Int?,
        durationMillis = getObject("duration_millis") as Long?,
        failureCode = getString("failure_code"),
        failureMessage = getString("failure_message"),
        startedAt = getTimestamp("started_at").toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
    )
}
