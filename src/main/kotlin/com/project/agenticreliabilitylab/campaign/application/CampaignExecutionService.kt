package com.project.agenticreliabilitylab.campaign.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.campaign.domain.CampaignRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignRunStatus
import com.project.agenticreliabilitylab.campaign.domain.CampaignStepRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignStepStatus
import com.project.agenticreliabilitylab.campaign.application.port.CampaignRunStore
import com.project.agenticreliabilitylab.execution.application.OutboxJobExecutionResult
import com.project.agenticreliabilitylab.execution.application.OutboxJobPublisher
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import com.project.agenticreliabilitylab.experiment.application.ExperimentNotFoundException
import com.project.agenticreliabilitylab.experiment.application.ExperimentRequestException
import com.project.agenticreliabilitylab.campaign.application.model.StartStockConcurrencyCampaign
import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyParametersCodec
import com.project.agenticreliabilitylab.experiment.application.port.CampaignExperimentOperations
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunStatus
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

// Campaign lifecycle transitions are intentionally colocated with their state-machine invariants.
@Suppress("TooManyFunctions")
@Service
class CampaignExecutionService(
    private val campaignRepository: CampaignRunStore,
    private val experimentService: CampaignExperimentOperations,
    private val stepExperimentStarter: CampaignStepExperimentStarter,
    private val outboxJobPublisher: OutboxJobPublisher,
    private val parametersCodec: StockConcurrencyParametersCodec,
    private val clock: Clock,
    identifierGenerator: IdentifierGenerator,
) {
    private val workerId = "campaign-worker:${identifierGenerator.next()}"

    @Transactional
    @Suppress("ReturnCount") // Idempotency recovery has explicit early exits by design.
    fun start(command: StartStockConcurrencyCampaign, idempotencyKey: String): CampaignRunRecord {
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            "Idempotency-Key must contain 1 to 200 letters, numbers, '.', '_', ':' or '-'"
        }
        require(command.repeatCount in 1..MAX_REPEAT_COUNT) {
            "repeatCount must be between 1 and $MAX_REPEAT_COUNT"
        }
        val parameters = command.parameters
        experimentService.validateForCampaign(command.targetSystemId, parameters)
        val parametersJson = parametersCodec.encode(parameters)

        campaignRepository.findByTargetAndIdempotency(command.targetSystemId, idempotencyKey)?.let { existing ->
            ensureSameCampaign(existing, parametersJson, command.repeatCount)
            if (existing.status == CampaignRunStatus.RUNNING) {
                schedule(existing.id)
            }
            return existing
        }

        val campaign = try {
            campaignRepository.createRun(
                targetSystemId = command.targetSystemId,
                idempotencyKey = idempotencyKey,
                parametersJson = parametersJson,
                repeatCount = command.repeatCount,
                now = clock.instant(),
            )
        } catch (exception: DuplicateKeyException) {
            val existing = campaignRepository.findByTargetAndIdempotency(command.targetSystemId, idempotencyKey)
                ?: throw exception
            ensureSameCampaign(existing, parametersJson, command.repeatCount)
            if (existing.status == CampaignRunStatus.RUNNING) {
                schedule(existing.id)
            }
            return existing
        }
        schedule(campaign.id)
        return find(campaign.id)
    }

    fun find(campaignRunId: UUID): CampaignRunRecord =
        campaignRepository.findRun(campaignRunId) ?: throw CampaignNotFoundException(campaignRunId)

    fun steps(campaignRunId: UUID): List<CampaignStepRunRecord> {
        find(campaignRunId)
        return campaignRepository.findSteps(campaignRunId)
    }

    fun recoverActiveCampaigns() {
        campaignRepository.findRunningRuns().forEach { schedule(it.id) }
    }

    private fun schedule(campaignRunId: UUID) =
        outboxJobPublisher.enqueue(OutboxJobType.CAMPAIGN_EXECUTION, campaignRunId)

    /**
     * Performs at most one state-machine turn. A campaign can wait for a child
     * experiment for minutes, so it must never occupy a shared outbox worker
     * while merely polling that child.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun executeOutboxJob(campaignRunId: UUID): OutboxJobExecutionResult {
        val campaign = campaignRepository.findRun(campaignRunId) ?: return OutboxJobExecutionResult.Completed
        if (campaign.status != CampaignRunStatus.RUNNING) return OutboxJobExecutionResult.Completed

        val activeStep = campaignRepository.findSteps(campaignRunId)
            .firstOrNull { it.status == CampaignStepStatus.RUNNING }
        if (activeStep != null) {
            val ownedStep = claimOrRenew(activeStep) ?: return deferForProgress()
            return when (monitor(ownedStep)) {
                MonitorResult.WAIT -> deferForProgress()
                MonitorResult.CONTINUE -> deferImmediately()
                MonitorResult.STOP -> OutboxJobExecutionResult.Completed
            }
        }

        return try {
            val now = clock.instant()
            val next = campaignRepository.claimNextQueuedStep(
                campaignRunId = campaignRunId,
                workerId = workerId,
                now = now,
                leaseExpiresAt = now.plus(CAMPAIGN_STEP_LEASE),
            )
            if (next == null) {
                if (campaignRepository.completeRunIfAllStepsCompleted(campaignRunId, now)) {
                    OutboxJobExecutionResult.Completed
                } else {
                    deferForProgress()
                }
            } else {
                stepExperimentStarter.start(campaign, next)
                deferImmediately()
            }
        } catch (exception: ExperimentRequestException) {
            markCurrentStepFailed(campaignRunId, exception.code, exception.message)
            OutboxJobExecutionResult.Completed
        } catch (exception: IllegalArgumentException) {
            markCurrentStepFailed(campaignRunId, "CAMPAIGN_STEP_INVALID", exception.message ?: "Campaign step is invalid")
            OutboxJobExecutionResult.Completed
        } catch (exception: Exception) {
            markCurrentStepRecoveryRequired(
                campaignRunId,
                "CAMPAIGN_ENGINE_ERROR",
                exception.message ?: "Campaign worker could not determine the step outcome",
            )
            OutboxJobExecutionResult.Completed
        }
    }

    private fun monitor(step: CampaignStepRunRecord): MonitorResult {
        val experimentRunId = step.experimentRunId ?: run {
            markCurrentStepRecoveryRequired(
                step.campaignRunId,
                "EXPERIMENT_LINK_MISSING",
                "A claimed campaign step has no linked experiment run",
            )
            return MonitorResult.STOP
        }

        val experiment = try {
            experimentService.find(experimentRunId)
        } catch (_: ExperimentNotFoundException) {
            markCurrentStepRecoveryRequired(
                step.campaignRunId,
                "EXPERIMENT_NOT_FOUND",
                "The linked experiment run '$experimentRunId' is missing",
            )
            return MonitorResult.STOP
        }

        return when (experiment.runStatus) {
            ExperimentRunStatus.COMPLETED -> {
                if (campaignRepository.completeStep(step, CampaignStepStatus.COMPLETED, clock.instant())) {
                    MonitorResult.CONTINUE
                } else {
                    MonitorResult.WAIT
                }
            }
            ExperimentRunStatus.RECOVERY_REQUIRED -> {
                val completed = campaignRepository.completeStep(
                    step,
                    CampaignStepStatus.RECOVERY_REQUIRED,
                    clock.instant(),
                    "EXPERIMENT_RECOVERY_REQUIRED",
                    experiment.outcomeReason,
                )
                if (completed) {
                    campaignRepository.completeRun(step.campaignRunId, CampaignRunStatus.RECOVERY_REQUIRED, clock.instant())
                    MonitorResult.STOP
                } else {
                    MonitorResult.WAIT
                }
            }
            ExperimentRunStatus.FAILED,
            ExperimentRunStatus.VALIDATION_FAILED,
            ExperimentRunStatus.TIMED_OUT,
            ExperimentRunStatus.ABORTED,
            ExperimentRunStatus.CANCELED,
            -> {
                val completed = campaignRepository.completeStep(
                    step,
                    CampaignStepStatus.FAILED,
                    clock.instant(),
                    "EXPERIMENT_${experiment.runStatus.name}",
                    experiment.outcomeReason,
                )
                if (completed) {
                    campaignRepository.completeRun(step.campaignRunId, CampaignRunStatus.FAILED, clock.instant())
                    MonitorResult.STOP
                } else {
                    MonitorResult.WAIT
                }
            }
            else -> MonitorResult.WAIT
        }
    }

    private fun markCurrentStepFailed(campaignRunId: UUID, code: String, message: String) {
        campaignRepository.findSteps(campaignRunId)
            .firstOrNull { it.status == CampaignStepStatus.RUNNING && it.leaseOwner == workerId }
            ?.let { campaignRepository.completeStep(it, CampaignStepStatus.FAILED, clock.instant(), code, message) }
        campaignRepository.completeRun(campaignRunId, CampaignRunStatus.FAILED, clock.instant())
    }

    private fun markCurrentStepRecoveryRequired(campaignRunId: UUID, code: String, message: String) {
        campaignRepository.findSteps(campaignRunId)
            .firstOrNull { it.status == CampaignStepStatus.RUNNING && it.leaseOwner == workerId }
            ?.let {
                campaignRepository.completeStep(
                    it,
                    CampaignStepStatus.RECOVERY_REQUIRED,
                    clock.instant(),
                    code,
                    message,
                )
            }
        campaignRepository.completeRun(campaignRunId, CampaignRunStatus.RECOVERY_REQUIRED, clock.instant())
    }

    private fun deferImmediately() = OutboxJobExecutionResult.Deferred(Duration.ZERO)

    private fun deferForProgress() = OutboxJobExecutionResult.Deferred(CAMPAIGN_PROGRESS_DELAY)

    private fun ensureSameCampaign(existing: CampaignRunRecord, parametersJson: String, repeatCount: Int) {
        if (existing.parametersJson != parametersJson || existing.repeatCount != repeatCount) {
            throw CampaignRequestException(
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key is already associated with a different campaign request",
            )
        }
    }

    private enum class MonitorResult { WAIT, CONTINUE, STOP }

    private fun claimOrRenew(activeStep: CampaignStepRunRecord): CampaignStepRunRecord? {
        val now = clock.instant()
        val nextLeaseExpiry = now.plus(CAMPAIGN_STEP_LEASE)
        return if (activeStep.leaseOwner == workerId) {
            campaignRepository.renewStepLease(activeStep, now, nextLeaseExpiry)
                ?: campaignRepository.takeOverExpiredRunningStep(activeStep.campaignRunId, workerId, now, nextLeaseExpiry)
        } else if (activeStep.leaseExpiresAt == null || !activeStep.leaseExpiresAt.isAfter(now)) {
            campaignRepository.takeOverExpiredRunningStep(activeStep.campaignRunId, workerId, now, nextLeaseExpiry)
        } else {
            null
        }
    }

    private companion object {
        const val MAX_REPEAT_COUNT = 20
        val CAMPAIGN_PROGRESS_DELAY: Duration = Duration.ofMillis(250)
        val CAMPAIGN_STEP_LEASE: Duration = Duration.ofMinutes(1)
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,200}")
    }
}

class CampaignNotFoundException(campaignRunId: UUID) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Campaign run", campaignRunId)

class CampaignRequestException(
    override val code: String,
    override val message: String,
) : com.project.agenticreliabilitylab.common.ClientRequestException(code, message)
