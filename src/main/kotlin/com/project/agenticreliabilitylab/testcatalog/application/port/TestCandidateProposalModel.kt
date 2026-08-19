package com.project.agenticreliabilitylab.testcatalog.application.port

import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeContent
import com.project.agenticreliabilitylab.testcatalog.application.TargetCapabilitySnapshot
import com.project.agenticreliabilitylab.testcatalog.application.TestCandidateDraft

/**
 * Optional model-backed source of additional candidate proposals.
 *
 * No implementation is registered yet: Phase 12 ships the deterministic generator and the validator first, because a
 * proposal is only safe once something independent of the model decides what may be stored. An implementation must
 * return drafts that still pass `TestCandidateValidator`, which means it can neither invent an endpoint nor name an
 * execution unit outside the Experiment Catalog. A model may propose and explain; it never widens execution scope.
 */
interface TestCandidateProposalModel {
    fun propose(content: TargetKnowledgeContent, capabilities: TargetCapabilitySnapshot): List<TestCandidateDraft>
}
