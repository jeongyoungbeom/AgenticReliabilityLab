package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.testcatalog.domain.CandidateUnresolvedReason
import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBinding
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateCategory
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateRisk
import org.springframework.stereotype.Component

/**
 * Turns a test the user asked for into a candidate draft.
 *
 * A direct request never gets its own execution path. It is resolved against the same registered read-only candidates
 * and the same Experiment Catalog as the rule-based generator, and whatever cannot be bound is returned with the
 * missing precondition named, so the user learns what to supply instead of receiving a test ARL cannot run.
 */
@Component
class DirectTestCandidateResolver {
    fun resolve(command: RequestTestCandidate, capabilities: TargetCapabilitySnapshot): TestCandidateDraft {
        val registeredId = command.targetOperationPath?.let(capabilities::candidateIdForPath)
        return TestCandidateDraft(
            category = command.category,
            title = command.title,
            description = command.description,
            risk = risk(command.category),
            confidence = KnowledgeConfidence.STATED,
            verifiedExpectation = command.invariantStatement?.takeIf(String::isNotBlank)
                ?: "확인할 불변식을 사용자가 아직 지정하지 않았습니다.",
            preconditions = preconditions(command),
            binding = binding(command, registeredId),
            citations = emptyList(),
            requiredEvidence = listOf("invariant result"),
            dataLifecyclePlan = null,
        )
    }

    private fun binding(command: RequestTestCandidate, registeredId: String?): ExecutionBinding = when {
        registeredId != null && command.category == TestCandidateCategory.AVAILABILITY ->
            ExecutionBinding.readOnlyBatch(listOf(registeredId))

        command.category == TestCandidateCategory.CONCURRENCY && command.mentionsStock() ->
            ExecutionBinding.experiment(ExperimentType.STOCK_CONCURRENCY, STOCK_CAPABILITY)

        command.invariantStatement.isNullOrBlank() -> ExecutionBinding.unbound(
            reason = CandidateUnresolvedReason.MISSING_INVARIANT,
            detail = "검증할 불변식이 지정되지 않아 실행 단위를 확정할 수 없습니다.",
        )

        else -> ExecutionBinding.unbound(
            reason = unresolvedReason(command.category),
            detail = detail(command.category),
        )
    }

    private fun unresolvedReason(category: TestCandidateCategory): CandidateUnresolvedReason = when (category) {
        TestCandidateCategory.AVAILABILITY, TestCandidateCategory.CONTRACT_INPUT ->
            CandidateUnresolvedReason.NO_SAFE_EXECUTION_PATH

        TestCandidateCategory.WORKFLOW -> CandidateUnresolvedReason.MISSING_TEST_DATA_LIFECYCLE
        TestCandidateCategory.CONSISTENCY -> CandidateUnresolvedReason.MISSING_OBSERVATION_CAPABILITY
        TestCandidateCategory.CONCURRENCY -> CandidateUnresolvedReason.MISSING_TEST_DATA_LIFECYCLE
        TestCandidateCategory.IDEMPOTENCY, TestCandidateCategory.RETRY_RECOVERY ->
            CandidateUnresolvedReason.UNSUPPORTED_TEST_TYPE
    }

    private fun detail(category: TestCandidateCategory): String = when (category) {
        TestCandidateCategory.AVAILABILITY ->
            "요청한 path가 활성 Profile의 읽기 전용 operation으로 등록되어 있지 않습니다."

        TestCandidateCategory.CONTRACT_INPUT ->
            "읽기 전용 Batch는 GET만 실행하므로 상태 변경 method를 실행할 경로가 없습니다."

        TestCandidateCategory.WORKFLOW, TestCandidateCategory.CONCURRENCY ->
            "테스트 데이터 생성·관측·정리 capability가 선언되어 있지 않습니다."

        TestCandidateCategory.CONSISTENCY ->
            "최종 상태를 관측할 capability가 선언되어 있지 않습니다."

        TestCandidateCategory.IDEMPOTENCY, TestCandidateCategory.RETRY_RECOVERY ->
            "이 검증을 표현하는 ExperimentType이 아직 Catalog에 없습니다."
    }

    private fun preconditions(command: RequestTestCandidate): List<String> = buildList {
        add("Target이 LOCAL 또는 TEST 환경이어야 합니다.")
        if (command.invariantStatement.isNullOrBlank()) add("검증할 불변식을 지정해야 합니다.")
        if (command.targetOperationPath.isNullOrBlank()) add("대상 operation을 지정하면 더 정확히 바인딩할 수 있습니다.")
    }

    private fun risk(category: TestCandidateCategory): TestCandidateRisk =
        if (category == TestCandidateCategory.AVAILABILITY) TestCandidateRisk.SAFE else TestCandidateRisk.MODERATE

    private fun RequestTestCandidate.mentionsStock(): Boolean {
        val text = "$title $description ${invariantStatement.orEmpty()}".lowercase()
        return STOCK_TERMS.any(text::contains)
    }

    private companion object {
        const val STOCK_CAPABILITY = "stock-concurrency"
        val STOCK_TERMS = listOf("재고", "stock", "inventory")
    }
}
