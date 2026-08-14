package com.project.agenticreliabilitylab.analysis.application

import com.project.agenticreliabilitylab.analysis.application.port.AgentStepCompletion
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisCompletion
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisRunStore
import com.project.agenticreliabilitylab.analysis.application.port.LlmInvocationCompletion
import com.project.agenticreliabilitylab.analysis.application.port.MultiAgentAnalysisStore
import com.project.agenticreliabilitylab.analysis.application.port.MultiAgentSettings
import com.project.agenticreliabilitylab.analysis.application.port.NewLlmInvocation
import com.project.agenticreliabilitylab.analysis.application.port.ReliabilityAgentSettings
import com.project.agenticreliabilitylab.analysis.domain.AgentStepRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord
import com.project.agenticreliabilitylab.analysis.domain.MultiAgentRole
import com.project.agenticreliabilitylab.common.IdentifierGenerator
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** Executes a persisted multi-agent workflow; request creation and idempotency stay in the agent facade. */
@Service
@Suppress("TooGenericExceptionCaught") // A workflow must persist any model/validation failure as a durable outcome.
class MultiAgentAnalysisExecutor(
    private val datasetService: AnalysisDatasetService,
    private val analysisRepository: AnalysisRunStore,
    private val multiAgentRepository: MultiAgentAnalysisStore,
    private val analysisModel: ReliabilityAnalysisModel,
    private val outputValidator: AnalysisOutputValidator,
    private val stepContract: MultiAgentStepContract,
    private val objectMapper: ObjectMapper,
    private val agentProperties: ReliabilityAgentSettings,
    private val properties: MultiAgentSettings,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
) {
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun execute(analysisRunId: UUID) {
        if (!properties.enabled || !agentProperties.enabled) return
        if (!analysisRepository.claimForExecution(analysisRunId, clock.instant())) return
        var activeStepId: UUID? = null
        try {
            val analysisRun = analysisRepository.findById(analysisRunId)
                ?: throw IllegalStateException("Claimed analysis run '$analysisRunId' no longer exists")
            val datasetId = analysisRun.analysisDatasetId
                ?: throw AnalysisInputException("Analysis run '$analysisRunId' has no immutable input dataset")
            val dataset = datasetService.find(datasetId)
            val steps = multiAgentRepository.findSteps(analysisRunId)
            require(steps.map { it.role } == MultiAgentRole.entries.toList()) {
                "Multi-agent analysis '$analysisRunId' does not have the required ordered roles"
            }

            val previousOutputs = linkedMapOf<MultiAgentRole, String>()
            var finalCompletion: AnalysisCompletion? = null
            val stepMetrics = mutableListOf<StepMetrics>()
            for (step in steps) {
                activeStepId = step.id
                val stepResult = executeStep(dataset, step, previousOutputs)
                previousOutputs[step.role] = stepResult.outputJson
                stepMetrics += stepResult.metrics
                if (step.role == MultiAgentRole.REVIEWER) finalCompletion = stepResult.finalCompletion
            }
            val aggregateMetrics = StepMetrics.aggregate(stepMetrics)
            analysisRepository.complete(
                analysisRunId,
                (finalCompletion ?: throw AnalysisOutputException(
                    "Multi-agent reviewer did not produce a final analysis",
                )).copy(
                    promptTokenCount = aggregateMetrics.promptTokenCount,
                    completionTokenCount = aggregateMetrics.completionTokenCount,
                    durationMillis = aggregateMetrics.durationMillis,
                ),
                clock.instant(),
            )
        } catch (exception: RuntimeException) {
            val failure = failureFrom(exception)
            activeStepId?.let { multiAgentRepository.failStep(it, failure.code, failure.message, clock.instant()) }
            multiAgentRepository.failIncompleteSteps(analysisRunId, failure.code, failure.message, clock.instant())
            analysisRepository.fail(analysisRunId, failure.code, failure.message, clock.instant())
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeStep(
        dataset: AnalysisDatasetRecord,
        step: AgentStepRunRecord,
        previousOutputs: Map<MultiAgentRole, String>,
    ): StepResult {
        val inputContext = objectMapper.writeValueAsString(
            linkedMapOf(
                "role" to step.role.name,
                "previousRoleOutputs" to previousOutputs.mapKeys { it.key.name },
                "toolPolicy" to "NO_TOOLS",
            ),
        )
        val inputChecksum = (dataset.checksum + "|" + step.role.name + "|" + inputContext).sha256()
        if (!multiAgentRepository.claimStep(step.id, inputChecksum, inputContext, clock.instant())) {
            throw AnalysisInputException("Agent step '${step.id}' was not available for execution")
        }

        var invocationId: UUID? = null
        var invocationCompleted = false
        try {
            invocationId = identifierGenerator.next()
            val invocation = NewLlmInvocation(
                invocationId,
                step.id,
                FIRST_INVOCATION_ORDINAL,
                step.modelKey,
                step.modelId,
                step.promptVersion,
                inputChecksum,
                clock.instant(),
            )
            multiAgentRepository.createInvocation(invocation)
            val modelResponse = analysisModel.analyze(
                ReliabilityAnalysisModelRequest(
                    step.modelId,
                    stepContract.systemInstruction(step.role, inputContext),
                    dataset.evidenceBundleJson,
                    dataset.evidenceIds,
                ),
            )
            require(modelResponse.content.toByteArray(StandardCharsets.UTF_8).size <= properties.maxStepOutputBytes) {
                "Agent step '${step.role}' response exceeds ${properties.maxStepOutputBytes} bytes"
            }
            completeInvocation(invocationId, modelResponse)
            invocationCompleted = true
            val finalCompletion = parseStepOutput(step, modelResponse, dataset)
            completeStep(step, modelResponse)
            return StepResult(
                modelResponse.content,
                finalCompletion,
                StepMetrics(
                    modelResponse.promptTokenCount,
                    modelResponse.completionTokenCount,
                    modelResponse.durationMillis,
                ),
            )
        } catch (exception: RuntimeException) {
            val failure = failureFrom(exception)
            if (invocationId != null && !invocationCompleted) {
                multiAgentRepository.failInvocation(invocationId, failure.code, failure.message, clock.instant())
            }
            multiAgentRepository.failStep(step.id, failure.code, failure.message, clock.instant())
            throw exception
        }
    }

    private fun completeInvocation(invocationId: UUID, modelResponse: ReliabilityAnalysisModelResponse) {
        val completion = LlmInvocationCompletion(
            modelResponse.content.sha256(),
            modelResponse.promptTokenCount,
            modelResponse.completionTokenCount,
            modelResponse.durationMillis,
        )
        multiAgentRepository.completeInvocation(invocationId, completion, clock.instant())
    }

    private fun parseStepOutput(
        step: AgentStepRunRecord,
        modelResponse: ReliabilityAnalysisModelResponse,
        dataset: AnalysisDatasetRecord,
    ): AnalysisCompletion? = if (step.role == MultiAgentRole.REVIEWER) {
        outputValidator.parseFinal(modelResponse.content, dataset.evidenceIds.toSet())
    } else {
        stepContract.validate(step.role, modelResponse.content, dataset.evidenceIds.toSet())
        null
    }

    private fun completeStep(step: AgentStepRunRecord, modelResponse: ReliabilityAnalysisModelResponse) {
        val completion = AgentStepCompletion(
            modelResponse.content,
            modelResponse.content.sha256(),
            modelResponse.promptTokenCount,
            modelResponse.completionTokenCount,
            modelResponse.durationMillis,
        )
        multiAgentRepository.completeStep(step.id, completion, clock.instant())
    }

    private fun failureFrom(exception: Exception): Failure = when (exception) {
        is AnalysisModelUnavailableException ->
            Failure("MODEL_UNAVAILABLE", exception.message ?: "Analysis model is unavailable")
        is AnalysisInputException -> Failure("EVIDENCE_INPUT_INVALID", exception.message)
        is AnalysisOutputException -> Failure("MODEL_OUTPUT_INVALID", exception.message ?: "Model output is invalid")
        else -> Failure(
            "MULTI_ANALYSIS_EXECUTION_FAILED",
            exception.message ?: exception.javaClass.simpleName,
        )
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private data class StepResult(
        val outputJson: String,
        val finalCompletion: AnalysisCompletion?,
        val metrics: StepMetrics,
    )
    private data class Failure(val code: String, val message: String)
    private data class StepMetrics(
        val promptTokenCount: Int?,
        val completionTokenCount: Int?,
        val durationMillis: Long?,
    ) {
        companion object {
            fun aggregate(metrics: List<StepMetrics>): StepMetrics = StepMetrics(
                promptTokenCount = metrics.completeIntSum { it.promptTokenCount },
                completionTokenCount = metrics.completeIntSum { it.completionTokenCount },
                durationMillis = metrics.completeLongSum { it.durationMillis },
            )
            private fun List<StepMetrics>.completeIntSum(value: (StepMetrics) -> Int?): Int? =
                if (all { value(it) != null }) sumOf { value(it)!! } else null

            private fun List<StepMetrics>.completeLongSum(value: (StepMetrics) -> Long?): Long? =
                if (all { value(it) != null }) sumOf { value(it)!! } else null
        }
    }

    private companion object {
        const val FIRST_INVOCATION_ORDINAL = 1
    }
}
