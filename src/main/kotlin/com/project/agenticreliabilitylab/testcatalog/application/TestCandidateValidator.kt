package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBindingKind
import org.springframework.stereotype.Component

/**
 * Deterministic gate every candidate passes before it is stored.
 *
 * The rule-based generator cannot violate these checks today, but the gate exists for the proposal path: a model may
 * suggest a candidate, and it must not be able to widen execution scope by inventing an endpoint, an execution unit or
 * a capability. Only identifiers the active Profile already registers and Experiment types already present in the
 * Catalog survive validation.
 */
@Component
class TestCandidateValidator {
    fun validate(drafts: List<TestCandidateDraft>, capabilities: TargetCapabilitySnapshot) {
        require(drafts.size <= MAX_TEST_CANDIDATES) {
            "A generation may contain at most $MAX_TEST_CANDIDATES candidates"
        }
        drafts.forEach { draft -> validate(draft, capabilities) }
    }

    private fun validate(draft: TestCandidateDraft, capabilities: TargetCapabilitySnapshot) {
        require(draft.title.isNotBlank()) { "Candidate title must not be blank" }
        require(draft.verifiedExpectation.isNotBlank()) { "Candidate must state what it verifies" }
        if (draft.confidence == KnowledgeConfidence.ASSUMPTION) {
            require(draft.citations.isNotEmpty()) {
                "Inferred candidate '${draft.title}' must cite the document position it was derived from"
            }
        }
        when (draft.binding.kind) {
            ExecutionBindingKind.READ_ONLY_BATCH -> validateReadOnly(draft, capabilities)
            ExecutionBindingKind.EXPERIMENT -> validateExperiment(draft)
            ExecutionBindingKind.UNBOUND -> validateUnbound(draft)
        }
    }

    private fun validateReadOnly(draft: TestCandidateDraft, capabilities: TargetCapabilitySnapshot) {
        val ids = draft.binding.targetTestCandidateIds
        require(ids.isNotEmpty()) { "Read-only candidate '${draft.title}' must reference a registered candidate" }
        val unknown = ids - capabilities.registeredReadOnlyCandidateIds
        require(unknown.isEmpty()) {
            "Candidate '${draft.title}' references unregistered read-only candidates: " +
                unknown.sorted().joinToString()
        }
    }

    /** The Experiment type is an enum, so a proposal cannot name an execution unit that does not exist. */
    private fun validateExperiment(draft: TestCandidateDraft) {
        requireNotNull(draft.binding.experimentType) { "Experiment candidate '${draft.title}' must name a type" }
        require(!draft.binding.requiredCapability.isNullOrBlank()) {
            "Experiment candidate '${draft.title}' must declare the capability it needs"
        }
    }

    private fun validateUnbound(draft: TestCandidateDraft) {
        requireNotNull(draft.binding.unresolvedReason) {
            "Unbound candidate '${draft.title}' must record why it cannot be bound"
        }
    }
}
