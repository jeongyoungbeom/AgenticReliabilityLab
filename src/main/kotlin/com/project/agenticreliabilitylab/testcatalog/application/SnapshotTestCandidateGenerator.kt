package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.targetintelligence.domain.ExtractedOperation
import com.project.agenticreliabilitylab.targetintelligence.domain.KnowledgeConfidence
import com.project.agenticreliabilitylab.targetintelligence.domain.OperationMutability
import com.project.agenticreliabilitylab.targetintelligence.domain.RiskSignalType
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeContent
import com.project.agenticreliabilitylab.testcatalog.domain.CandidateUnresolvedReason
import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBinding
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateCategory
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateRisk
import org.springframework.stereotype.Component

/**
 * Turns one Knowledge Snapshot into recommended tests using fixed rules.
 *
 * The generator only ever points at execution units that already exist: read-only candidates registered in the active
 * Profile, or an [ExperimentType] already in the Catalog. Everything else is proposed as unbound with the reason that
 * blocks it, because a recommendation the user cannot safely run is still worth seeing, while inventing an execution
 * path would not be.
 */
@Component
class SnapshotTestCandidateGenerator {
    fun generate(content: TargetKnowledgeContent, capabilities: TargetCapabilitySnapshot): List<TestCandidateDraft> =
        interleave(
            listOf(
                availabilityCandidates(content, capabilities),
                contractInputCandidates(content),
                concurrencyCandidates(content),
                idempotencyCandidates(content),
                retryRecoveryCandidates(content),
                consistencyCandidates(content),
                workflowCandidates(content),
            ),
        )

    /**
     * Takes candidates one per category per round rather than concatenating the categories.
     *
     * Straight concatenation means an document with many read operations could push concurrency or consistency out of
     * the set entirely once the cap is reached, and the user would never learn those areas existed. Round-robin keeps
     * every category represented and trims evenly.
     */
    private fun interleave(groups: List<List<TestCandidateDraft>>): List<TestCandidateDraft> {
        val selected = mutableListOf<TestCandidateDraft>()
        var index = 0
        while (selected.size < MAX_TEST_CANDIDATES) {
            val round = groups.mapNotNull { group -> group.getOrNull(index) }
            if (round.isEmpty()) break
            round.take(MAX_TEST_CANDIDATES - selected.size).forEach(selected::add)
            index++
        }
        return selected
    }

    private fun availabilityCandidates(
        content: TargetKnowledgeContent,
        capabilities: TargetCapabilitySnapshot,
    ): List<TestCandidateDraft> = content.operations
        .filter { operation -> operation.mutability == OperationMutability.READ }
        .take(MAX_PER_RULE)
        .map { operation -> availabilityCandidate(operation, capabilities.candidateIdForPath(operation.path)) }

    private fun availabilityCandidate(operation: ExtractedOperation, registeredId: String?): TestCandidateDraft =
        TestCandidateDraft(
            category = TestCandidateCategory.AVAILABILITY,
            title = "${operation.method} ${operation.path} 가용성·상태 코드 점검",
            description = "문서에 선언된 읽기 operation이 기대한 상태 코드로 응답하는지 확인합니다.",
            risk = TestCandidateRisk.SAFE,
            confidence = KnowledgeConfidence.ASSUMPTION,
            verifiedExpectation = "허용된 상태 코드와 응답 지연을 확인한다.",
            preconditions = listOf("Target이 LOCAL 또는 TEST 환경이고 읽기 전용 실행이 허용되어야 합니다."),
            binding = registeredId
                ?.let { id -> ExecutionBinding.readOnlyBatch(listOf(id)) }
                ?: ExecutionBinding.unbound(
                    reason = CandidateUnresolvedReason.NO_SAFE_EXECUTION_PATH,
                    detail = "이 path가 활성 Profile의 읽기 전용 operation으로 등록되어 있지 않습니다.",
                ),
            citations = listOf(operation.citation),
            requiredEvidence = listOf("HTTP status", "latency", "response byte count"),
            dataLifecyclePlan = null,
        )

