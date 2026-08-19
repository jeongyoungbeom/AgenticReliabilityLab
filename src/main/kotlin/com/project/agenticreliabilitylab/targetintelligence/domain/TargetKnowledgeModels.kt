package com.project.agenticreliabilitylab.targetintelligence.domain

import java.time.Instant
import java.util.UUID

/** Kind of user-supplied document a Knowledge Snapshot was extracted from. */
enum class KnowledgeSourceType {
    OPENAPI,
    README,
    BRIEF,
}

/** How strongly a extracted statement is backed by the supplied documents. */
enum class KnowledgeConfidence {
    /** The user declared the fact in a structured field; ARL did not interpret prose. */
    STATED,

    /** ARL inferred the fact from prose or naming. It must be confirmed before it can guide execution. */
    ASSUMPTION,
}

/** Whether an extracted operation can change Target state. */
enum class OperationMutability {
    READ,
    WRITE,
    UNKNOWN,
}

/** Risk area that later Phases turn into concurrency, retry or consistency test candidates. */
enum class RiskSignalType {
    RETRY,
    ASYNC,
    CACHE,
    EVENT,
    SHARED_RESOURCE,
    IDEMPOTENCY_KEY,
    LOCKING,
    TRANSACTION,
}

/** Pointer back to the exact place in a supplied document that produced an extraction. */
data class KnowledgeCitation(
    val sourceType: KnowledgeSourceType,
    val location: String,
    val excerpt: String,
)

/** Type, size and hash of one supplied document. Raw document text is never persisted. */
data class KnowledgeSourceDocument(
    val type: KnowledgeSourceType,
    val byteCount: Int,
    val checksum: String,
)

data class ExtractedOperation(
    val method: String,
    val path: String,
    val operationId: String?,
    val summary: String?,
    val requestMediaTypes: Set<String>,
    val responseStatusCodes: Set<Int>,
    val mutability: OperationMutability,
    val citation: KnowledgeCitation,
)

data class ExtractedWorkflow(
    val title: String,
    val steps: List<String>,
    val confidence: KnowledgeConfidence,
    val citation: KnowledgeCitation,
)

/**
 * A concept ARL believes the Target handles.
 *
 * ASSUMPTION-level knowledge must never be used as an execution oracle before the user confirms it. That derived flag
 * is computed where it is displayed rather than stored, so it cannot drift from [confidence].
 */
data class DomainHypothesis(
    val concept: String,
    val description: String,
    val confidence: KnowledgeConfidence,
    val citations: List<KnowledgeCitation>,
)

data class ExtractedInvariant(
    val statement: String,
    val confidence: KnowledgeConfidence,
    val citations: List<KnowledgeCitation>,
)

data class RiskSignal(
    val type: RiskSignalType,
    val confidence: KnowledgeConfidence,
    val citation: KnowledgeCitation,
)

data class ExtractionWarning(
    val code: String,
    val message: String,
)

/** Immutable extraction result. Persisted as-is and never rewritten after creation. */
data class TargetKnowledgeContent(
    val sources: List<KnowledgeSourceDocument>,
    val operations: List<ExtractedOperation>,
    val workflows: List<ExtractedWorkflow>,
    val domainHypotheses: List<DomainHypothesis>,
    val invariants: List<ExtractedInvariant>,
    val riskSignals: List<RiskSignal>,
    val warnings: List<ExtractionWarning>,
)

/**
 * Immutable understanding of a Target built only from supplied documents.
 *
 * [extractionVersion] records which extractor produced [content], mirroring the `collector_version` provenance the
 * project already stores for Evidence. It also participates in [checksum], so improving the extractors and bumping the
 * version yields a new Snapshot for the same documents instead of freezing the first result forever.
 *
 * The snapshot is bound to the Profile Version that was active when it was created. Whether it may still drive new
 * planning is derived by comparing [profileVersionId] with the currently active Profile Version, so the binding never
 * goes stale in storage.
 */
data class TargetKnowledgeSnapshot(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val checksum: String,
    val extractionVersion: String,
    val content: TargetKnowledgeContent,
    val createdBy: String,
    val createdCorrelationId: String,
    val createdAt: Instant,
    val confirmedBy: String? = null,
    val confirmedCorrelationId: String? = null,
    val confirmedAt: Instant? = null,
) {
    val confirmed: Boolean = confirmedAt != null
}
