package com.project.agenticreliabilitylab.targetintelligence.application

import com.project.agenticreliabilitylab.targetintelligence.domain.DomainHypothesis
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedInvariant
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedWorkflow
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractionWarning
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceDocument
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceType
import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignal
import org.springframework.stereotype.Component

/**
 * Records the structured Target Brief the user filled in.
 *
 * These values are the only Phase 11 input recorded as STATED: the user declared them in dedicated fields, so ARL does
 * not have to interpret prose to read them. They still describe the Target, not an instruction to ARL.
 */
@Component
class TargetBriefKnowledgeExtractor {
    fun extract(brief: TargetBriefInput): KnowledgeFragment {
        val canonical = canonicalForm(brief)
        return KnowledgeFragment(
            source = KnowledgeSourceDocument(
                type = KnowledgeSourceType.BRIEF,
                byteCount = canonical.toByteArray(Charsets.UTF_8).size,
                checksum = sha256Hex(canonical),
            ),
            workflows = workflows(brief),
            domainHypotheses = domainHypotheses(brief),
            invariants = invariants(brief),
            riskSignals = riskSignals(brief),
            warnings = warnings(brief),
        )
    }

    private fun workflows(brief: TargetBriefInput): List<ExtractedWorkflow> = brief.workflows
        .take(MAX_WORKFLOWS)
        .mapIndexed { index, workflow ->
            ExtractedWorkflow(
                title = workflow.title.toBoundedTitle(),
                steps = workflow.steps.take(MAX_WORKFLOW_STEPS).map(String::toStatement),
                confidence = KnowledgeConfidence.STATED,
                citation = citation(
                    sourceType = KnowledgeSourceType.BRIEF,
                    location = "brief.workflows[$index]",
                    excerpt = workflow.title,
                ),
            )
        }

    private fun invariants(brief: TargetBriefInput): List<ExtractedInvariant> = brief.invariants
        .take(MAX_INVARIANTS)
        .mapIndexed { index, statement ->
            ExtractedInvariant(
                statement = statement.toStatement(),
                confidence = KnowledgeConfidence.STATED,
                citations = listOf(
                    citation(KnowledgeSourceType.BRIEF, "brief.invariants[$index]", statement),
                ),
            )
        }

    /**
     * Normalizes a declared term to the shared concept vocabulary when it matches one.
     *
     * The document extractors store the vocabulary key, so a Brief that says "재고" has to become the same concept as a
     * README that says the same thing. Otherwise the two stay separate hypotheses and any rule that keys off the
     * canonical concept silently misses a Brief-only declaration. Unrecognized terms are kept verbatim.
     */
    private fun domainHypotheses(brief: TargetBriefInput): List<DomainHypothesis> = brief.domainTerms
        .take(MAX_DOMAIN_HYPOTHESES)
        .mapIndexed { index, term ->
            val canonical = matchedDomainConcepts(term.lowercase()).firstOrNull()
            DomainHypothesis(
                concept = canonical ?: term.toBoundedTitle(),
                description = canonical
                    ?.let { concept -> "사용자가 입력한 '${term.toBoundedTitle()}'을 '$concept' 개념으로 정규화했습니다." }
                    ?: "사용자가 직접 입력한 도메인 용어입니다.",
                confidence = KnowledgeConfidence.STATED,
                citations = listOf(
                    citation(KnowledgeSourceType.BRIEF, "brief.domainTerms[$index]", term),
                ),
            )
        }

    private fun riskSignals(brief: TargetBriefInput): List<RiskSignal> = brief.components
        .take(MAX_RISK_SIGNALS)
        .flatMapIndexed { index: Int, component: String ->
            matchedRiskTypes(component.lowercase()).map { type ->
                RiskSignal(
                    type = type,
                    confidence = KnowledgeConfidence.STATED,
                    citation = citation(KnowledgeSourceType.BRIEF, "brief.components[$index]", component),
                )
            }
        }
        .boundedRiskSignals()

    private fun warnings(brief: TargetBriefInput): List<ExtractionWarning> = buildList {
        val unmatched = brief.components.filter { component -> matchedRiskTypes(component.lowercase()).isEmpty() }
        if (unmatched.isNotEmpty()) {
            add(
                ExtractionWarning(
                    code = "BRIEF_UNRECOGNIZED_COMPONENT",
                    message = "알려진 위험 신호로 분류하지 못한 구성 요소가 ${unmatched.size}개 있습니다.",
                ),
            )
        }
        if (brief.invariants.isEmpty()) {
            add(
                ExtractionWarning(
                    code = "BRIEF_NO_INVARIANTS",
                    message = "확정된 불변식이 없어 상태 변경 테스트 후보는 확인이 더 필요합니다.",
                ),
            )
        }
    }

    /** Stable text form used for the source checksum so identical Brief input yields an identical Snapshot. */
    private fun canonicalForm(brief: TargetBriefInput): String = buildString {
        append("domainTerms=").append(brief.domainTerms.joinToString(",")).append('\n')
        append("invariants=").append(brief.invariants.joinToString(",")).append('\n')
        append("components=").append(brief.components.joinToString(",")).append('\n')
        brief.workflows.forEach { workflow ->
            append("workflow=").append(workflow.title).append(':').append(workflow.steps.joinToString(">")).append('\n')
        }
    }
}
