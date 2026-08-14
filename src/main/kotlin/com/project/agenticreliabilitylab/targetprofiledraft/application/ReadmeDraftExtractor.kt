package com.project.agenticreliabilitylab.targetprofiledraft.application

import com.project.agenticreliabilitylab.targetprofiledraft.application.port.TargetProfileDraftExtractor
import com.project.agenticreliabilitylab.targetprofiledraft.domain.DraftReadOnlyOperation
import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraft
import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraftSource
import org.springframework.stereotype.Component

/** Extracts only literal `GET /path` examples from README text; prose is never treated as an instruction. */
@Component
class ReadmeDraftExtractor(
    private val yamlRenderer: ProfileDraftYamlRenderer,
) : TargetProfileDraftExtractor {
    override fun extract(document: String): TargetProfileDraft {
        require(document.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_BYTES) {
            "README document exceeds $MAX_DOCUMENT_BYTES bytes"
        }
        val targetName = title(document) ?: DEFAULT_TARGET_NAME
        val operations = GET_OPERATION.findAll(document)
            .mapNotNull { match -> match.groups[PATH]?.value?.toOperation(match.groups[LABEL]?.value) }
            .distinctBy(DraftReadOnlyOperation::path)
            .take(MAX_OPERATIONS)
            .toList()
        return yamlRenderer.render(
            source = TargetProfileDraftSource.README,
            targetId = targetName.toTargetId(),
            targetName = targetName,
            baseUrl = null,
            operations = operations,
            warnings = listOf(
                "README는 비신뢰 텍스트로만 해석했습니다. 실행 가능한 명령이나 지시로 처리하지 않았습니다.",
                "Draft는 disabled 상태이며, base URL, allowed-cidrs, health-path를 검토해야 합니다.",
                if (operations.isEmpty()) "고정 path를 가진 GET 예시를 찾지 못했습니다." else "GET 예시 ${operations.size}개를 제안했습니다.",
            ),
        )
    }

    private fun title(document: String): String? = document.lineSequence()
        .firstOrNull { line -> line.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?.take(MAX_TITLE_LENGTH)
        ?.takeIf(String::isNotBlank)

    private fun String.toOperation(label: String?): DraftReadOnlyOperation? {
        if (!isSafeReadmePath()) return null
        val id = lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(MAX_ID_LENGTH)
            .ifBlank { "get-operation" }
        val title = label?.trim()?.take(MAX_TITLE_LENGTH)?.ifBlank { null } ?: "GET $this"
        return DraftReadOnlyOperation(
            id = id,
            title = title,
            description = "README declared GET example.",
            path = this,
            expectedStatusCodes = setOf(HTTP_OK),
        )
    }

    private fun String.isSafeReadmePath(): Boolean = startsWith('/') && !startsWith("//") &&
        FORBIDDEN_PATH_MARKERS.none(::contains)

    private fun String.toTargetId(): String = lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(MAX_ID_LENGTH)
        .ifBlank { DEFAULT_TARGET_ID }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 262_144
        const val MAX_OPERATIONS = 20
        const val MAX_ID_LENGTH = 100
        const val MAX_TITLE_LENGTH = 200
        const val DEFAULT_TARGET_ID = "readme-target"
        const val DEFAULT_TARGET_NAME = "README Target"
        const val HTTP_OK = 200
        const val PATH = "path"
        const val LABEL = "label"
        val FORBIDDEN_PATH_MARKERS = listOf("{", "?", "#", "..")
        val GET_OPERATION = Regex(
            """(?im)^\s*(?:[-*]\s*)?GET\s+(?<path>/[^\s?`#]+)(?:\s*(?:[-—:]|\|)\s*(?<label>[^\r\n]+))?\s*$""",
        )
    }
}
