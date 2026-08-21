package com.project.agenticreliabilitylab.testspec.application.port

import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationCandidateOutcome
import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationRunDetails
import com.project.agenticreliabilitylab.testspec.domain.TestSpecGenerationRunRecord
import java.time.Instant
import java.util.UUID

/** Persistence boundary for LLM-proposed specification generation runs and the candidates each run produced. */
interface TestSpecGenerationStore {
    fun create(command: NewTestSpecGenerationRun)
    fun findById(id: UUID): TestSpecGenerationRunRecord?
    fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): TestSpecGenerationRunRecord?
    fun findDetails(id: UUID): TestSpecGenerationRunDetails?
    fun claim(id: UUID, now: Instant): Boolean
    fun complete(id: UUID, completion: TestSpecGenerationCompletion, now: Instant)
    fun fail(id: UUID, code: String, message: String, now: Instant)
    fun findRequestedIds(): List<UUID>
    fun findRunningIds(): List<UUID>
}

data class NewTestSpecGenerationRun(
    val id: UUID,
    val targetSystemId: String,
    val knowledgeSnapshotId: UUID,
    val profileVersionId: UUID,
    val idempotencyKey: String,
    val configurationHash: String,
    val modelKey: String,
    val modelId: String,
    val promptVersion: String,
    val inputBundleJson: String,
    val inputChecksum: String,
    val requestedBy: String,
    val requestedCorrelationId: String,
    val requestedAt: Instant,
)

data class NewTestSpecGenerationCandidate(
    val id: UUID,
    val outcome: TestSpecGenerationCandidateOutcome,
    val specKey: String,
    val title: String,
    val documentJson: String,
    val rejectionReason: String?,
    val specificationId: UUID?,
)

data class TestSpecGenerationCompletion(
    val candidates: List<NewTestSpecGenerationCandidate>,
    val promptTokenCount: Int?,
    val completionTokenCount: Int?,
    val durationMillis: Long?,
)
