package com.project.agenticreliabilitylab.testcatalog.api.dto

import com.project.agenticreliabilitylab.targetintelligence.api.dto.KnowledgeCitationResponse
import com.project.agenticreliabilitylab.testcatalog.application.TestCandidateView
import java.util.UUID

/** [readiness] is computed per request from the stored binding and current Target capability. */
data class TestCandidateResponse(
    val id: UUID,
    val sequenceNumber: Int,
    val category: String,
    val title: String,
    val description: String,
    val risk: String,
    val confidence: String,
    val readiness: String,
    val verifiedExpectation: String,
    val preconditions: List<String>,
    val binding: ExecutionBindingResponse,
    val citations: List<KnowledgeCitationResponse>,
    val requiredEvidence: List<String>,
    val dataLifecyclePlan: String?,
) {
    companion object {
        fun from(view: TestCandidateView): TestCandidateResponse {
            val candidate = view.candidate
            return TestCandidateResponse(
                id = candidate.id,
                sequenceNumber = candidate.sequenceNumber,
                category = candidate.category.name,
                title = candidate.title,
                description = candidate.description,
                risk = candidate.risk.name,
                confidence = candidate.confidence.name,
                readiness = view.readiness.name,
                verifiedExpectation = candidate.verifiedExpectation,
                preconditions = candidate.preconditions,
                binding = ExecutionBindingResponse.from(candidate.binding),
                citations = candidate.citations.map(KnowledgeCitationResponse::from),
                requiredEvidence = candidate.requiredEvidence,
                dataLifecyclePlan = candidate.dataLifecyclePlan,
            )
        }
    }
}
