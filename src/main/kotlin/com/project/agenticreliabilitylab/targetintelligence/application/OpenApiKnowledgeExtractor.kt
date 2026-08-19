package com.project.agenticreliabilitylab.targetintelligence.application

import com.project.agenticreliabilitylab.targetintelligence.domain.DomainHypothesis
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedOperation
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractionWarning
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceDocument
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeSourceType
import com.project.agenticreliabilitylab.targetintelligence.domain.OperationMutability
import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignal
import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignalType
import com.project.agenticreliabilitylab.targetprofiledraft.application.BoundedOpenApiDocumentParser
import com.project.agenticreliabilitylab.targetprofiledraft.application.OpenApiInternalReferenceResolver
import org.springframework.stereotype.Component

/**
 * Extracts Target understanding from an OpenAPI document.
 *
 * Parsing, size bounds, external `$ref` rejection and document-local pointer resolution are delegated to the Phase 10.6
 * components so that the Profile Draft and the Knowledge Snapshot share exactly one parser. Nothing here contacts the
 * Target: only the supplied text is read.
 */
@Component
class OpenApiKnowledgeExtractor(
    private val documentParser: BoundedOpenApiDocumentParser,
    private val referenceResolver: OpenApiInternalReferenceResolver,
) {
    fun extract(document: String): KnowledgeFragment {
        val root = documentParser.parse(document)
        require(root[OPENAPI_VERSION] is String) { "OpenAPI document must declare an 'openapi' version" }
        val operations = extractOperations(root)
        return KnowledgeFragment(
            source = KnowledgeSourceDocument(
                type = KnowledgeSourceType.OPENAPI,
                byteCount = document.toByteArray(Charsets.UTF_8).size,
                checksum = sha256Hex(document),
            ),
            operations = operations,
            domainHypotheses = domainHypotheses(operations),
            riskSignals = riskSignals(operations, root),
            warnings = warnings(operations),
        )
    }

    private fun extractOperations(root: Map<String, Any?>): List<ExtractedOperation> =
        (root[PATHS] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (path, item) ->
                (path as? String)?.let { name -> name to referenceResolver.resolvePathItem(root, item) }
            }
            ?.flatMap { (path, pathItem) -> operationsOf(path, pathItem) }
            ?.take(MAX_OPERATIONS)
            .orEmpty()

    private fun operationsOf(path: String, pathItem: Map<*, *>?): List<ExtractedOperation> =
        pathItem?.entries.orEmpty()
            .mapNotNull { (method, definition) ->
                val name = (method as? String)?.uppercase()?.takeIf(HTTP_METHODS::contains) ?: return@mapNotNull null
                (definition as? Map<*, *>)?.let { operation -> toOperation(path, name, operation) }
            }

    private fun toOperation(path: String, method: String, operation: Map<*, *>): ExtractedOperation =
        ExtractedOperation(
            method = method,
            path = path.toBoundedTitle(),
            operationId = (operation[OPERATION_ID] as? String)?.toBoundedTitle(),
            summary = (operation[SUMMARY] as? String)?.toBoundedTitle(),
            requestMediaTypes = requestMediaTypes(operation),
            responseStatusCodes = responseStatusCodes(operation),
            mutability = method.toMutability(),
            citation = citation(
                sourceType = KnowledgeSourceType.OPENAPI,
                location = "paths.$path.${method.lowercase()}",
                excerpt = "$method $path",
            ),
        )

    private fun requestMediaTypes(operation: Map<*, *>): Set<String> =
        ((operation[REQUEST_BODY] as? Map<*, *>)?.get(CONTENT) as? Map<*, *>)
            ?.keys
            ?.filterIsInstance<String>()
            ?.mapTo(linkedSetOf()) { type -> type.toBoundedTitle() }
            .orEmpty()

    private fun responseStatusCodes(operation: Map<*, *>): Set<Int> =
        (operation[RESPONSES] as? Map<*, *>)
            ?.keys
            ?.mapNotNull { code -> (code as? String)?.toIntOrNull() }
            ?.toCollection(linkedSetOf())
            .orEmpty()

    private fun domainHypotheses(operations: List<ExtractedOperation>): List<DomainHypothesis> = operations
        .flatMap { operation -> matchedDomainConcepts(operation.path.lowercase()).map { it to operation } }
        .groupBy({ (concept, _) -> concept }, { (_, operation) -> operation })
        .entries
        .take(MAX_DOMAIN_HYPOTHESES)
        .map { (concept, matched) ->
            DomainHypothesis(
                concept = concept,
                description = "OpenAPI path 이름에서 '$concept' 개념을 추론했습니다. 실제 도메인 규칙은 확인이 필요합니다.",
                confidence = KnowledgeConfidence.ASSUMPTION,
                citations = matched.take(MAX_CITATIONS_PER_ITEM).map(ExtractedOperation::citation),
            )
        }

    private fun riskSignals(operations: List<ExtractedOperation>, root: Map<String, Any?>): List<RiskSignal> {
        val textual = operations.flatMap { operation ->
            val text = listOfNotNull(operation.operationId, operation.summary, operation.path)
                .joinToString(" ")
                .lowercase()
            matchedRiskTypes(text).map { type -> RiskSignal(type, KnowledgeConfidence.ASSUMPTION, operation.citation) }
        }
        val structural = operations
            .filter { operation -> ACCEPTED_STATUS in operation.responseStatusCodes }
            .map { operation -> RiskSignal(RiskSignalType.ASYNC, KnowledgeConfidence.ASSUMPTION, operation.citation) }
        val documentWide = matchedRiskTypes(idempotencyText(root)).map { type ->
            RiskSignal(
                type = type,
                confidence = KnowledgeConfidence.ASSUMPTION,
                citation = citation(KnowledgeSourceType.OPENAPI, "parameters", "declared parameter name"),
            )
        }
        return (textual + structural + documentWide).boundedRiskSignals()
    }

    /**
     * Collects every declared `name` value in the document so naming such as `Idempotency-Key` can raise a signal.
     *
     * This deliberately over-collects: schema property names are included too, which is why the resulting signals
     * are recorded as ASSUMPTION and cited as a declared name rather than as a confirmed contract.
     */
    private fun idempotencyText(root: Map<String, Any?>): String = buildString {
        collectParameterNames(root, this)
    }.lowercase()

    private fun collectParameterNames(value: Any?, sink: StringBuilder) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                if (key == NAME && child is String) sink.append(child).append(' ')
                collectParameterNames(child, sink)
            }
            is List<*> -> value.forEach { child -> collectParameterNames(child, sink) }
        }
    }

    private fun warnings(operations: List<ExtractedOperation>): List<ExtractionWarning> = buildList {
        add(
            ExtractionWarning(
                code = "OPENAPI_NO_TARGET_CONTACT",
                message = "OpenAPI 문서만 읽었고 Target에 요청을 보내지 않았습니다.",
            ),
        )
        if (operations.isEmpty()) {
            add(ExtractionWarning("OPENAPI_NO_OPERATIONS", "문서에서 operation을 찾지 못했습니다."))
        }
        if (operations.any { operation -> operation.responseStatusCodes.isEmpty() }) {
            add(ExtractionWarning("OPENAPI_MISSING_RESPONSES", "응답 상태가 없는 operation이 있습니다."))
        }
        if (operations.none { operation -> operation.mutability == OperationMutability.WRITE }) {
            add(ExtractionWarning("OPENAPI_NO_WRITE_OPERATIONS", "상태 변경 operation을 찾지 못했습니다."))
        }
    }

    private companion object {
        const val OPENAPI_VERSION = "openapi"
        const val PATHS = "paths"
        const val OPERATION_ID = "operationId"
        const val SUMMARY = "summary"
        const val REQUEST_BODY = "requestBody"
        const val CONTENT = "content"
        const val RESPONSES = "responses"
        const val NAME = "name"
        const val ACCEPTED_STATUS = 202
        const val MAX_CITATIONS_PER_ITEM = 5
    }
}
