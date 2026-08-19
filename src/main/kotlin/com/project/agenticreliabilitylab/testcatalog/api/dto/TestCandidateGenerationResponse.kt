package com.project.agenticreliabilitylab.testcatalog.api.dto

import com.project.agenticreliabilitylab.testcatalog.application.TestCandidateGenerationView
import java.time.Instant
import java.util.UUID

data class TestCandidateGenerationResponse(
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
    val candidates: List<TestCandidateResponse>,
) {
    companion object {
        fun from(view: TestCandidateGenerationView): TestCandidateGenerationResponse {
            val generation = view.generation
            return TestCandidateGenerationResponse(
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
                candidates = view.candidates.map(TestCandidateResponse::from),
            )
        }
    }
}
