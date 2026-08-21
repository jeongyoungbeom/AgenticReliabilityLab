package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.ReliabilityAgentSettings
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecProposalModel
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecProposalModelRequest
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecProposalModelResponse
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecProposalModelUnavailableException
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** Mirrors [com.project.agenticreliabilitylab.analysis.infrastructure.OllamaReliabilityAnalysisModel]'s call shape. */
@Component
class OllamaTestSpecProposalModel(
    private val chatModelProvider: ObjectProvider<ChatModel>,
    private val properties: ReliabilityAgentSettings,
) : TestSpecProposalModel {
    @Suppress("ThrowsCount") // Each failure mode names itself rather than sharing one vague message.
    override fun propose(request: TestSpecProposalModelRequest): TestSpecProposalModelResponse {
        if (!properties.enabled) {
            throw TestSpecProposalModelUnavailableException("Reliability Agent is disabled by arl.agent.enabled")
        }
        val chatModel = chatModelProvider.ifAvailable
            ?: throw TestSpecProposalModelUnavailableException(
                "No Spring AI ChatModel is configured. Start Ollama or configure spring.ai.model.chat=ollama",
            )
        val startedAt = System.nanoTime()
        val response = try {
            ChatClient.builder(chatModel)
                .build()
                .prompt()
                .options(OllamaChatOptions.builder().model(request.modelId).format("json"))
                .system(request.systemInstruction)
                .user(request.inputBundleJson)
                .call()
                .chatResponse()
                ?: throw TestSpecProposalModelUnavailableException("Ollama returned no proposal response")
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            throw TestSpecProposalModelUnavailableException(
                "Ollama model '${request.modelId}' could not complete the proposal: ${exception.javaClass.simpleName}",
                exception,
            )
        }
        val usage = response.metadata.usage
        return TestSpecProposalModelResponse(
            content = response.result?.output?.text
                ?: throw TestSpecProposalModelUnavailableException("Ollama returned an empty proposal response"),
            promptTokenCount = usage.promptTokens,
            completionTokenCount = usage.completionTokens,
            durationMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI,
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000
    }
}
