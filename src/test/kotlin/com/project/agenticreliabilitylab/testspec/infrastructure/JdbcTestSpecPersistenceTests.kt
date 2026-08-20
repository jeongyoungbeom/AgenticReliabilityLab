package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.targetprofile.infrastructure.JdbcTargetProfileRepository
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecRunStore
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecificationStore
import com.project.agenticreliabilitylab.testspec.domain.InvariantOutcome
import com.project.agenticreliabilitylab.testspec.domain.InvariantVerdict
import com.project.agenticreliabilitylab.testspec.domain.RecordedResponse
import com.project.agenticreliabilitylab.testspec.domain.ResetCheck
import com.project.agenticreliabilitylab.testspec.domain.ResetOutcome
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecRunOutcome
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.SpecificationResult
import com.project.agenticreliabilitylab.testspec.domain.StepTiming
import com.project.agenticreliabilitylab.testspec.domain.StoredTestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import com.project.agenticreliabilitylab.testspec.domain.TestSpecificationStatus
import com.project.agenticreliabilitylab.testspec.domain.TrialExecution
import com.project.agenticreliabilitylab.testspec.domain.TrialOutcome
import com.project.agenticreliabilitylab.testspec.domain.TrialResult
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Replays the Phase 17 persistence contract on H2 and, through the subclass, PostgreSQL. */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
open class JdbcTestSpecPersistenceTests {
    @Autowired
    private lateinit var specificationStore: TestSpecificationStore

    @Autowired
    private lateinit var runStore: TestSpecRunStore

    @Autowired
    private lateinit var profileRepository: JdbcTargetProfileRepository

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Test
    fun `keeps specification versions immutable and records one approval decision`() {
        val specification = pendingSpecification()
        val approvedAt = Instant.parse("2026-08-20T01:00:00Z")
        specificationStore.create(specification)

        assertTrue(specificationStore.approve(specification.id, "reviewer", "approve-correlation", approvedAt))
        assertFalse(specificationStore.approve(specification.id, "other-reviewer", "second-correlation", approvedAt))

        val approved = assertNotNull(specificationStore.findById(specification.id))
        assertEquals(TestSpecificationStatus.APPROVED, approved.status)
        assertEquals("reviewer", approved.approvedBy)
        assertEquals("approve-correlation", approved.approvedCorrelationId)
        assertEquals(approvedAt, approved.approvedAt)
        assertEquals(specification.documentJson, approved.documentJson)
        assertEquals(listOf(specification.id), specificationStore.findByTargetAndKey(TARGET_ID, specification.specKey)
            .map(StoredTestSpecification::id))

        assertTrue(specificationStore.supersede(specification.id, "Profile version changed"))
        val superseded = assertNotNull(specificationStore.findById(specification.id))
        assertEquals(TestSpecificationStatus.SUPERSEDED, superseded.status)
        assertEquals("Profile version changed", superseded.terminalReason)
        assertFalse(specificationStore.supersede(specification.id, "Cannot overwrite terminal reason"))
    }

    @Test
    fun `stores verdicts timings and resets without raw responses or bindings`() {
        val specification = createSpecification()
        val run = pendingRun(specification)
        val sensitiveValue = "must-not-be-stored-${UUID.randomUUID()}"
        runStore.create(run)

        assertEquals(run.id, runStore.findByTargetAndIdempotencyKey(TARGET_ID, run.idempotencyKey)?.id)
        assertTrue(runStore.markRunning(run.id, STARTED_AT))
        assertFalse(runStore.markRunning(run.id, STARTED_AT))
        assertTrue(runStore.complete(run.id, completedOutcome(run.id, true, sensitiveValue), COMPLETED_AT))
        assertFalse(runStore.complete(run.id, completedOutcome(run.id, true, sensitiveValue), COMPLETED_AT))

        val completed = assertNotNull(runStore.findById(run.id))
        assertEquals(TestSpecRunStatus.COMPLETED, completed.status)
        assertEquals(TrialOutcome.PASSED, completed.resultOutcome)
        assertEquals(1, completed.trialsRun)
        assertEquals(true, completed.cleanupVerified)

        val trial = runStore.findTrials(run.id).single()
        assertEquals(TrialOutcome.PASSED, trial.outcome)
        assertTrue(trial.stateChanged)
        assertEquals("stock_never_negative", trial.verdicts.single().invariantId)
        assertEquals("workload", trial.timings.single().name)

        val reset = runStore.findResets(run.id).single()
        assertTrue(reset.performed)
        assertTrue(reset.verified)
        assertEquals("inventory_restored", reset.checks.single().id)

        assertEquals(
            0L,
            sensitiveValuesStored("test_spec_trial_result", "verdicts_json", "timings_json", sensitiveValue),
        )
        assertEquals(0L, sensitiveValuesStored("test_spec_reset_result", "checks_json", null, sensitiveValue))
    }

