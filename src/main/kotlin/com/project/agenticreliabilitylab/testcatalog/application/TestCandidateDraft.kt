package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeCitation
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBinding
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateCategory
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateRisk

/** A proposed candidate before identifiers and ordering are assigned. */
data class TestCandidateDraft(
    val category: TestCandidateCategory,
    val title: String,
    val description: String,
    val risk: TestCandidateRisk,
    val confidence: KnowledgeConfidence,
    val verifiedExpectation: String,
    val preconditions: List<String>,
    val binding: ExecutionBinding,
    val citations: List<KnowledgeCitation>,
    val requiredEvidence: List<String>,
    val dataLifecyclePlan: String?,
)
