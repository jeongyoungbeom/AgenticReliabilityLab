package com.project.agenticreliabilitylab.testcatalog.application.port

import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidate
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateGeneration
import java.util.UUID

/**
 * Persistence boundary for generated candidates.
 *
 * A generation and its candidates are written once and never rewritten. Readiness is not stored, so nothing here has to
 * be updated when Target capability changes.
 */
interface TestCandidateStore {
    fun createIfAbsent(generation: TestCandidateGeneration, candidates: List<TestCandidate>): Boolean
    fun findGeneration(id: UUID): TestCandidateGeneration?
    fun findGenerationBySnapshotAndChecksum(knowledgeSnapshotId: UUID, checksum: String): TestCandidateGeneration?
    fun findGenerationsByTarget(targetSystemId: String, limit: Int): List<TestCandidateGeneration>
    fun findCandidates(generationId: UUID): List<TestCandidate>
}