    @Test
    fun `requires recovery when cleanup cannot be verified`() {
        val specification = createSpecification()
        val completedRun = pendingRun(specification)
        runStore.create(completedRun)
        assertTrue(runStore.markRunning(completedRun.id, STARTED_AT))
        assertTrue(
            runStore.complete(
                completedRun.id,
                completedOutcome(completedRun.id, false, "discarded"),
                COMPLETED_AT,
            ),
        )

        val recovery = assertNotNull(runStore.findById(completedRun.id))
        assertEquals(TestSpecRunStatus.RECOVERY_REQUIRED, recovery.status)
        assertEquals(false, recovery.cleanupVerified)
        assertFalse(runStore.findResets(completedRun.id).single().verified)
        assertTrue(runStore.hasBlockingRun(TARGET_ID))
    }

    @Test
    fun `keeps one active run slot until execution finishes safely`() {
        val specification = createSpecification()
        val first = pendingRun(specification)
        val second = pendingRun(specification)
        runStore.create(first)

        assertTrue(runStore.hasBlockingRun(TARGET_ID))
        assertFailsWith<org.springframework.dao.DuplicateKeyException> { runStore.create(second) }

        assertTrue(runStore.markFailed(first.id, false, "Target was not touched", COMPLETED_AT))
        assertFalse(runStore.hasBlockingRun(TARGET_ID))
        runStore.create(second)
    }

    @Test
    fun `requires recovery when a claimed run fails unexpectedly`() {
        val specification = createSpecification()
        val failedRun = pendingRun(specification)

        runStore.create(failedRun)
        assertTrue(runStore.markRunning(failedRun.id, STARTED_AT))
        assertTrue(runStore.markFailed(failedRun.id, true, "Reset endpoint timed out", COMPLETED_AT))
        val failed = assertNotNull(runStore.findById(failedRun.id))
        assertEquals(TestSpecRunStatus.RECOVERY_REQUIRED, failed.status)
        assertEquals("Reset endpoint timed out", failed.failure)
        assertFalse(runStore.markFailed(failedRun.id, false, "Cannot rewrite terminal state", COMPLETED_AT))
    }

    @Test
    fun `moves an orphaned running run to recovery required after restart`() {
        val run = pendingRun(createSpecification())
        runStore.create(run)
        assertTrue(runStore.markRunning(run.id, STARTED_AT))

        assertEquals(1, runStore.recoverIncompleteRuns(COMPLETED_AT))

        val recovered = assertNotNull(runStore.findById(run.id))
        assertEquals(TestSpecRunStatus.RECOVERY_REQUIRED, recovered.status)
        assertTrue(runStore.hasBlockingRun(TARGET_ID))
    }

    @Test
    fun `fails an orphaned pending run without leaving the target blocked`() {
        val run = pendingRun(createSpecification())
        runStore.create(run)

        assertEquals(1, runStore.recoverIncompleteRuns(COMPLETED_AT))

        val recovered = assertNotNull(runStore.findById(run.id))
        assertEquals(TestSpecRunStatus.FAILED, recovered.status)
        assertFalse(runStore.hasBlockingRun(TARGET_ID))
    }