    /** A safe Batch executes GET only, so an error-contract test on a state-changing call has no execution path yet. */
    private fun contractInputCandidates(content: TargetKnowledgeContent): List<TestCandidateDraft> = content.operations
        .filter { operation -> operation.mutability == OperationMutability.WRITE }
        .take(MAX_PER_RULE)
        .map { operation ->
            TestCandidateDraft(
                category = TestCandidateCategory.CONTRACT_INPUT,
                title = "${operation.method} ${operation.path} 잘못된 요청의 오류 계약 확인",
                description = "경계값과 잘못된 입력에 대해 선언된 오류 응답이 지켜지는지 확인합니다.",
                risk = TestCandidateRisk.MODERATE,
                confidence = KnowledgeConfidence.ASSUMPTION,
                verifiedExpectation = "잘못된 요청이 선언된 오류 상태 코드로 거절된다.",
                preconditions = listOf("상태를 바꾸지 않는 안전한 실행 경로 또는 격리된 테스트 데이터가 필요합니다."),
                binding = ExecutionBinding.unbound(
                    reason = CandidateUnresolvedReason.NO_SAFE_EXECUTION_PATH,
                    detail = "읽기 전용 Batch는 GET만 실행하므로 상태 변경 method를 실행할 경로가 없습니다.",
                ),
                citations = listOf(operation.citation),
                requiredEvidence = listOf("HTTP status", "error contract"),
                dataLifecyclePlan = "격리된 테스트 데이터 생성·정리 방법이 필요합니다.",
            )
        }

    private fun concurrencyCandidates(content: TargetKnowledgeContent): List<TestCandidateDraft> {
        val stockEvidence = content.domainHypotheses.filter { it.concept.equals(STOCK_CONCEPT, ignoreCase = true) }
        val stockInvariants = content.invariants.filter { invariant ->
            STOCK_TERMS.any { term -> invariant.statement.contains(term, ignoreCase = true) }
        }
        if (stockEvidence.isEmpty() && stockInvariants.isEmpty()) return emptyList()
        val stated = stockInvariants.any { it.confidence == KnowledgeConfidence.STATED }
        return listOf(
            TestCandidateDraft(
                category = TestCandidateCategory.CONCURRENCY,
                title = "재고 동시 차감 동시성 테스트",
                description = "같은 재고에 병렬 요청을 보내 초과 판매와 최종 재고 정합성을 확인합니다.",
                risk = TestCandidateRisk.MODERATE,
                confidence = if (stated) KnowledgeConfidence.STATED else KnowledgeConfidence.ASSUMPTION,
                verifiedExpectation = "확정된 총 판매 수량이 초기 재고를 넘지 않고 최종 재고가 음수가 되지 않는다.",
                preconditions = listOf(
                    "격리된 테스트 상품과 초기 재고가 필요합니다.",
                    "Target이 LOCAL 또는 TEST 환경이어야 합니다.",
                ),
                binding = ExecutionBinding.experiment(ExperimentType.STOCK_CONCURRENCY, STOCK_CAPABILITY),
                citations = (stockInvariants.flatMap { it.citations } + stockEvidence.flatMap { it.citations })
                    .take(MAX_CITATIONS),
                requiredEvidence = listOf("invariant result", "success/failure count", "final stock", "cleanup status"),
                dataLifecyclePlan = "기존 STOCK_CONCURRENCY Experiment의 fixture와 cleanup 검증 계약을 따릅니다.",
            ),
        )
    }

    private fun idempotencyCandidates(content: TargetKnowledgeContent): List<TestCandidateDraft> =
        unsupportedSignalCandidate(
            content = content,
            signalTypes = setOf(RiskSignalType.IDEMPOTENCY_KEY),
            category = TestCandidateCategory.IDEMPOTENCY,
            title = "동일 멱등성 키 반복 요청 테스트",
            description = "같은 멱등성 키로 반복 요청했을 때 결과가 한 번만 확정되는지 확인합니다.",
            expectation = "동일 멱등성 키의 요청은 최대 한 번만 확정된다.",
        )

    private fun retryRecoveryCandidates(content: TargetKnowledgeContent): List<TestCandidateDraft> =
        unsupportedSignalCandidate(
            content = content,
            signalTypes = setOf(RiskSignalType.RETRY, RiskSignalType.ASYNC),
            category = TestCandidateCategory.RETRY_RECOVERY,
            title = "timeout 후 재시도와 중복 처리 테스트",
            description = "timeout 뒤 재시도나 중복 전달이 발생해도 결과가 중복 확정되지 않는지 확인합니다.",
            expectation = "재시도와 중복 전달 이후에도 최종 상태가 한 번만 반영된다.",
        )

