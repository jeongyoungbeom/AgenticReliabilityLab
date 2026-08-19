package com.project.agenticreliabilitylab.testplan.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.common.ResourceNotFoundException
import com.project.agenticreliabilitylab.targetintelligence.application.sha256Hex
import com.project.agenticreliabilitylab.targetspec.application.CreateTargetTestBatch
import com.project.agenticreliabilitylab.targetspec.application.TargetTestBatchService
import com.project.agenticreliabilitylab.targetspec.application.TargetTestBatchTargetPolicy
import com.project.agenticreliabilitylab.testcatalog.application.TargetCapabilityResolver
import com.project.agenticreliabilitylab.testcatalog.application.TargetCapabilitySnapshot
import com.project.agenticreliabilitylab.testcatalog.application.TestCandidateReadinessResolver
import com.project.agenticreliabilitylab.testcatalog.application.port.TestCandidateStore
import com.project.agenticreliabilitylab.testcatalog.domain.ExecutionBindingKind
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidate
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateGeneration
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateReadiness
import com.project.agenticreliabilitylab.testplan.application.port.TestPlanStore
import com.project.agenticreliabilitylab.testplan.domain.TestPlan
import com.project.agenticreliabilitylab.testplan.domain.TestPlanConfirmation
import com.project.agenticreliabilitylab.testplan.domain.TestPlanExecutionKind
import com.project.agenticreliabilitylab.testplan.domain.TestPlanExecutionReference
import com.project.agenticreliabilitylab.testplan.domain.TestPlanItem
import com.project.agenticreliabilitylab.testplan.domain.TestPlanStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Records which recommended tests a user selected and approved, and hands the work to the existing execution engine.
 *
 * The plan owns selection, approval and audit only. Dispatch creates and approves the existing Target Test Batch inside
 * one transaction, so either the execution reference and its outbox job both exist or neither does; from that point the
 * Batch is the single source of truth for what ran.
 */
