package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.application.model.MultiAgentAnalysisDetails

data class MultiAgentAnalysisDetailsResponse(
    val analysis: AnalysisRunDetailsResponse,
    val configurationJson: String,
    val agentSteps: List<AgentStepRunResponse>,
    val llmInvocations: List<LlmInvocationResponse>,
) {
    companion object {
        fun from(details: MultiAgentAnalysisDetails) = MultiAgentAnalysisDetailsResponse(
            analysis = AnalysisRunDetailsResponse.from(details.analysis),
            configurationJson = details.configurationJson,
            agentSteps = details.agentSteps.map(AgentStepRunResponse::from),
            llmInvocations = details.invocations.map(LlmInvocationResponse::from),
        )
    }
}