    private fun consistencyCandidates(content: TargetKnowledgeContent): List<TestCandidateDraft> {
        val signals = content.riskSignals.filter { signal -> signal.type in CONSISTENCY_SIGNALS }
        if (signals.isEmpty()) return emptyList()
        return listOf(
            TestCandidateDraft(
                category = TestCandidateCategory.CONSISTENCY,
                title = "API 상태와 이벤트·read model 정합성 확인",
                description = "비동기 반영 이후 API 상태와 파생 데이터가 모순되지 않는지 확인합니다.",
                risk = TestCandidateRisk.MODERATE,
                confidence = KnowledgeConfidence.ASSUMPTION,
                verifiedExpectation = "수렴 시간 안에 API 상태와 파생 데이터가 일치한다.",
                preconditions = listOf("최종 상태를 관측할 수 있는 capability와 명시된 불변식이 필요합니다."),
                binding = ExecutionBinding.unbound(
                    reason = CandidateUnresolvedReason.MISSING_OBSERVATION_CAPABILITY,
                    detail = "최종 상태와 파생 데이터를 관측할 방법이 Profile에 선언되어 있지 않습니다.",
                ),
                citations = signals.take(MAX_CITATIONS).map { signal -> signal.citation },
                requiredEvidence = listOf("state snapshot", "settling condition"),
                dataLifecyclePlan = "관측 대상과 수렴 조건을 사용자가 확인해야 합니다.",
            ),
        )
    }

    private fun workflowCandidates(content: TargetKnowledgeContent): List<TestCandidateDraft> = content.workflows
        .take(MAX_PER_RULE)
        .map { workflow ->
            TestCandidateDraft(
                category = TestCandidateCategory.WORKFLOW,
                title = "workflow 검증: ${workflow.title}",
                description = "선언된 흐름을 순서대로 수행한 뒤 최종 상태가 기대와 일치하는지 확인합니다.",
                risk = TestCandidateRisk.MODERATE,
                confidence = workflow.confidence,
                verifiedExpectation = "각 단계 이후 상태가 선언된 흐름과 일치한다.",
                preconditions = listOf("테스트 데이터 생성·관측·정리 capability가 필요합니다."),
                binding = ExecutionBinding.unbound(
                    reason = CandidateUnresolvedReason.MISSING_TEST_DATA_LIFECYCLE,
                    detail = "workflow 실행에 필요한 테스트 데이터 lifecycle capability가 없습니다.",
                ),
                citations = listOf(workflow.citation),
                requiredEvidence = listOf("step status", "final state"),
                dataLifecyclePlan = "테스트 데이터 생성과 정리 방법이 필요합니다.",
            )
        }

    private fun unsupportedSignalCandidate(
        content: TargetKnowledgeContent,
        signalTypes: Set<RiskSignalType>,
        category: TestCandidateCategory,
        title: String,
        description: String,
        expectation: String,
    ): List<TestCandidateDraft> {
        val signals = content.riskSignals.filter { signal -> signal.type in signalTypes }
        if (signals.isEmpty()) return emptyList()
        return listOf(
            TestCandidateDraft(
                category = category,
                title = title,
                description = description,
                risk = TestCandidateRisk.MODERATE,
                confidence = KnowledgeConfidence.ASSUMPTION,
                verifiedExpectation = expectation,
                preconditions = listOf("해당 불변식을 검증할 Experiment 정의가 Catalog에 필요합니다."),
                binding = ExecutionBinding.unbound(
                    reason = CandidateUnresolvedReason.UNSUPPORTED_TEST_TYPE,
                    detail = "이 검증을 표현하는 ExperimentType이 아직 Catalog에 없습니다.",
                ),
                citations = signals.take(MAX_CITATIONS).map { signal -> signal.citation },
                requiredEvidence = listOf("invariant result"),
                dataLifecyclePlan = null,
            ),
        )
    }

    private companion object {
        const val MAX_PER_RULE = 10
        const val MAX_CITATIONS = 5
        const val STOCK_CONCEPT = "stock"
        const val STOCK_CAPABILITY = "stock-concurrency"
        val STOCK_TERMS = listOf("재고", "stock", "inventory")
        val CONSISTENCY_SIGNALS = setOf(
            RiskSignalType.EVENT,
            RiskSignalType.CACHE,
            RiskSignalType.TRANSACTION,
        )
    }
}
