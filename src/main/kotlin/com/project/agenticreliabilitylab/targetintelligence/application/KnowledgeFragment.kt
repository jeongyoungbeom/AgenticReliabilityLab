package com.project.agenticreliabilitylab.targetintelligence.application

import com.project.agenticreliabilitylab.targetintelligence.domain.DomainHypothesis
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedInvariant
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedOperation
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedWorkflow
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractionWarning
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceDocument
import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignal

/** What one supplied document contributed to a Knowledge Snapshot before the parts are merged. */
data class KnowledgeFragment(
    val source: KnowledgeSourceDocument,
    val operations: List<ExtractedOperation> = emptyList(),
    val workflows: List<ExtractedWorkflow> = emptyList(),
    val domainHypotheses: List<DomainHypothesis> = emptyList(),
    val invariants: List<ExtractedInvariant> = emptyList(),
    val riskSignals: List<RiskSignal> = emptyList(),
    val warnings: List<ExtractionWarning> = emptyList(),
)
