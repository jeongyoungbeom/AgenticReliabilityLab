package com.project.agenticreliabilitylab.targetprofiledraft.application.port

import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraft

interface TargetProfileDraftExtractor {
    fun extract(document: String): TargetProfileDraft
}
