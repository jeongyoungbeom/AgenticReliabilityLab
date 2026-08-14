package com.project.agenticreliabilitylab.experiment.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.experiment.application.port.ExperimentRunStore
import com.project.agenticreliabilitylab.experiment.application.port.ManifestPhase
import com.project.agenticreliabilitylab.experiment.application.port.NewArtifact
import com.project.agenticreliabilitylab.experiment.application.port.NewEvidence
import com.project.agenticreliabilitylab.experiment.application.port.RunCompletion
import com.project.agenticreliabilitylab.experiment.domain.CleanupStatus
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunStatus
import com.project.agenticreliabilitylab.experiment.domain.InvariantEvaluation
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyExecutionResult
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters
import com.project.agenticreliabilitylab.experiment.domain.SystemOutcome
import com.project.agenticreliabilitylab.experiment.domain.TargetExperimentProfile
import com.project.agenticreliabilitylab.experiment.domain.TargetResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

/** Persists the immutable result evidence and evaluates stock-concurrency invariants. */
@Component
class StockConcurrencyResultCollector(
    private val repository: ExperimentRunStore,
    private val objectMapper: ObjectMapper,
    private val parametersCodec: StockConcurrencyParametersCodec,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
) {
    fun collectAndComplete(
        run: ExperimentRunRecord,
        targetProfile: TargetExperimentProfile,
        result: StockConcurrencyExecutionResult,
    ) {
        val parameters = parametersCodec.decode(run.parametersJson)
        val evaluation = evaluate(parameters, result)
        val resources = result.resources.ifEmpty {
            result.productId?.let { listOf(TargetResource("PRODUCT", it, run.id.toString())) }
                ?: emptyList()
        }
        val cleanupStatus = cleanupStatus(result, resources)
        val observedAt = clock.instant()
        val evidencePayload = evidencePayload(parameters, result, resources)
        persistEvidence(run, targetProfile, result, evidencePayload, observedAt)
        resources.forEach { resource ->
            repository.insertResource(run.id, ACTION_ID, resource.type, resource.id, resource.namespace, cleanupStatus)
        }
        repository.updateRunStatus(run.id, ExperimentRunStatus.CLEANING, clock.instant())
        val completion = completion(evaluation, cleanupStatus)
        persistPostRunManifest(run, targetProfile, evaluation, completion)
        repository.complete(run.id, completion)
    }

    private fun cleanupStatus(
        result: StockConcurrencyExecutionResult,
        resources: List<TargetResource>,
    ): CleanupStatus =
        if (result.cleanupVerified && resources.isNotEmpty()) CleanupStatus.VERIFIED else CleanupStatus.FAILED

    private fun evidencePayload(
        parameters: StockConcurrencyParameters,
        result: StockConcurrencyExecutionResult,
        resources: List<TargetResource>,
    ): String = json(
        mapOf(
            "executionStatus" to result.executionStatus,
            "message" to result.message.take(MAX_MESSAGE_LENGTH),
            "parameters" to mapOf(
                "stock" to parameters.stock,
                "requestCount" to parameters.requestCount,
                "concurrency" to parameters.concurrency,
                "quantityPerRequest" to parameters.quantityPerRequest,
            ),
            "result" to mapOf(
                "successCount" to result.successCount,
                "failureCount" to result.failureCount,
                "oversellCount" to result.oversellCount,
                "finalRedisStock" to result.finalRedisStock,
                "finalDbStock" to result.finalDbStock,
                "durationSeconds" to result.durationSeconds,
                "cleanupVerified" to result.cleanupVerified,
            ),
            "resourceCount" to resources.size,
            "performanceMetrics" to "TARGET_PROFILE_DEFINED",
        ),
    )

    private fun persistEvidence(
        run: ExperimentRunRecord,
        targetProfile: TargetExperimentProfile,
        result: StockConcurrencyExecutionResult,
        evidencePayload: String,
        observedAt: java.time.Instant,
    ) {
        repository.insertArtifact(NewArtifact(
            identifierGenerator.next(),
            run.id,
            "TARGET_RESULT_CONTRACT",
            result.artifactReference,
            result.artifactChecksum,
            observedAt,
        ))
        repository.insertEvidence(NewEvidence(
            id = identifierGenerator.next(),
            runId = run.id,
            evidenceType = "STOCK_CONCURRENCY_RESULT",
            schemaVersion = "stock-concurrency-evidence-v1",
            source = "TARGET_ADAPTER:${targetProfile.adapterId}",
            collectorVersion = DEFINITION_VERSION,
            observedAt = observedAt,
            completeness = if (result.finalRedisStock == null || result.finalDbStock == null) {
                "PARTIAL"
            } else {
                "COMPLETE"
            },
            payloadJson = evidencePayload,
            artifactRefsJson = json(mapOf("artifactReference" to result.artifactReference)),
            checksum = evidencePayload.sha256(),
            createdAt = observedAt,
        ))
    }

    private fun completion(evaluation: InvariantEvaluation, cleanupStatus: CleanupStatus): RunCompletion =
        if (cleanupStatus == CleanupStatus.VERIFIED) {
            RunCompletion(
                ExperimentRunStatus.COMPLETED,
                evaluation.outcome,
                evaluation.payloadJson,
                evaluation.reason,
                DEFINITION_VERSION,
                null,
                null,
                null,
                null,
                cleanupStatus,
                null,
                null,
                clock.instant(),
            )
        } else {
            RunCompletion(
                ExperimentRunStatus.FAILED,
                evaluation.outcome,
                evaluation.payloadJson,
                evaluation.reason,
                DEFINITION_VERSION,
                "CLEANING",
                "TARGET_ADAPTER",
                "CLEANUP_NOT_VERIFIED",
                CLEANUP_FAILURE_MESSAGE,
                cleanupStatus,
                "CLEANUP_NOT_VERIFIED",
                CLEANUP_FAILURE_MESSAGE,
                clock.instant(),
            )
        }

    private fun persistPostRunManifest(
        run: ExperimentRunRecord,
        targetProfile: TargetExperimentProfile,
        evaluation: InvariantEvaluation,
        completion: RunCompletion,
    ) {
        val postManifest = json(
            mapOf(
                "adapterId" to targetProfile.adapterId,
                "systemOutcome" to completion.systemOutcome.name,
                "cleanupStatus" to completion.cleanupStatus.name,
                "invariant" to evaluation.payloadJson,
            ),
        )
        repository.attachManifest(
            run.id,
            ManifestPhase.POST_RUN,
            postManifest,
            postManifest.sha256(),
            clock.instant(),
        )
    }

    private fun evaluate(
        parameters: StockConcurrencyParameters,
        result: StockConcurrencyExecutionResult,
    ): InvariantEvaluation {
        val expectedSuccessCount = minOf(
            parameters.requestCount,
            parameters.stock / parameters.quantityPerRequest,
        )
        val expectedFinalStock = parameters.stock - expectedSuccessCount * parameters.quantityPerRequest
        val checks = linkedMapOf(
            "targetReportedCompleted" to (result.executionStatus == "COMPLETED"),
            "requestAccountingComplete" to (result.successCount + result.failureCount == parameters.requestCount),
            "successCountMatchesCapacity" to (result.successCount == expectedSuccessCount),
            "committedQuantityWithinStock" to (
                result.successCount * parameters.quantityPerRequest <= parameters.stock
            ),
            "oversellCountIsZero" to (result.oversellCount == 0),
            "redisStockMatchesExpected" to (result.finalRedisStock == expectedFinalStock),
            "dbStockMatchesExpected" to (result.finalDbStock == expectedFinalStock),
        )
        val outcome = when {
            result.finalRedisStock == null || result.finalDbStock == null -> SystemOutcome.INCONCLUSIVE
            checks.values.all { it } -> SystemOutcome.PASSED
            else -> SystemOutcome.FAILED
        }
        val reason = when (outcome) {
            SystemOutcome.PASSED -> "All deterministic STOCK_CONCURRENCY invariants passed"
            SystemOutcome.INCONCLUSIVE -> "The Target result did not provide both Redis and DB final stock"
            else -> "One or more deterministic STOCK_CONCURRENCY invariants failed"
        }
        return InvariantEvaluation(
            outcome, reason,
            json(
                mapOf(
                    "outcome" to outcome.name,
                    "expectedSuccessCount" to expectedSuccessCount,
                    "expectedFinalStock" to expectedFinalStock,
                    "checks" to checks,
                ),
            ),
        )
    }

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)
    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ACTION_ID = "stock-concurrency-target"
        const val DEFINITION_VERSION = "stock-concurrency-v1"
        const val MAX_MESSAGE_LENGTH = 500
        const val CLEANUP_FAILURE_MESSAGE =
            "The Target Profile did not verify cleanup for all recorded resources"
    }
}
