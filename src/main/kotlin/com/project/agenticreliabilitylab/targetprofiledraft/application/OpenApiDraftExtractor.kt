package com.project.agenticreliabilitylab.targetprofiledraft.application

import com.project.agenticreliabilitylab.targetprofiledraft.application.port.TargetProfileDraftExtractor
import com.project.agenticreliabilitylab.targetprofiledraft.domain.DraftReadOnlyOperation
import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraft
import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraftSource
import org.springframework.stereotype.Component

/** Extracts only static GET operations without resolving external references or contacting a server. */
@Component
class OpenApiDraftExtractor(
    private val documentParser: BoundedOpenApiDocumentParser,
    private val referenceResolver: OpenApiInternalReferenceResolver,
    private val yamlRenderer: ProfileDraftYamlRenderer,
) : TargetProfileDraftExtractor {
    override fun extract(document: String): TargetProfileDraft {
        val root = documentParser.parse(document)
        require(root[OPENAPI_VERSION] is String) { "OpenAPI document must declare an 'openapi' version" }
        val targetName = root.objectValue(INFO)?.stringValue(TITLE) ?: DEFAULT_TARGET_NAME
        val targetId = targetName.toDraftIdentifier(DEFAULT_TARGET_ID)
        val baseUrl = root.objectListValue(SERVERS)
            ?.firstOrNull()
            ?.stringValue(URL)
            ?.takeIf(String::isSafeServerOrigin)
        val operations = root.objectValue(PATHS)
            ?.entries
            ?.mapNotNull { (path, item) -> extractOperation(root, path, item) }
            ?.take(MAX_OPERATIONS)
            .orEmpty()
        return yamlRenderer.render(
            source = TargetProfileDraftSource.OPENAPI,
            targetId = targetId,
            targetName = targetName,
            baseUrl = baseUrl,
            operations = operations,
            warnings = warnings(baseUrl, operations),
        )
    }

    private fun extractOperation(
        root: Map<String, Any?>,
        path: String,
        item: Any?,
    ): DraftReadOnlyOperation? {
        val pathItem = referenceResolver.resolvePathItem(root, item)
        val operation = pathItem?.get(GET) as? Map<*, *>
        val expectedStatuses = operation?.responseStatusCodes().orEmpty()
        val safeOperation = path.isStaticRelativePath() && pathItem != null && operation != null &&
            !pathItem.hasParameters() && !operation.hasParameters() && !operation.containsKey(REQUEST_BODY)
        return operation?.takeIf { safeOperation && expectedStatuses.isNotEmpty() }?.let { safeGet ->
            DraftReadOnlyOperation(
                id = (safeGet.stringValue(OPERATION_ID) ?: path).toDraftIdentifier("get-operation"),
                title = safeGet.stringValue(SUMMARY)?.toBoundedTitle("GET $path") ?: "GET $path",
                description = safeGet.stringValue(DESCRIPTION)
                    ?.take(MAX_DRAFT_DESCRIPTION_LENGTH)
                    ?: "OpenAPI declared GET operation.",
                path = path,
                expectedStatusCodes = expectedStatuses,
            )
        }
    }

    private fun warnings(baseUrl: String?, operations: List<DraftReadOnlyOperation>): List<String> = buildList {
        add("Draft는 disabled 상태이며, 검증·활성화 전에는 어떤 Target 요청도 보내지 않습니다.")
        add("allowed-cidrs와 health-path는 실제 Target 경계에 맞게 검토해야 합니다.")
        if (baseUrl == null) add("OpenAPI server URL을 안전한 origin으로 해석하지 못해 loopback placeholder를 넣었습니다.")
        if (operations.isEmpty()) add("고정 path와 성공 응답을 가진 GET operation을 찾지 못했습니다.")
    }

    private companion object {
        const val OPENAPI_VERSION = "openapi"
        const val INFO = "info"
        const val TITLE = "title"
        const val SERVERS = "servers"
        const val URL = "url"
        const val PATHS = "paths"
        const val DEFAULT_TARGET_ID = "openapi-target"
        const val DEFAULT_TARGET_NAME = "OpenAPI Target"
        const val MAX_OPERATIONS = 20
    }
}
