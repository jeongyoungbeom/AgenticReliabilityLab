package com.project.agenticreliabilitylab.experiment.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.execution.application.OutboxJobPublisher
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import com.project.agenticreliabilitylab.experiment.application.port.CampaignExperimentOperations
import com.project.agenticreliabilitylab.experiment.application.port.ExperimentRunStore
import com.project.agenticreliabilitylab.experiment.application.port.ManifestPhase
import com.project.agenticreliabilitylab.experiment.application.port.NewExperimentRun
import com.project.agenticreliabilitylab.experiment.application.port.RunCompletion
import com.project.agenticreliabilitylab.experiment.application.port.WorkloadLeasePort
import com.project.agenticreliabilitylab.experiment.application.port.TargetExperimentProfileCatalog
import com.project.agenticreliabilitylab.experiment.domain.CleanupStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentTargetAdapter
import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.experiment.domain.ExternalOperationOutcomeUnknownException
import com.project.agenticreliabilitylab.experiment.domain.TargetPreflightFailedException
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyTargetExecutionRequest
import com.project.agenticreliabilitylab.experiment.domain.SystemOutcome
import com.project.agenticreliabilitylab.experiment.domain.TargetExperimentProfile
import com.project.agenticreliabilitylab.common.ResourceNotFoundException
import com.project.agenticreliabilitylab.target.application.TargetSystemNotFoundException
import com.project.agenticreliabilitylab.target.application.TargetSystemService
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.domain.TargetHealthStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Suppress("TooManyFunctions") // The existing experiment state machine is retained while adapters stay behind ports.
@Service
class StockConcurrencyExperimentService(
    private val targetSystemService: TargetSystemService,
    targetAdapters: List<ExperimentTargetAdapter>,
    private val targetProfileCatalog: TargetExperimentProfileCatalog,
    private val repository: ExperimentRunStore,
    private val workloadLeaseRepository: WorkloadLeasePort,
    private val objectMapper: ObjectMapper,
    private val parametersCodec: StockConcurrencyParametersCodec,
    private val outboxJobPublisher: OutboxJobPublisher,
    private val resultCollector: StockConcurrencyResultCollector,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
) : CampaignExperimentOperations {
    private val targetAdaptersById = targetAdapters.associateBy { it.adapterId }

    init {
        require(targetAdaptersById.size == targetAdapters.size) {
            "Only one ExperimentTargetAdapter may be registered for each adapter id"
        }
    }

    @Transactional
    fun start(command: StartStockConcurrencyExperiment, idempotencyKey: String): ExperimentRunRecord =
        createRun(command, idempotencyKey, null)

    @Transactional
    override fun startForCampaign(
        campaignRunId: UUID,
        campaignStepRunId: UUID,
        targetSystem: String,
        parameters: StockConcurrencyParameters,
    ): ExperimentRunRecord = createRun(
            command = StartStockConcurrencyExperiment(targetSystem, StockConcurrencyParametersInput.from(parameters)),
            idempotencyKey = "campaign:$campaignRunId:$campaignStepRunId",
            campaignLink = CampaignLink(campaignRunId, campaignStepRunId),
        )

    override fun validateForCampaign(targetSystemId: String, parameters: StockConcurrencyParameters) {
        validateStart(targetSystemId, parameters, "campaign-validation")
    }

    private fun createRun(
        command: StartStockConcurrencyExperiment,
        idempotencyKey: String,
        campaignLink: CampaignLink?,
    ): ExperimentRunRecord {
        val parameters = command.parameters.toDomain()
        val targetProfile = validateStart(command.targetSystem, parameters, idempotencyKey)

        repository.findByTargetAndIdempotencyKey(command.targetSystem, idempotencyKey)?.let { existing ->
            ensureSameRequest(existing, parameters)
            return existing
        }
        if (repository.hasBlockingCleanup(command.targetSystem)) {
            throw ExperimentRequestException(
                code = "CLEANUP_BLOCKING",
                message = "A previous experiment has unverified cleanup for target '${command.targetSystem}'",
            )
        }

        val now = clock.instant()
        val parametersJson = parametersCodec.encode(parameters)
        val run = NewExperimentRun(
            id = identifierGenerator.next(),
            targetSystemId = command.targetSystem,
            campaignRunId = campaignLink?.campaignRunId,
            campaignStepRunId = campaignLink?.campaignStepRunId,
            definitionVersion = DEFINITION_VERSION,
            parametersJson = parametersJson,
            plannedRunSpecId = identifierGenerator.next(),
            idempotencyKey = idempotencyKey,
            loadProfileJson = LOAD_PROFILE_JSON,
            fixturePlanJson = "{\"fixtureStrategy\":\"TARGET_PROFILE_ISOLATED_NAMESPACE\",\"runIdGeneratedBy\":\"ARL\"}",
            hostResourceGroup = targetProfile.hostResourceGroup,
            specHash = "${command.targetSystem}|${targetProfile.adapterId}|${targetProfile.stockConcurrency.endpoint}|$DEFINITION_VERSION|$parametersJson|$LOAD_PROFILE_JSON".sha256(),
            queuedAt = now,
        )

        try {
            repository.create(run)
        } catch (exception: DuplicateKeyException) {
            val existing = repository.findByTargetAndIdempotencyKey(command.targetSystem, idempotencyKey)
                ?: throw exception
            ensureSameRequest(existing, parameters)
            return existing
        }

        scheduleExecution(run.id)
        return repository.findById(run.id) ?: error("New experiment run '${run.id}' was not persisted")
    }

    override fun find(runId: UUID): ExperimentRunRecord =
        repository.findById(runId) ?: throw ExperimentNotFoundException(runId)

    fun evidence(runId: UUID) = repository.findEvidence(find(runId).id)

    fun recoverIncompleteRuns() {
        repository.findInProgressRunIds().forEach { runId ->
            repository.markRecoveryRequired(
                runId = runId,
                now = clock.instant(),
                message = "The application restarted while an external target operation could have been in progress",
            )
        }
        repository.findCreatedRunIds().forEach(::scheduleExecution)
    }

    private fun scheduleExecution(runId: UUID) {
        outboxJobPublisher.enqueue(OutboxJobType.EXPERIMENT_EXECUTION, runId)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun executeOutboxJob(runId: UUID) {
        if (!repository.claimForExecution(runId, clock.instant())) {
            return
        }

        val run = find(runId)
        var lease: com.project.agenticreliabilitylab.experiment.domain.WorkloadLease? = null
        var preserveLease = false
        var actionDispatched = false
        try {
            val targetSystem = targetSystemService.findById(run.targetSystemId)
            val targetProfile = targetProfileCatalog.requireStockConcurrency(run.targetSystemId)
            val targetAdapter = adapterFor(targetProfile)
            val health = targetSystemService.health(run.targetSystemId)
            val preManifest = json(
                mapOf(
                    "targetSystemId" to targetSystem.id,
                    "environment" to targetSystem.environment.name,
                    "identityVerification" to targetSystem.identityVerification.name,
                    "capabilities" to targetSystem.capabilities.map { it.name }.sorted(),
                    "targetProfile" to mapOf("adapterId" to targetProfile.adapterId),
                    "health" to mapOf(
                        "status" to health.status.name,
                        "httpStatus" to health.httpStatus,
                        "observedAt" to health.observedAt.toString(),
                    ),
                ),
            )
            repository.attachManifest(run.id, ManifestPhase.PRE_RUN, preManifest, preManifest.sha256(), clock.instant())
            if (health.status != TargetHealthStatus.UP) {
                completeValidationFailure(run.id, "Target health is ${health.status}")
                return
            }

            repository.updateRunStatus(run.id, ExperimentRunStatus.PREPARING, clock.instant())
            val now = clock.instant()
            lease = workloadLeaseRepository.tryAcquire(
                hostResourceGroup = targetProfile.hostResourceGroup,
                ownerId = run.id.toString(),
                leaseOwner = "experiment:${run.id}",
                now = now,
                expiresAt = now.plus(targetProfile.stockConcurrency.executionTimeout).plus(LEASE_GRACE),
            )
            if (lease == null) {
                repository.complete(
                    run.id,
                    RunCompletion(
                        runStatus = ExperimentRunStatus.FAILED,
                        systemOutcome = SystemOutcome.NOT_EVALUATED,
                        invariantResultJson = null,
                        outcomeReason = "Another workload currently owns '${targetProfile.hostResourceGroup}'",
                        definitionVersion = null,
                        failurePhase = "PREPARING",
                        failureOwner = "WORKLOAD_LEASE",
                        failureCode = "WORKLOAD_LEASE_UNAVAILABLE",
                        failureMessage = "Another workload currently owns '${targetProfile.hostResourceGroup}'",
                        cleanupStatus = CleanupStatus.NOT_REQUIRED,
                        cleanupFailureCode = null,
                        cleanupFailureMessage = null,
                        completedAt = clock.instant(),
                    ),
                )
                return
            }

            val parameters = parametersCodec.decode(run.parametersJson)
            repository.insertAction(
                runId = run.id,
                actionId = ACTION_ID,
                requestHash = run.parametersJson.sha256(),
                fencingToken = lease.fencingToken,
                now = clock.instant(),
            )
            repository.markActionDispatched(run.id, ACTION_ID, clock.instant())
            actionDispatched = true
            repository.updateRunStatus(run.id, ExperimentRunStatus.RUNNING, clock.instant())

            val result = targetAdapter.executeStockConcurrency(
                StockConcurrencyTargetExecutionRequest(targetSystem, targetProfile, run.id, ACTION_ID, parameters),
            )
            repository.markActionConfirmed(run.id, ACTION_ID, result.targetOperationId, clock.instant())
            repository.updateRunStatus(run.id, ExperimentRunStatus.COLLECTING, clock.instant())
            resultCollector.collectAndComplete(run, targetProfile, result)
        } catch (exception: ExternalOperationOutcomeUnknownException) {
            preserveLease = true
            repository.markRecoveryRequired(runId, clock.instant(), exception.message ?: "External operation outcome is unknown")
        } catch (exception: TargetPreflightFailedException) {
            completeValidationFailure(runId, exception.message ?: "Target preflight validation failed")
        } catch (exception: TargetSystemNotFoundException) {
            completeValidationFailure(runId, exception.message ?: "Target system does not exist")
        } catch (exception: ResourceNotFoundException) {
            completeValidationFailure(runId, exception.message ?: "Target Profile does not support this scenario")
        } catch (exception: Exception) {
            if (actionDispatched) {
                preserveLease = true
                repository.markRecoveryRequired(
                    runId,
                    clock.instant(),
                    "A target action was dispatched but ARL could not durably record its result: ${exception.javaClass.simpleName}",
                )
            } else {
                repository.complete(
                    runId,
                    RunCompletion(
                        runStatus = ExperimentRunStatus.FAILED,
                        systemOutcome = SystemOutcome.NOT_EVALUATED,
                        invariantResultJson = null,
                        outcomeReason = exception.message ?: "Experiment execution failed",
                        definitionVersion = null,
                        failurePhase = "EXPERIMENT_ENGINE",
                        failureOwner = "EXPERIMENT_ENGINE",
                        failureCode = exception.javaClass.simpleName,
                        failureMessage = exception.message ?: "Experiment execution failed",
                        cleanupStatus = CleanupStatus.NOT_REQUIRED,
                        cleanupFailureCode = null,
                        cleanupFailureMessage = null,
                        completedAt = clock.instant(),
                    ),
                )
            }
        } finally {
            if (lease != null && !preserveLease) {
                workloadLeaseRepository.release(lease)
            }
        }
    }

    private fun completeValidationFailure(runId: UUID, message: String) {
        repository.complete(
            runId,
            RunCompletion(
                runStatus = ExperimentRunStatus.VALIDATION_FAILED,
                systemOutcome = SystemOutcome.NOT_EVALUATED,
                invariantResultJson = null,
                outcomeReason = message,
                definitionVersion = null,
                failurePhase = "VALIDATING",
                failureOwner = "TARGET_PREFLIGHT",
                failureCode = "TARGET_NOT_READY",
                failureMessage = message,
                cleanupStatus = CleanupStatus.NOT_REQUIRED,
                cleanupFailureCode = null,
                cleanupFailureMessage = null,
                completedAt = clock.instant(),
            ),
        )
    }

    private fun validateStart(
        targetSystemId: String,
        parameters: StockConcurrencyParameters,
        idempotencyKey: String,
    ): TargetExperimentProfile {
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            "Idempotency-Key must contain 1 to 200 letters, numbers, '.', '_', ':' or '-'"
        }
        val target = targetSystemService.findById(targetSystemId)
        require(target.enabled) { "Target system '$targetSystemId' is disabled" }
        require(target.environment in EXECUTABLE_ENVIRONMENTS) {
            "STOCK_CONCURRENCY is enabled only for LOCAL or TEST targets in Phase 1"
        }
        require(TargetCapability.STOCK_CONCURRENCY in target.capabilities) {
            "Target system '$targetSystemId' does not declare the STOCK_CONCURRENCY capability"
        }
        val profile = try {
            targetProfileCatalog.requireStockConcurrency(targetSystemId)
        } catch (exception: ResourceNotFoundException) {
            throw ExperimentRequestException(
                "TARGET_PROFILE_MISSING",
                exception.message ?: "Target Profile is missing",
                exception,
            )
        }
        if (!profile.executionEnabled) {
            throw ExperimentRequestException(
                "TARGET_EXECUTION_DISABLED",
                "Target Profile '$targetSystemId' does not enable execution",
            )
        }
        adapterFor(profile)

        val limits = profile.stockConcurrency
        require(parameters.stock in 1..limits.maxStock) {
            "stock must be between 1 and ${limits.maxStock}"
        }
        require(parameters.requestCount in 1..limits.maxRequestCount) {
            "requestCount must be between 1 and ${limits.maxRequestCount}"
        }
        require(parameters.concurrency in 1..limits.maxConcurrency && parameters.concurrency <= parameters.requestCount) {
            "concurrency must be between 1 and min(requestCount, ${limits.maxConcurrency})"
        }
        require(parameters.quantityPerRequest in 1..limits.maxQuantityPerRequest && parameters.quantityPerRequest <= parameters.stock) {
            "quantityPerRequest must be between 1 and min(stock, ${limits.maxQuantityPerRequest})"
        }
        return profile
    }

    private fun adapterFor(profile: TargetExperimentProfile): ExperimentTargetAdapter =
        targetAdaptersById[profile.adapterId]
            ?: throw ExperimentRequestException(
                "TARGET_ADAPTER_UNAVAILABLE",
                "Target Profile '${profile.targetSystemId}' requires unavailable adapter '${profile.adapterId}'",
            )

    private fun ensureSameRequest(existing: ExperimentRunRecord, parameters: StockConcurrencyParameters) {
        if (existing.experimentType != ExperimentType.STOCK_CONCURRENCY || existing.parametersJson != parametersCodec.encode(parameters)) {
            throw ExperimentRequestException(
                code = "IDEMPOTENCY_KEY_REUSED",
                message = "Idempotency-Key is already associated with a different experiment request",
            )
        }
    }

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val DEFINITION_VERSION = "stock-concurrency-v1"
        const val ACTION_ID = "stock-concurrency-target"
        const val LOAD_PROFILE_JSON = "{\"workloadModel\":\"TARGET_PROFILE_DEFINED\",\"retryPolicy\":\"NONE\"}"
        val LEASE_GRACE: Duration = Duration.ofMinutes(2)
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,200}")
        val EXECUTABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
    }
}

private data class CampaignLink(
    val campaignRunId: UUID,
    val campaignStepRunId: UUID,
)
