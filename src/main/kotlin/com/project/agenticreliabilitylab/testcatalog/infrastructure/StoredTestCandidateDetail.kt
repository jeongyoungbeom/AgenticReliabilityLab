package com.project.agenticreliabilitylab.testcatalog.infrastructure

import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeCitation
import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBinding

/**
 * JSON shape of the candidate fields that are not queried by column.
 *
 * The binding stored here is authoritative; the `binding_kind` column is a denormalized copy written from the same
 * value so the database can constrain and filter on it.
 */
internal data class StoredTestCandidateDetail(
    val verifiedExpectation: String,
    val preconditions: List<String>,
    val binding: ExecutionBinding,
    val citations: List<KnowledgeCitation>,
    val requiredEvidence: List<String>,
    val dataLifecyclePlan: String?,
)
