package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidate
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateGeneration
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateReadiness

/** A stored candidate plus the readiness derived from current Target capability. */
data class TestCandidateView(
    val candidate: TestCandidate,
    val readiness: TestCandidateReadiness,
)

/**
 * A stored candidate set plus what has to be recomputed at read time.
 *
 * [profileVersionActive] reports whether the set still describes the Profile Version in force. Bindings were resolved
 * against that version, so a superseded set has to be regenerated rather than silently reused.
 */
data class TestCandidateGenerationView(
    val generation: TestCandidateGeneration,
    val profileVersionActive: Boolean,
    val candidates: List<TestCandidateView>,
)

/**
 * List projection that deliberately omits candidates.
 *
 * Embedding every candidate of every generation turned the list endpoint into one query per row; the detail endpoint
 * carries the candidates instead.
 */
data class TestCandidateGenerationSummaryView(
    val generation: TestCandidateGeneration,
    val profileVersionActive: Boolean,
)
