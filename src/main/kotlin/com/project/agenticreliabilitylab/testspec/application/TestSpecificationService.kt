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
        if (profile.profileVersionId != specification.profileVersionId) {
            specificationStore.supersede(specification.id, PROFILE_VERSION_INACTIVE)
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
        requireCurrentProfile(specification, profile)
        val parsed = parseAndValidate(specification, profile)
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
            profileVersionId = specification.profileVersionId,
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

    fun findSpecification(specificationId: UUID): TestSpecificationView = view(requireSpecification(specificationId))

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

    private fun requireCurrentProfile(
        specification: StoredTestSpecification,
        profile: ActiveTestSpecExecutionProfile,
    ) {
        if (specification.profileVersionId != profile.profileVersionId) {
            specificationStore.supersede(specification.id, PROFILE_VERSION_INACTIVE)
            throw ClientRequestException(
                "TEST_SPECIFICATION_PROFILE_VERSION_INACTIVE",
                "Test specification '${specification.id}' is bound to an inactive Profile Version",
            )
        }
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
    }
}
