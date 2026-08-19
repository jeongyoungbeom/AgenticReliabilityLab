package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.testcatalog.domain.CandidateUnresolvedReason
import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBinding
import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBindingKind
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateReadiness
import org.springframework.stereotype.Component

/**
 * Derives whether a candidate can run right now.
 *
 * Readiness is never stored. A Profile activation, a newly registered operation or a disabled capability changes the
 * answer immediately, which is why it is recomputed from the stored binding on every read, approval and dispatch.
 */
@Component
class TestCandidateReadinessResolver {
    fun resolve(binding: ExecutionBinding, capabilities: TargetCapabilitySnapshot): TestCandidateReadiness =
        when (binding.kind) {
            ExecutionBindingKind.READ_ONLY_BATCH -> readOnlyReadiness(binding, capabilities)
            ExecutionBindingKind.EXPERIMENT -> experimentReadiness(binding, capabilities)
            ExecutionBindingKind.UNBOUND -> unboundReadiness(binding)
        }

    private fun readOnlyReadiness(
        binding: ExecutionBinding,
        capabilities: TargetCapabilitySnapshot,
    ): TestCandidateReadiness {
        val allRegistered = binding.targetTestCandidateIds.isNotEmpty() &&
            capabilities.registeredReadOnlyCandidateIds.containsAll(binding.targetTestCandidateIds)
        return if (capabilities.genericExecutionEnabled && allRegistered) {
            TestCandidateReadiness.EXECUTABLE
        } else {
            TestCandidateReadiness.CAPABILITY_UNAVAILABLE
        }
    }

    private fun experimentReadiness(
        binding: ExecutionBinding,
        capabilities: TargetCapabilitySnapshot,
    ): TestCandidateReadiness =
        if (binding.experimentType != null && binding.experimentType in capabilities.availableExperimentTypes) {
            TestCandidateReadiness.EXECUTABLE
        } else {
            TestCandidateReadiness.CAPABILITY_UNAVAILABLE
        }

    /** Only a test type the Catalog cannot express at all is UNSUPPORTED; the rest wait on user input. */
    private fun unboundReadiness(binding: ExecutionBinding): TestCandidateReadiness =
        if (binding.unresolvedReason == CandidateUnresolvedReason.UNSUPPORTED_TEST_TYPE) {
            TestCandidateReadiness.UNSUPPORTED
        } else {
            TestCandidateReadiness.NEEDS_USER_INPUT
        }
}
