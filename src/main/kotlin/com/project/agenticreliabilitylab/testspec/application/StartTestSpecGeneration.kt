package com.project.agenticreliabilitylab.testspec.application

import java.util.UUID

/**
 * [openApiDocument] is accepted fresh on every call rather than read back from the Knowledge Snapshot, because the
 * Snapshot deliberately never stores raw document text or field-level schema. Supplying it here is what lets the
 * model ground request and response field names in real evidence instead of guessing them; omitting it still
 * produces a run, just one where most or all proposals are likely to be rejected for referencing fields nobody can
 * confirm exist.
 */
data class StartTestSpecGeneration(
    val targetSystemId: String,
    val knowledgeSnapshotId: UUID,
    val openApiDocument: String?,
    val requestedModelKey: String?,
)
