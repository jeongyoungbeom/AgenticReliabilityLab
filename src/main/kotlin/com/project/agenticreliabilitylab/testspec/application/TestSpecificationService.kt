package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.common.ResourceNotFoundException
import com.project.agenticreliabilitylab.target.application.TargetSystemService
import com.project.agenticreliabilitylab.targetintelligence.application.sha256Hex
import com.project.agenticreliabilitylab.testspec.application.port.ActiveTestSpecExecutionProfile
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecExecutionProfileCatalog
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecRunStore
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecificationStore
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.StoredTestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRunStatus
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TestSpecificationStatus
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/** Owns the Phase 17 specification approval and deterministic execution lifecycle. */
@Service
@Suppress("TooManyFunctions") // Registration, approval, execution and their read views form one lifecycle boundary.
class TestSpecificationService(
    private val specificationStore: TestSpecificationStore,
    private val runStore: TestSpecRunStore,
    private val profiles: TestSpecExecutionProfileCatalog,
    private val parser: TestSpecParser,
    private val validator: TestSpecValidator,
    private val runner: TestSpecRunner,
    private val targetSystems: TargetSystemService,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("ReturnCount") // Content deduplication and a concurrent insert both deliberately return stored state.
    fun create(
        command: CreateTestSpecification,
        actor: String,
        correlationId: String,
    ): TestSpecificationView {
        val profile = requireExecutionProfile(command.targetSystemId)
        val id = identifiers.next()
        val parsed = parser.parse(
            command.documentJson,
            id,
            command.targetSystemId,
            profile.profileVersionId,
            command.source,
        )
        validator.validate(parsed, profile.capabilities)
        val checksum = sha256Hex(command.documentJson)
        val versions = specificationStore.findByTargetAndKey(command.targetSystemId, parsed.specKey)
        versions.firstOrNull { specification ->
            specification.checksum == checksum && specification.profileVersionId == profile.profileVersionId
        }?.let { existing ->
            return view(existing)
        }
        requireNextVersion(parsed, versions)
        val stored = parsed.toStored(command.documentJson, checksum, actor, correlationId)
        try {
            specificationStore.create(stored)
        } catch (exception: DuplicateKeyException) {
            specificationStore.findByTargetAndKey(command.targetSystemId, parsed.specKey)
                .firstOrNull { specification ->
                    specification.checksum == checksum && specification.profileVersionId == profile.profileVersionId
                }
                ?.let { existing -> return view(existing) }
            throw exception
        }
        return view(stored)
    }

    @Suppress("ReturnCount") // Re-approval and Profile supersession deliberately return current terminal state.
    fun approve(
        specificationId: UUID,
        confirmation: String,
        actor: String,
        correlationId: String,
    ): TestSpecificationView {
        val specification = requireSpecification(specificationId)
        if (specification.status != TestSpecificationStatus.PENDING_APPROVAL) return view(specification)
        require(confirmation == requiredConfirmation(specification.risk)) {
            "confirmation must equal ${requiredConfirmation(specification.risk)}"
        }
        val profile = requireExecutionProfile(specification.targetSystemId)
        if (!reconcileProfileVersion(specification, profile)) {
            return view(requireSpecification(specification.id))
        }
        specificationStore.approve(specification.id, actor, correlationId, clock.instant())
        return view(requireSpecification(specification.id))
    }

    /** Claims a run before touching the Target, so concurrent retries cannot execute the specification twice. */
    @Suppress("ReturnCount") // Idempotent retries and a lost execution claim return the authoritative stored run.
    fun execute(
        specificationId: UUID,
        idempotencyKey: String,
        actor: String,
        correlationId: String,
    ): TestSpecRunView {
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            "Idempotency-Key must contain 1 to 200 letters, numbers, '.', '_', ':' or '-'"
        }
        val specification = requireSpecification(specificationId)
        val requestHash = sha256Hex(specificationId.toString())
        runStore.findByTargetAndIdempotencyKey(specification.targetSystemId, idempotencyKey)?.let { existing ->
            ensureSameRunRequest(existing, requestHash)
            return runView(existing)
        }
        requireApproved(specification)
        val profile = requireExecutionProfile(specification.targetSystemId)
        if (!reconcileProfileVersion(specification, profile)) {
            throw ClientRequestException(
                "TEST_SPECIFICATION_PROFILE_VERSION_INACTIVE",
                "Test specification '${specification.id}' is bound to an inactive Profile Version",
            )
        }
        val parsed = parseAndValidate(requireSpecification(specification.id), profile)
        runStore.findByTargetAndIdempotencyKey(specification.targetSystemId, idempotencyKey)?.let { existing ->
            ensureSameRunRequest(existing, requestHash)
            return runView(existing)
        }
        requireExecutionSlot(specification.targetSystemId)
        val target = targetSystems.findById(specification.targetSystemId)
        val run = TestSpecRun(
            id = identifiers.next(),
            specificationId = specification.id,
            targetSystemId = specification.targetSystemId,
            profileVersionId = profile.profileVersionId,
            status = TestSpecRunStatus.PENDING,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            requestedTrials = parsed.policy.trials,
            createdBy = actor,
            createdCorrelationId = correlationId,
            createdAt = clock.instant(),
        )
        try {
            runStore.create(run)
        } catch (exception: DuplicateKeyException) {
            return recoverConcurrentRun(run, exception)
        }
        if (!runStore.markRunning(run.id, clock.instant())) return runView(requireRun(run.id))
        executeClaimedRun(run, parsed, target, profile, correlationId)
        return runView(requireRun(run.id))
    }

    /**
     * Executes every currently APPROVED specification for [targetSystemId] as one regression batch.
     *
     * Reuses [execute] unchanged - each specification's run gets a derived idempotency key so retries of the
     * whole batch are as idempotent as an individual run already is. When the same specKey has more than one
     * APPROVED version (approving a new version does not by itself supersede an older approved one - see the
     * [com.project.agenticreliabilitylab.testspec.application.port.TestSpecificationStore.supersede] call sites),
     * only the highest version is run, so a batch cannot execute a specKey twice or resurrect a version the
     * Target has moved on from.
     *
     * One specification's rejection (for example a recovery-required run still blocking the Target) does not
     * abort the batch - it is captured as a failed [TestSpecRegressionRunOutcome] alongside the other
     * specifications' results, mirroring the "one item's failure must not lose the rest" discipline already used
     * for misjudgment drafting and Target test candidate generation elsewhere in this codebase.
     */
    fun triggerRegressionRuns(
        targetSystemId: String,
        idempotencyKey: String,
        actor: String,
        correlationId: String,
    ): List<TestSpecRegressionRunOutcome> {
        require(REGRESSION_IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            "Idempotency-Key must contain 1 to $MAX_REGRESSION_IDEMPOTENCY_KEY_LENGTH letters, numbers, '.', " +
                "'_', ':' or '-'"
        }
        return specificationStore.findApprovedByTarget(targetSystemId)
            .groupBy(StoredTestSpecification::specKey)
            .values
            .map { versions -> versions.maxBy(StoredTestSpecification::version) }
            .sortedBy(StoredTestSpecification::specKey)
            .map { specification -> runOne(specification, idempotencyKey, actor, correlationId) }
    }

    // execute() can also let a raw DuplicateKeyException escape (see recoverConcurrentRun()'s final `throw
    // exception`) when two batch calls race on the same derived per-specification idempotency key - that must
    // become a failed outcome too, not a 500 that discards every other specification's already-computed result.
    // Any other escaped execute() failure must still become a per-item outcome, not abort the whole batch.
    @Suppress("TooGenericExceptionCaught")
    private fun runOne(
        specification: StoredTestSpecification,
        idempotencyKey: String,
        actor: String,
        correlationId: String,
    ): TestSpecRegressionRunOutcome {
        val runIdempotencyKey = "$idempotencyKey:${specification.id}"
        return try {
            val view = execute(specification.id, runIdempotencyKey, actor, correlationId)
            regressionOutcome(specification, run = view, failureCode = null, failureMessage = null)
        } catch (exception: ClientRequestException) {
            regressionOutcome(
                specification,
                run = null,
                failureCode = exception.code,
                failureMessage = exception.message,
            )
        } catch (exception: Exception) {
            log.error(
                "Regression run for specification {} failed unexpectedly; correlationId={}, exceptionType={}",
                specification.id,
                correlationId,
                exception.javaClass.name,
            )
            regressionOutcome(
                specification,
                run = null,
                failureCode = REGRESSION_RUN_FAILURE_CODE,
                failureMessage = exception.message ?: exception.javaClass.simpleName,
            )
        }
    }

    private fun regressionOutcome(
        specification: StoredTestSpecification,
        run: TestSpecRunView?,
        failureCode: String?,
        failureMessage: String?,
    ) = TestSpecRegressionRunOutcome(
        specificationId = specification.id,
        specKey = specification.specKey,
        version = specification.version,
        run = run,
        failureCode = failureCode,
        failureMessage = failureMessage,
    )

    fun findSpecification(specificationId: UUID): TestSpecificationView = view(requireSpecification(specificationId))

    /**
     * Every specification for [targetSystemId] regardless of status, newest first, capped at
     * [MAX_LISTED_SPECIFICATIONS].
     */
    fun findByTarget(targetSystemId: String): List<TestSpecificationView> =
        specificationStore.findByTarget(targetSystemId, MAX_LISTED_SPECIFICATIONS).map(::view)

    fun findRun(runId: UUID): TestSpecRunView = runView(requireRun(runId))

    @Suppress("TooGenericExceptionCaught") // Any escaped Runner failure leaves Target state conservatively unknown.
    private fun executeClaimedRun(
        run: TestSpecRun,
        specification: TestSpecification,
        target: com.project.agenticreliabilitylab.target.domain.RegisteredTarget,
        profile: ActiveTestSpecExecutionProfile,
        correlationId: String,
    ) {
        try {
            val outcome = runner.run(
                specification,
                target,
                profile.resetPlan,
                run.id.toString(),
                profile.capabilities.observationSources,
                profile.faultInjectionPlan,
            )
            runStore.complete(run.id, outcome, clock.instant())
        } catch (exception: Exception) {
            log.error(
                "Test specification run {} failed; correlationId={}, exceptionType={}",
                run.id,
                correlationId,
                exception.javaClass.name,
            )
            runStore.markFailed(
                run.id,
                recoveryRequired = true,
                failure = "Execution failed unexpectedly; recovery must be verified before another run",
                completedAt = clock.instant(),
            )
        }
    }

    private fun parseAndValidate(
        specification: StoredTestSpecification,
        profile: ActiveTestSpecExecutionProfile,
    ): TestSpecification = parser.parse(
        specification.documentJson,
        specification.id,
        specification.targetSystemId,
        specification.profileVersionId,
        specification.source,
    ).also { parsed -> validator.validate(parsed, profile.capabilities) }

    private fun requireApproved(specification: StoredTestSpecification) {
        if (specification.status != TestSpecificationStatus.APPROVED) {
            throw ClientRequestException(
                "TEST_SPECIFICATION_NOT_APPROVED",
                "Test specification '${specification.id}' is not approved",
            )
        }
    }

    /**
     * Reconciles [specification] against [profile]'s Version, if they differ.
     *
     * A Profile Version bump does not by itself invalidate a specification - only a bump that breaks a reference
     * the specification actually uses does. Revalidating the stored document against the new Profile's
     * capabilities answers that the same way initial validation did: references still hold -> only the
     * profileVersionId pointer moves and no re-approval is required; a reference broke -> the specification is
     * superseded exactly as before.
     *
     * The pointer move is a compare-and-swap (see [TestSpecificationStore.reviseProfileVersion]) so a concurrent
     * reconciliation of the same row cannot be silently lost - a failed swap is resolved by re-reading the row
     * rather than assumed to mean this reconciliation failed.
     *
     * Returns true when [specification] is (now) bound to [profile]'s Version, false when it was superseded.
     */
    @Suppress("ReturnCount") // Same-version, broken-reference, and a lost compare-and-swap are distinct exits.
    private fun reconcileProfileVersion(
        specification: StoredTestSpecification,
        profile: ActiveTestSpecExecutionProfile,
    ): Boolean {
        if (specification.profileVersionId == profile.profileVersionId) return true
        val stillValid = try {
            val parsed = parser.parse(
                specification.documentJson,
                specification.id,
                specification.targetSystemId,
                profile.profileVersionId,
                specification.source,
            )
            validator.validate(parsed, profile.capabilities)
            true
        } catch (_: SpecParseException) {
            false
        } catch (_: SpecValidationException) {
            false
        }
        if (!stillValid) {
            specificationStore.supersede(specification.id, PROFILE_VERSION_INACTIVE)
            return false
        }
        val revised = specificationStore.reviseProfileVersion(
            specification.id,
            specification.profileVersionId,
            profile.profileVersionId,
        )
        // A false result means a concurrent request already moved (or superseded) this same row - re-read it
        // instead of assuming failure, since that request may have reached the same, equally valid, Version first.
        return revised || requireSpecification(specification.id).profileVersionId == profile.profileVersionId
    }

    private fun requireExecutionSlot(targetSystemId: String) {
        if (runStore.hasBlockingRun(targetSystemId)) {
            throw ClientRequestException(
                "TEST_SPECIFICATION_RECOVERY_REQUIRED",
                "Target '$targetSystemId' has an earlier run that is active or requires verified recovery",
            )
        }
    }

    private fun requireExecutionProfile(targetSystemId: String): ActiveTestSpecExecutionProfile = try {
        profiles.requireActive(targetSystemId)
    } catch (exception: IllegalArgumentException) {
        throw ClientRequestException(
            "TEST_SPECIFICATION_EXECUTION_PROFILE_UNAVAILABLE",
            exception.message ?: "Target '$targetSystemId' cannot execute test specifications",
            exception,
        )
    }

    private fun requireNextVersion(
        specification: TestSpecification,
        versions: List<StoredTestSpecification>,
    ) {
        val expected = (versions.maxOfOrNull(StoredTestSpecification::version) ?: 0) + 1
        if (specification.version != expected) {
            throw ClientRequestException(
                "TEST_SPECIFICATION_VERSION_CONFLICT",
                "Specification '${specification.specKey}' must use version $expected",
            )
        }
    }

    private fun recoverConcurrentRun(run: TestSpecRun, exception: DuplicateKeyException): TestSpecRunView {
        runStore.findByTargetAndIdempotencyKey(run.targetSystemId, run.idempotencyKey)?.let { existing ->
            ensureSameRunRequest(existing, run.requestHash)
            return runView(existing)
        }
        if (runStore.hasBlockingRun(run.targetSystemId)) requireExecutionSlot(run.targetSystemId)
        throw exception
    }

    private fun ensureSameRunRequest(run: TestSpecRun, requestHash: String) {
        if (run.requestHash != requestHash) {
            throw ClientRequestException(
                "TEST_SPECIFICATION_RUN_IDEMPOTENCY_CONFLICT",
                "Idempotency-Key was reused for a different test specification",
            )
        }
    }

    private fun TestSpecification.toStored(
        documentJson: String,
        checksum: String,
        actor: String,
        correlationId: String,
    ) = StoredTestSpecification(
        id = id,
        targetSystemId = targetSystemId,
        specKey = specKey,
        version = version,
        title = title,
        profileVersionId = profileVersionId,
        source = source,
        category = category,
        risk = risk,
        status = TestSpecificationStatus.PENDING_APPROVAL,
        documentJson = documentJson,
        checksum = checksum,
        createdBy = actor,
        createdCorrelationId = correlationId,
        createdAt = clock.instant(),
    )

    private fun view(specification: StoredTestSpecification): TestSpecificationView {
        val parsed = parser.parse(
            specification.documentJson,
            specification.id,
            specification.targetSystemId,
            specification.profileVersionId,
            specification.source,
        )
        return TestSpecificationView(
            specification = specification,
            profileVersionActive = activeProfileId(specification.targetSystemId) == specification.profileVersionId,
            requiredConfirmation = requiredConfirmation(specification.risk),
            unfoundedThresholds = parsed.unfoundedThresholds(),
        )
    }

    private fun activeProfileId(targetSystemId: String): UUID? = try {
        profiles.requireActive(targetSystemId).profileVersionId
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun runView(run: TestSpecRun) = TestSpecRunView(
        run = run,
        trials = runStore.findTrials(run.id),
        resets = runStore.findResets(run.id),
    )

    private fun requireSpecification(id: UUID): StoredTestSpecification =
        specificationStore.findById(id) ?: throw ResourceNotFoundException("TestSpecification", id)

    private fun requireRun(id: UUID): TestSpecRun =
        runStore.findById(id) ?: throw ResourceNotFoundException("TestSpecRun", id)

    private fun requiredConfirmation(risk: SpecRisk): String = when (risk) {
        SpecRisk.SAFE -> "APPROVE_SAFE_TEST_SPECIFICATION"
        SpecRisk.MODERATE -> "APPROVE_MODERATE_TEST_SPECIFICATION"
        SpecRisk.DESTRUCTIVE -> "APPROVE_DESTRUCTIVE_TEST_SPECIFICATION"
    }

    private companion object {
        const val PROFILE_VERSION_INACTIVE = "Target Profile Version is no longer active"
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,200}")

        // Matches TargetKnowledgeSnapshotService.MAX_LISTED_SNAPSHOTS - an unbounded per-target listing query
        // is the risk being capped here, not a real expected volume of specifications.
        const val MAX_LISTED_SPECIFICATIONS = 50

        // A regression run's own key gets ":" plus a 36-character specification UUID appended before it reaches
        // execute()'s 200-character idempotency_key column, so the caller-supplied key is capped well under that.
        const val MAX_REGRESSION_IDEMPOTENCY_KEY_LENGTH = 160
        val REGRESSION_IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,$MAX_REGRESSION_IDEMPOTENCY_KEY_LENGTH}")
        const val REGRESSION_RUN_FAILURE_CODE = "TEST_SPECIFICATION_REGRESSION_RUN_FAILED"
    }
}
