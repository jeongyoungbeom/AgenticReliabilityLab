package com.project.agenticreliabilitylab.testcatalog.domain

import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeCitation
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import java.time.Instant
import java.util.UUID

/** Test areas from the Phase 12 candidate taxonomy. */
enum class TestCandidateCategory {
    AVAILABILITY,
    CONTRACT_INPUT,
    WORKFLOW,
    RETRY_RECOVERY,
    IDEMPOTENCY,
    CONCURRENCY,
    CONSISTENCY,
}

/** Risk levels reused from the existing Experiment risk policy. */
enum class TestCandidateRisk {
    SAFE,
    MODERATE,
    DESTRUCTIVE,
}

enum class ExecutionBindingKind {
    READ_ONLY_BATCH,
    EXPERIMENT,
    UNBOUND,
}

/** Why a candidate could not be bound to an existing execution unit. */
enum class CandidateUnresolvedReason {
    NO_SAFE_EXECUTION_PATH,
    MISSING_INVARIANT,
    MISSING_TEST_DATA_LIFECYCLE,
    MISSING_OBSERVATION_CAPABILITY,
    UNSUPPORTED_TEST_TYPE,
}

/**
 * How a candidate would actually run. This is the stored single source of truth.
 *
 * A candidate never introduces a new execution unit: it either points at read-only candidates already registered in the
 * active Profile, or at an [ExperimentType] already present in the Experiment Catalog. Anything else stays
 * [ExecutionBindingKind.UNBOUND] with the reason that blocks it. Readiness is derived from this plus current Target
 * capability at query time and is deliberately not stored.
 *
 * The kind discriminator is a plain field rather than a sealed hierarchy so the value survives a JSON round trip.
 */
data class ExecutionBinding(
    val kind: ExecutionBindingKind,
    val targetTestCandidateIds: List<String>,
    val experimentType: ExperimentType?,
    val requiredCapability: String?,
    val unresolvedReason: CandidateUnresolvedReason?,
    val unresolvedDetail: String?,
) {
    companion object {
        fun readOnlyBatch(targetTestCandidateIds: List<String>): ExecutionBinding = ExecutionBinding(
            kind = ExecutionBindingKind.READ_ONLY_BATCH,
            targetTestCandidateIds = targetTestCandidateIds,
            experimentType = null,
            requiredCapability = null,
            unresolvedReason = null,
            unresolvedDetail = null,
        )

        fun experiment(experimentType: ExperimentType, requiredCapability: String): ExecutionBinding =
            ExecutionBinding(
                kind = ExecutionBindingKind.EXPERIMENT,
                targetTestCandidateIds = emptyList(),
                experimentType = experimentType,
                requiredCapability = requiredCapability,
                unresolvedReason = null,
                unresolvedDetail = null,
            )

        fun unbound(reason: CandidateUnresolvedReason, detail: String): ExecutionBinding = ExecutionBinding(
            kind = ExecutionBindingKind.UNBOUND,
            targetTestCandidateIds = emptyList(),
            experimentType = null,
            requiredCapability = null,
            unresolvedReason = reason,
            unresolvedDetail = detail,
        )
    }
}

/**
 * Whether a candidate can run right now.
 *
 * Computed from [ExecutionBinding] and the currently active Profile every time it is read, so registering an operation
 * or enabling a capability changes the answer without rewriting stored candidates.
 */
enum class TestCandidateReadiness {
    EXECUTABLE,
    CAPABILITY_UNAVAILABLE,
    NEEDS_USER_INPUT,
    UNSUPPORTED,
}

/**
 * One recommended test.
 *
 * [confidence] is STATED when the candidate rests on registered configuration or facts the user declared, and
 * ASSUMPTION when it rests on knowledge ARL inferred from prose or naming.
 */
data class TestCandidate(
    val id: UUID,
    val generationId: UUID,
    val sequenceNumber: Int,
    val category: TestCandidateCategory,
    val title: String,
    val description: String,
    val risk: TestCandidateRisk,
    val confidence: KnowledgeConfidence,
    val verifiedExpectation: String,
    val preconditions: List<String>,
    val binding: ExecutionBinding,
    val citations: List<KnowledgeCitation>,
    val requiredEvidence: List<String>,
    val dataLifecyclePlan: String?,
)

/** Whether a candidate set came from Snapshot rules or from a test the user asked for directly. */
enum class TestCandidateGenerationSource {
    SNAPSHOT_RULES,
    DIRECT_REQUEST,
}

/**
 * An immutable set of candidates produced from one Knowledge Snapshot under one Profile Version.
 *
 * [checksum] makes repeated generation idempotent, and [generatorVersion] participates in it so improving the rules
 * yields a new set instead of returning the previous one.
 */
data class TestCandidateGeneration(
    val id: UUID,
    val targetSystemId: String,
    val knowledgeSnapshotId: UUID,
    val profileVersionId: UUID,
    val source: TestCandidateGenerationSource,
    val generatorVersion: String,
    val checksum: String,
    val createdBy: String,
    val createdCorrelationId: String,
    val createdAt: Instant,
)
