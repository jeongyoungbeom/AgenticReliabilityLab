package com.project.agenticreliabilitylab.analysis.application.model

import com.project.agenticreliabilitylab.analysis.domain.AgentStepRunRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunDetails
import com.project.agenticreliabilitylab.analysis.domain.LlmInvocationRecord
import com.project.agenticreliabilitylab.analysis.domain.MultiAgentRole

data class MultiAgentModelSelection(
    val modelKey: String? = null,
    val roleModelKeys: Map<MultiAgentRole, String>? = null,
)

data class MultiAgentAnalysisDetails(
    val analysis: AnalysisRunDetails,
    val configurationJson: String,
    val agentSteps: List<AgentStepRunRecord>,
    val invocations: List<LlmInvocationRecord>,
)
