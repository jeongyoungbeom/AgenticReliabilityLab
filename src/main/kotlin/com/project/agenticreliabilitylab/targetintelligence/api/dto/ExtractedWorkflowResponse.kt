package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedWorkflow

data class ExtractedWorkflowResponse(
    val title: String,
    val steps: List<String>,
    val confidence: String,
    val citation: KnowledgeCitationResponse,
) {
    companion object {
        fun from(workflow: ExtractedWorkflow): ExtractedWorkflowResponse = ExtractedWorkflowResponse(
            title = workflow.title,
            steps = workflow.steps,
            confidence = workflow.confidence.name,
            citation = KnowledgeCitationResponse.from(workflow.citation),
        )
    }
}
