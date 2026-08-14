package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.domain.AnalysisFindingRecord
import com.project.agenticreliabilitylab.analysis.domain.FindingSeverity

data class AnalysisFindingResponse(
    val ordinal: Int,
    val severity: FindingSeverity,
    val title: String,
    val rationale: String,
    val evidenceIds: List<String>,
) {
    companion object {
        fun from(finding: AnalysisFindingRecord) = AnalysisFindingResponse(
            ordinal = finding.ordinal,
            severity = finding.severity,
            title = finding.title,
            rationale = finding.rationale,
            evidenceIds = finding.evidenceIds,
        )
    }
}
