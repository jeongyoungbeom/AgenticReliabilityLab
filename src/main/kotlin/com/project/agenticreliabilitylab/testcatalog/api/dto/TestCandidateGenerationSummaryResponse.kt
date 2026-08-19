package com.project.agenticreliabilitylab.testcatalog.api.dto

import com.project.agenticreliabilitylab.testcatalog.application.TestCandidateGenerationSummaryView
import java.time.Instant
import java.util.UUID

/** List item without candidates; the detail endpoint returns the candidates and their readiness. */
data class TestCandidateGenerationSummaryResponse(
    val id: UUID,
    val targetSystemId: String,
    val knowledgeSnapshotId: UUID,
    val profileVersionId: UUID,
    val profileVersionActive: Boolean,
    val source: String,
    val generatorVersion: String,
    val checksum: String,
    val createdBy: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(view: TestCandidateGenerationSummaryView): TestCandidateGenerationSummaryResponse {
            val generation = view.generation
            return TestCandidateGenerationSummaryResponse(
                id = generation.id,
                targetSystemId = generation.targetSystemId,
                knowledgeSnapshotId = generation.knowledgeSnapshotId,
                profileVersionId = generation.profileVersionId,
                profileVersionActive = view.profileVersionActive,
                source = generation.source.name,
                generatorVersion = generation.generatorVersion,
                checksum = generation.checksum,
                createdBy = generation.createdBy,
                createdAt = generation.createdAt,
            )
        }
    }
}
