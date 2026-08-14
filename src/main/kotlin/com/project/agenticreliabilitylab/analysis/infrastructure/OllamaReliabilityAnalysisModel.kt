package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.AnalysisModelUnavailableException
import com.project.agenticreliabilitylab.analysis.application.ReliabilityAnalysisModel
import com.project.agenticreliabilitylab.analysis.application.ReliabilityAnalysisModelRequest
import com.project.agenticreliabilitylab.analysis.application.ReliabilityAnalysisModelResponse
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@Component
class OllamaReliabilityAnalysisModel(
    private val chatModelProvider: ObjectProvider<ChatModel>,
    private val properties: ReliabilityAgentProperties,
) : ReliabilityAnalysisModel {
    override fun analyze(request: ReliabilityAnalysisModelRequest): ReliabilityAnalysisModelResponse {
        if (!properties.enabled) {
            throw AnalysisModelUnavailableException("Reliability Agent is disabled by arl.agent.enabled")
        }
        val chatModel = chatModelProvider.ifAvailable
            ?: throw AnalysisModelUnavailableException(
                "No Spring AI ChatModel is configured. Start Ollama or configure spring.ai.model.chat=ollama",
            )
        val startedAt = System.nanoTime()
        val response = try {
            ChatClient.builder(chatModel)
                .build()
                .prompt()
                .options(OllamaChatOptions.builder().model(request.modelId).format("json"))
                .system(request.systemInstruction)
                .user(request.evidenceBundleJson)
                .call()
                .chatResponse()
                ?: throw AnalysisModelUnavailableException("Ollama returned no analysis response")
        } catch (exception: Exception) {
            throw AnalysisModelUnavailableException(
                "Ollama model '${request.modelId}' could not complete the analysis: ${exception.javaClass.simpleName}",
            )
        }
        val usage = response.metadata.usage
        return ReliabilityAnalysisModelResponse(
            content = response.result?.output?.text
                ?: throw AnalysisModelUnavailableException("Ollama returned an empty analysis response"),
            promptTokenCount = usage.promptTokens,
            completionTokenCount = usage.completionTokens,
            durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }
}
