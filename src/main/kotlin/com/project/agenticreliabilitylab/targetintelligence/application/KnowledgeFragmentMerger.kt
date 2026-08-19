package com.project.agenticreliabilitylab.targetintelligence.application

import com.project.agenticreliabilitylab.targetintelligence.domain.DomainHypothesis
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedInvariant
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeContent
import org.springframework.stereotype.Component

/** Merges per-document fragments into one bounded, deduplicated Knowledge Snapshot content. */
@Component
class KnowledgeFragmentMerger {
    fun merge(fragments: List<KnowledgeFragment>): TargetKnowledgeContent = TargetKnowledgeContent(
        sources = fragments.map(KnowledgeFragment::source),
        operations = fragments.flatMap(KnowledgeFragment::operations)
            .distinctBy { operation -> operation.method to operation.path }
            .take(MAX_OPERATIONS),
        workflows = fragments.flatMap(KnowledgeFragment::workflows).take(MAX_WORKFLOWS),
        domainHypotheses = mergeHypotheses(fragments),
        invariants = mergeInvariants(fragments),
        riskSignals = fragments.flatMap(KnowledgeFragment::riskSignals).boundedRiskSignals(),
        warnings = fragments.flatMap(KnowledgeFragment::warnings).distinctBy { warning -> warning.code },
    )

    /** A concept declared in the Brief stays STATED even when prose mentioned it too. */
    private fun mergeHypotheses(fragments: List<KnowledgeFragment>): List<DomainHypothesis> = fragments
        .flatMap(KnowledgeFragment::domainHypotheses)
        .groupBy { hypothesis -> hypothesis.concept.lowercase() }
        .values
        .take(MAX_DOMAIN_HYPOTHESES)
        .map { hypotheses ->
            val stated = hypotheses.firstOrNull { it.confidence == KnowledgeConfidence.STATED }
            (stated ?: hypotheses.first()).copy(
                citations = hypotheses.flatMap(DomainHypothesis::citations).take(MAX_CITATIONS),
            )
        }

    private fun mergeInvariants(fragments: List<KnowledgeFragment>): List<ExtractedInvariant> = fragments
        .flatMap(KnowledgeFragment::invariants)
        .groupBy { invariant -> invariant.statement.lowercase() }
        .values
        .take(MAX_INVARIANTS)
        .map { invariants ->
            val stated = invariants.firstOrNull { it.confidence == KnowledgeConfidence.STATED }
            (stated ?: invariants.first()).copy(
                citations = invariants.flatMap(ExtractedInvariant::citations).take(MAX_CITATIONS),
            )
        }

    private companion object {
        const val MAX_CITATIONS = 10
    }
}
