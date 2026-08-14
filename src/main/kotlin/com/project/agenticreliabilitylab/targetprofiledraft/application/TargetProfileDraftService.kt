package com.project.agenticreliabilitylab.targetprofiledraft.application

import com.project.agenticreliabilitylab.targetprofiledraft.application.port.TargetProfileDraftExtractor
import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraft
import org.springframework.stereotype.Service

@Service
class TargetProfileDraftService(
    private val openApiDraftExtractor: OpenApiDraftExtractor,
    private val readmeDraftExtractor: ReadmeDraftExtractor,
) {
    fun fromOpenApi(document: String): TargetProfileDraft = openApiDraftExtractor.extract(document)

    fun fromReadme(document: String): TargetProfileDraft = readmeDraftExtractor.extract(document)
}