@Service
@Suppress("TooManyFunctions") // Selection, approval, dispatch and their guards belong to one plan lifecycle.
class TestPlanService(
    private val store: TestPlanStore,
    private val candidateStore: TestCandidateStore,
    private val capabilityResolver: TargetCapabilityResolver,
    private val readinessResolver: TestCandidateReadinessResolver,
    private val batchService: TargetTestBatchService,
    private val batchTargetPolicy: TargetTestBatchTargetPolicy,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
) {
    @Transactional
    @Suppress("ReturnCount") // Idempotency recovery has explicit early exits by design.
    fun create(
        command: CreateTestPlan,
        idempotencyKey: String,
        actor: String,
        correlationId: String,
    ): TestPlanView {
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            "Idempotency-Key must contain 1 to 200 letters, numbers, '.', '_', ':' or '-'"
        }
        val generation = candidateStore.findGeneration(command.generationId)
            ?: throw ResourceNotFoundException("TestCandidateGeneration", command.generationId)
        val capabilities = capabilityResolver.resolve(generation.targetSystemId)
        requireCurrentProfileVersion(generation, capabilities)
        val selected = selectCandidates(command, capabilities)
        requireBatchCapacity(generation.targetSystemId, selected)
        val requestHash = requestHash(command)
        store.findByTargetAndIdempotencyKey(generation.targetSystemId, idempotencyKey)?.let { existing ->
            ensureSameRequest(existing, requestHash)
            return view(existing)
        }
        val plan = TestPlan(
            id = identifiers.next(),
            targetSystemId = generation.targetSystemId,
            knowledgeSnapshotId = generation.knowledgeSnapshotId,
            generationId = generation.id,
            profileVersionId = capabilities.profileVersionId,
            status = TestPlanStatus.PENDING_APPROVAL,
            requiredConfirmation = TestPlanConfirmation.forRisks(selected.map(TestCandidate::risk)),
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            createdBy = actor,
            createdCorrelationId = correlationId,
            createdAt = clock.instant(),
        )
        try {
            store.create(plan, items(plan.id, selected))
        } catch (exception: DuplicateKeyException) {
            val existing = store.findByTargetAndIdempotencyKey(generation.targetSystemId, idempotencyKey)
                ?: throw exception
            ensureSameRequest(existing, requestHash)
            return view(existing)
        }
        return view(plan)
    }

    @Transactional
    @Suppress("ReturnCount") // Already-approved and superseded plans each have a deliberate early exit.
    fun approve(planId: UUID, confirmation: String, actor: String, correlationId: String): TestPlanView {
        val plan = requirePlan(planId)
        if (plan.status != TestPlanStatus.PENDING_APPROVAL) return view(plan)
        require(confirmation == plan.requiredConfirmation.phrase) {
            "confirmation must equal ${plan.requiredConfirmation.phrase}"
        }
        supersedeIfProfileChanged(plan)?.let { superseded -> return superseded }
        store.approve(planId, actor, correlationId, clock.instant())
        return view(requirePlan(planId))
    }

    /**
     * Hands an approved plan to the existing execution engine.
     *
     * The APPROVED -> DISPATCHED transition is claimed before any work is handed over, and the claim is what makes
     * concurrent dispatches safe: it is a conditional update, so a second caller blocks on the same row, then finds
     * the plan already dispatched and returns the existing references instead of handing the Target a second Batch.
     * The claim lives in this transaction, so a dispatch that fails afterwards rolls the claim back with it.
     */
    @Transactional
    @Suppress("ReturnCount") // Already-dispatched and superseded plans each have a deliberate early exit.
    fun dispatch(planId: UUID, actor: String, correlationId: String): TestPlanView {
        val plan = requirePlan(planId)
        if (plan.status == TestPlanStatus.DISPATCHED) return view(plan)
        if (plan.status != TestPlanStatus.APPROVED) {
            throw ClientRequestException("TEST_PLAN_NOT_APPROVED", "Test plan '$planId' is not approved")
        }
        supersedeIfProfileChanged(plan)?.let { superseded -> return superseded }
        val candidateIds = store.findItems(planId).flatMap(TestPlanItem::targetTestCandidateIds).distinct()
        require(candidateIds.isNotEmpty()) { "Test plan '$planId' has no read-only candidate to execute" }
        if (!store.markDispatched(planId, clock.instant())) return view(requirePlan(planId))
        val batch = batchService.create(
            CreateTargetTestBatch(plan.targetSystemId, candidateIds),
            "test-plan-${plan.id}",
        )
        batchService.approve(batch.id, actor, correlationId)
        store.appendExecutionReference(
            TestPlanExecutionReference(
                id = identifiers.next(),
                planId = plan.id,
                kind = TestPlanExecutionKind.TARGET_TEST_BATCH,
                referenceId = batch.id,
                createdAt = clock.instant(),
            ),
        )
        return view(requirePlan(planId))
    }

    fun find(planId: UUID): TestPlanView = view(requirePlan(planId))

    fun findByTarget(targetSystemId: String): List<TestPlanSummaryView> {
        val activeProfileVersionId = capabilityResolver.find(targetSystemId)?.profileVersionId
        return store.findByTarget(targetSystemId, MAX_LISTED_PLANS).map { plan ->
            TestPlanSummaryView(plan, activeProfileVersionId == plan.profileVersionId)
        }
    }

    private fun selectCandidates(
        command: CreateTestPlan,
        capabilities: TargetCapabilitySnapshot,
    ): List<TestCandidate> {
        require(command.candidateIds.isNotEmpty() && command.candidateIds.size <= MAX_PLAN_ITEMS) {
            "candidateIds must contain between 1 and $MAX_PLAN_ITEMS items"
        }
        require(command.candidateIds.distinct().size == command.candidateIds.size) {
            "candidateIds must not contain duplicates"
        }
        val byId = candidateStore.findCandidates(command.generationId).associateBy(TestCandidate::id)
        return command.candidateIds.map { candidateId ->
            val candidate = byId[candidateId] ?: throw ClientRequestException(
                "UNKNOWN_TEST_CANDIDATE",
                "Candidate '$candidateId' does not belong to generation '${command.generationId}'",
            )
            requireExecutableReadOnly(candidate, capabilities)
            candidate
        }
    }

    /**
     * Rejects a selection the execution engine could not accept, before approval is requested.
     *
     * The Batch bound is per Profile and counts resolved read-only checks, not selected candidates. Checking it only at
     * dispatch would leave an explicitly approved plan permanently unable to run.
     */
    private fun requireBatchCapacity(targetSystemId: String, selected: List<TestCandidate>) {
        val executionCandidateIds = selected
            .flatMap { candidate -> candidate.binding.targetTestCandidateIds }
            .distinct()
        val maxBatchSize = batchTargetPolicy.maxBatchSize(targetSystemId)
        require(executionCandidateIds.size <= maxBatchSize) {
            "Selection resolves to ${executionCandidateIds.size} read-only checks but the Target allows $maxBatchSize"
        }
    }

    /** Phase 13 executes read-only checks only; a state-changing binding waits for the Test Harness contract. */
    private fun requireExecutableReadOnly(candidate: TestCandidate, capabilities: TargetCapabilitySnapshot) {
        if (candidate.binding.kind != ExecutionBindingKind.READ_ONLY_BATCH) {
            throw ClientRequestException(
                "EXECUTION_BINDING_NOT_SUPPORTED",
                "Candidate '${candidate.id}' needs a state-changing execution path that is not available yet",
            )
        }
        val readiness = readinessResolver.resolve(candidate.binding, capabilities)
        if (readiness != TestCandidateReadiness.EXECUTABLE) {
            throw ClientRequestException(
                "TEST_CANDIDATE_NOT_EXECUTABLE",
                "Candidate '${candidate.id}' is currently $readiness",
            )
        }
    }

    private fun items(planId: UUID, selected: List<TestCandidate>): List<TestPlanItem> =
        selected.mapIndexed { index, candidate ->
            TestPlanItem(
                id = identifiers.next(),
                planId = planId,
                sequenceNumber = index + 1,
                candidateId = candidate.id,
                category = candidate.category,
                risk = candidate.risk,
                bindingKind = candidate.binding.kind,
                targetTestCandidateIds = candidate.binding.targetTestCandidateIds,
            )
        }

    /** An approved plan that has not dispatched yet is still invalidated by a Profile change. */
    private fun supersedeIfProfileChanged(plan: TestPlan): TestPlanView? {
        val activeVersionId = capabilityResolver.find(plan.targetSystemId)?.profileVersionId
        if (activeVersionId == plan.profileVersionId) return null
        store.markTerminal(plan.id, TestPlanStatus.SUPERSEDED, PROFILE_VERSION_INACTIVE)
        return view(requirePlan(plan.id))
    }

    private fun requireCurrentProfileVersion(
        generation: TestCandidateGeneration,
        capabilities: TargetCapabilitySnapshot,
    ) {
        if (capabilities.profileVersionId != generation.profileVersionId) {
            throw ClientRequestException(
                "TEST_CANDIDATE_GENERATION_PROFILE_VERSION_INACTIVE",
                "Candidate generation '${generation.id}' is bound to an inactive Profile Version",
            )
        }
    }

    private fun ensureSameRequest(existing: TestPlan, requestHash: String) {
        if (existing.requestHash != requestHash) {
            throw ClientRequestException(
                "TEST_PLAN_IDEMPOTENCY_CONFLICT",
                "Idempotency-Key was reused with a different selection",
            )
        }
    }

    private fun requestHash(command: CreateTestPlan): String = sha256Hex(
        (listOf(command.generationId.toString()) + command.candidateIds.map(UUID::toString).sorted())
            .joinToString("|"),
    )

    private fun requirePlan(planId: UUID): TestPlan =
        store.findById(planId) ?: throw ResourceNotFoundException("TestPlan", planId)

    private fun view(plan: TestPlan): TestPlanView = TestPlanView(
        plan = plan,
        profileVersionActive = capabilityResolver.find(plan.targetSystemId)?.profileVersionId == plan.profileVersionId,
        items = store.findItems(plan.id),
        executionReferences = store.findExecutionReferences(plan.id),
    )

    private companion object {
        const val MAX_PLAN_ITEMS = 20
        const val MAX_LISTED_PLANS = 50
        const val PROFILE_VERSION_INACTIVE = "Target Profile Version is no longer active"
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,200}")
    }
}