    private fun createSpecification(): StoredTestSpecification = pendingSpecification().also(specificationStore::create)

    private fun pendingSpecification(): StoredTestSpecification {
        val id = UUID.randomUUID()
        return StoredTestSpecification(
            id = id,
            targetSystemId = TARGET_ID,
            specKey = "phase17-${UUID.randomUUID()}",
            version = 1,
            title = "Stock remains non-negative",
            profileVersionId = activeProfileId(),
            source = SpecSource.RULE_GENERATED,
            category = SpecCategory.CONCURRENCY,
            risk = SpecRisk.MODERATE,
            status = TestSpecificationStatus.PENDING_APPROVAL,
            documentJson = """{"id":"$id","invariants":["stock_never_negative"]}""",
            checksum = checksum(),
            createdBy = "phase17-test",
            createdCorrelationId = "create-${UUID.randomUUID()}",
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
        )
    }

    private fun pendingRun(specification: StoredTestSpecification): TestSpecRun = TestSpecRun(
        id = UUID.randomUUID(),
        specificationId = specification.id,
        targetSystemId = TARGET_ID,
        profileVersionId = specification.profileVersionId,
        status = TestSpecRunStatus.PENDING,
        idempotencyKey = "run-${UUID.randomUUID()}",
        requestHash = checksum(),
        requestedTrials = 1,
        createdBy = "phase17-test",
        createdCorrelationId = "run-correlation-${UUID.randomUUID()}",
        createdAt = Instant.parse("2026-08-20T00:30:00Z"),
    )

    private fun completedOutcome(
        runId: UUID,
        cleanupVerified: Boolean,
        sensitiveValue: String,
    ): SpecRunOutcome {
        val verdict = InvariantVerdict(
            invariantId = "stock_never_negative",
            description = "Stock never becomes negative",
            outcome = InvariantOutcome.PASSED,
            condition = "stock >= 0",
            observedValues = mapOf("stock" to "4"),
        )
        val trial = TrialResult(1, TrialOutcome.PASSED, listOf(verdict))
        val result = SpecificationResult(TrialOutcome.PASSED, 1, 0, 0, listOf(trial))
        val execution = TrialExecution(
            trialNumber = 1,
            bindings = mapOf("accessToken" to sensitiveValue),
            responses = mapOf(
                "workload" to listOf(RecordedResponse(1, 201, 5, sensitiveValue)),
            ),
            timings = listOf(StepTiming("workload", STARTED_AT, COMPLETED_AT)),
            stateChanged = true,
        )
        val reset = ResetOutcome(
            performed = true,
            verified = cleanupVerified,
            checks = listOf(ResetCheck("inventory_restored", "stock == 5", "5", cleanupVerified)),
            failure = if (cleanupVerified) null else "Reset verification failed",
        )
        return SpecRunOutcome(runId.toString(), result, listOf(execution), listOf(reset), cleanupVerified)
    }

    private fun sensitiveValuesStored(
        table: String,
        firstColumn: String,
        secondColumn: String?,
        value: String,
    ): Long {
        val predicate = if (secondColumn == null) {
            "$firstColumn like :pattern"
        } else {
            "$firstColumn like :pattern or $secondColumn like :pattern"
        }
        return jdbcClient.sql("select count(*) from $table where $predicate")
            .param("pattern", "%$value%")
            .query(Long::class.java)
            .single()
    }

    private fun activeProfileId(): UUID = profileRepository.findActive(TARGET_ID)?.id
        ?: error("Test target must have an active Profile")

    private fun checksum(): String = UUID.randomUUID().toString().replace("-", "") +
        UUID.randomUUID().toString().replace("-", "")

    private companion object {
        const val TARGET_ID = "contract-test-target"
        val STARTED_AT: Instant = Instant.parse("2026-08-20T01:30:00Z")
        val COMPLETED_AT: Instant = Instant.parse("2026-08-20T01:31:00Z")
    }
}
