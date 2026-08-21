package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationRunDetails
import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationRunStatus
import tools.jackson.databind.ObjectMapper
import java.time.Instant

data class TestSpecGenerationRunResponse(
    val id: String,
    val targetSystemId: String,
    val knowledgeSnapshotId: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputChecksum: String,
    val status: TestSpecGenerationRunStatus,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
    val failureCode: String?,
    val failureMessage: String?,
    val requestedAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val candidates: List<TestSpecGenerationCandidateResponse>,
) {
    companion object {
        fun from(details: TestSpecGenerationRunDetails, objectMapper: ObjectMapper) = TestSpecGenerationRunResponse(
            id = details.run.id.toString(),
            targetSystemId = details.run.targetSystemId,
            knowledgeSnapshotId = details.run.knowledgeSnapshotId.toString(),
            modelKey = details.run.modelKey,
            modelId = details.run.modelId,
            promptVersion = details.run.promptVersion,
            inputChecksum = details.run.inputChecksum,
            status = details.run.status,
            promptTokenCount = details.run.promptTokenCount,
            completionTokenCount = details.run.completionTokenCount,
            durationMillis = details.run.durationMillis,
            failureCode = details.run.failureCode,
            failureMessage = details.run.failureMessage,
            requestedAt = details.run.requestedAt,
            startedAt = details.run.startedAt,
            completedAt = details.run.completedAt,
            candidates = details.candidates.map { candidate ->
                TestSpecGenerationCandidateResponse(
                    ordinal = candidate.ordinal,
                    outcome = candidate.outcome,
                    specKey = candidate.specKey,
                    title = candidate.title,
                    document = objectMapper.readTree(candidate.documentJson),
                    rejectionReason = candidate.rejectionReason,
                    specificationId = candidate.specificationId?.toString(),
                )
            },
        )
    }
}
