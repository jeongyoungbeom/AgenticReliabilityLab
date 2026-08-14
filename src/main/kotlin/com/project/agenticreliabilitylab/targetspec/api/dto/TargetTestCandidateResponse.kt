package com.project.agenticreliabilitylab.targetspec.api.dto

import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidateKind

data class TargetTestCandidateResponse(
    val id: String,
    val kind: TargetTestCandidateKind,
    val title: String,
    val description: String,
    val method: String,
    val path: String,
    val expectedStatusCodes: Set<Int>,
    val timeoutMs: Long,
) {
    companion object {
        fun from(candidate: TargetTestCandidate) = TargetTestCandidateResponse(
            id = candidate.id, kind = candidate.kind, title = candidate.title, description = candidate.description,
            method = candidate.method, path = candidate.path, expectedStatusCodes = candidate.expectedStatusCodes,
            timeoutMs = candidate.timeout.toMillis(),
        )
    }
}
