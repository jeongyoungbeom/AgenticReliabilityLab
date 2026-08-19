package com.project.agenticreliabilitylab.targetintelligence.application

import com.project.agenticreliabilitylab.targetintelligence.domain.DomainHypothesis
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedInvariant
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedOperation
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractionWarning
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeCitation
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceDocument
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceType
import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignal
import org.springframework.stereotype.Component

/**
 * Extracts Target understanding from README or free-form service description text.
 *
 * The document is treated strictly as untrusted data: only literal `METHOD /path` lines, risk keywords and rule-like
 * sentences are matched. Prose is never followed as an instruction, no link is fetched and the Target is never called.
 * Because every result here is an ARL inference over prose, all of it is recorded as ASSUMPTION.
 */
@Component
class ReadmeKnowledgeExtractor {
    fun extract(document: String): KnowledgeFragment {
        require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "README document exceeds $MAX_DOCUMENT_BYTES bytes"
        }
        val lines = document.lineSequence().take(MAX_LINES).toList()
        val operations = operations(lines)
        return KnowledgeFragment(
            source = KnowledgeSourceDocument(
                type = KnowledgeSourceType.README,
                byteCount = document.toByteArray(Charsets.UTF_8).size,
                checksum = sha256Hex(document),
            ),
            operations = operations,
            domainHypotheses = domainHypotheses(lines),
            invariants = invariants(lines),
            riskSignals = riskSignals(lines),
            warnings = warnings(operations),
        )
    }

    private fun operations(lines: List<String>): List<ExtractedOperation> = lines
        .withIndex()
        .mapNotNull { (index, line) -> toOperation(index + 1, line) }
        .distinctBy { operation -> operation.method to operation.path }
        .take(MAX_OPERATIONS)

    private fun toOperation(lineNumber: Int, line: String): ExtractedOperation? {
        val groups = OPERATION_LINE.find(line)?.groups
        val method = groups?.get(METHOD)?.value?.uppercase()
        val path = groups?.get(PATH)?.value?.toBoundedTitle()
        val label = groups?.get(LABEL)?.value?.toBoundedTitle()
        return if (method == null || path == null) {
            null
        } else {
            ExtractedOperation(
                method = method,
                path = path,
                operationId = null,
                summary = label,
                requestMediaTypes = emptySet(),
                responseStatusCodes = emptySet(),
                mutability = method.toMutability(),
                citation = lineCitation(lineNumber, line),
            )
        }
    }

    private fun invariants(lines: List<String>): List<ExtractedInvariant> = lines
        .withIndex()
        .filter { (_, line) -> line.isNotBlank() && line.looksLikeInvariant() }
        .take(MAX_INVARIANTS)
        .map { (index, line) ->
            ExtractedInvariant(
                statement = line.toStatement(),
                confidence = KnowledgeConfidence.ASSUMPTION,
                citations = listOf(lineCitation(index + 1, line)),
            )
        }

    private fun riskSignals(lines: List<String>): List<RiskSignal> = lines
        .withIndex()
        .flatMap { (index, line) ->
            matchedRiskTypes(line.lowercase()).map { type ->
                RiskSignal(type, KnowledgeConfidence.ASSUMPTION, lineCitation(index + 1, line))
            }
        }
        .boundedRiskSignals()

    private fun domainHypotheses(lines: List<String>): List<DomainHypothesis> = lines
        .withIndex()
        .flatMap { (index, line) ->
            matchedDomainConcepts(line.lowercase()).map { concept -> concept to lineCitation(index + 1, line) }
        }
        .groupBy({ (concept, _) -> concept }, { (_, citation) -> citation })
        .entries
        .take(MAX_DOMAIN_HYPOTHESES)
        .map { (concept, citations) ->
            DomainHypothesis(
                concept = concept,
                description = "README 문장에서 '$concept' 개념을 추론했습니다. 실제 도메인 규칙은 확인이 필요합니다.",
                confidence = KnowledgeConfidence.ASSUMPTION,
                citations = citations.take(MAX_CITATIONS_PER_ITEM),
            )
        }

    private fun lineCitation(lineNumber: Int, line: String): KnowledgeCitation =
        citation(KnowledgeSourceType.README, "line $lineNumber", line)

    private fun warnings(operations: List<ExtractedOperation>): List<ExtractionWarning> = buildList {
        add(
            ExtractionWarning(
                code = "README_UNTRUSTED_TEXT",
                message = "README는 비신뢰 텍스트로만 해석했고 명령·링크를 실행하거나 가져오지 않았습니다.",
            ),
        )
        add(
            ExtractionWarning(
                code = "README_ASSUMPTION_ONLY",
                message = "README에서 추출한 규칙과 개념은 모두 ASSUMPTION이며 확인 전에는 oracle로 사용할 수 없습니다.",
            ),
        )
        if (operations.isEmpty()) {
            add(ExtractionWarning("README_NO_OPERATIONS", "README에서 'METHOD /path' 형태의 예시를 찾지 못했습니다."))
        }
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 262_144
        const val MAX_LINES = 20_000
        const val MAX_CITATIONS_PER_ITEM = 5
        const val METHOD = "method"
        const val PATH = "path"
        const val LABEL = "label"
        val OPERATION_LINE = Regex(
            """(?i)^\s*(?:[-*]\s*)?(?<method>GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+""" +
                """(?<path>/[^\s`]*)(?:\s*(?:[-—:|])\s*(?<label>.+))?\s*$""",
        )
    }
}
