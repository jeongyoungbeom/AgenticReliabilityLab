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
import com.project.agenticreliabilitylab.experiment.domain.InvariantOutcome
import com.project.agenticreliabilitylab.experiment.domain.InvariantVerdict
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

/**
 * Persists the immutable result evidence and evaluates stock-concurrency invariants.
 *
 * Each invariant is named and judged separately so a result can report which rule broke and on what evidence, rather
 * than only that the experiment failed.
 */
@Suppress("TooManyFunctions") // One named invariant per function keeps each rule and its evidence readable.
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
        val cleanupFailure = CleanupFailure.of(resources)
        val observedAt = clock.instant()
        val evidencePayload = evidencePayload(parameters, result, resources)
        persistEvidence(run, targetProfile, result, evidencePayload, observedAt)
        resources.forEach { resource ->
            repository.insertResource(run.id, ACTION_ID, resource.type, resource.id, resource.namespace, cleanupStatus)
        }
        repository.updateRunStatus(run.id, ExperimentRunStatus.CLEANING, clock.instant())
        val completion = completion(evaluation, cleanupStatus, cleanupFailure)
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
                "invariantVersion" to INVARIANT_VERSION,
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

    private fun completion(
        evaluation: InvariantEvaluation,
        cleanupStatus: CleanupStatus,
        cleanupFailure: CleanupFailure,
    ): RunCompletion =
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
                cleanupFailure.code,
                cleanupFailure.message,
                cleanupStatus,
                cleanupFailure.code,
                cleanupFailure.message,
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
        val workloadCompleted = result.executionStatus == COMPLETED_STATUS
        val verdicts = verdicts(parameters, result, expectedSuccessCount, expectedFinalStock)
        val outcome = outcomeOf(workloadCompleted, verdicts)
        return InvariantEvaluation(
            outcome,
            reasonOf(outcome, workloadCompleted, result.executionStatus, verdicts),
            json(
                mapOf(
                    "invariantVersion" to INVARIANT_VERSION,
                    "outcome" to outcome.name,
                    "workloadCompleted" to workloadCompleted,
                    "targetReportedStatus" to result.executionStatus,
                    "expectedSuccessCount" to expectedSuccessCount,
                    "expectedFinalStock" to expectedFinalStock,
                    "verdicts" to verdicts.map { verdict ->
                        mapOf(
                            "id" to verdict.id,
                            "title" to verdict.title,
                            "outcome" to verdict.outcome.name,
                            "expected" to verdict.expected,
                            "observed" to verdict.observed,
                            "detail" to verdict.detail,
                        )
                    },
                ),
            ),
        )
    }

    private fun verdicts(
        parameters: StockConcurrencyParameters,
        result: StockConcurrencyExecutionResult,
        expectedSuccessCount: Int,
        expectedFinalStock: Int,
    ): List<InvariantVerdict> = listOf(
        verdict(
            id = "request-accounting-complete",
            title = "Every dispatched request was accounted for",
            expected = parameters.requestCount.toString(),
            observed = (result.successCount + result.failureCount).toString(),
            held = result.successCount + result.failureCount == parameters.requestCount,
        ),
        verdict(
            id = "success-count-matches-capacity",
            title = "Confirmed successes match the capacity the initial stock allowed",
            expected = expectedSuccessCount.toString(),
            observed = result.successCount.toString(),
            held = result.successCount == expectedSuccessCount,
        ),
        verdict(
            id = "committed-quantity-within-stock",
            title = "Committed quantity never exceeded the initial stock",
            expected = "at most ${parameters.stock}",
            observed = (result.successCount * parameters.quantityPerRequest).toString(),
            held = result.successCount * parameters.quantityPerRequest <= parameters.stock,
        ),
        verdict(
            id = "oversell-count-is-zero",
            title = "No oversell was recorded",
            expected = "0",
            observed = result.oversellCount.toString(),
            held = result.oversellCount == 0,
        ),
        stockVerdict(
            id = "redis-stock-matches-expected",
            title = "Final Redis stock matched the expected remainder",
            expectedFinalStock = expectedFinalStock,
            observedStock = result.finalRedisStock,
            source = "Redis",
        ),
        stockVerdict(
            id = "db-stock-matches-expected",
            title = "Final database stock matched the expected remainder",
            expectedFinalStock = expectedFinalStock,
            observedStock = result.finalDbStock,
            source = "database",
        ),
    )

    private fun verdict(
        id: String,
        title: String,
        expected: String,
        observed: String,
        held: Boolean,
    ): InvariantVerdict = InvariantVerdict(
        id = id,
        title = title,
        outcome = if (held) InvariantOutcome.PASSED else InvariantOutcome.FAILED,
        expected = expected,
        observed = observed,
        detail = if (held) "Observed $observed as required" else "Observed $observed where $expected was required",
    )

    /** A stock the Target never reported is missing evidence, so it is left unjudged instead of counted as a breach. */
    private fun stockVerdict(
        id: String,
        title: String,
        expectedFinalStock: Int,
        observedStock: Int?,
        source: String,
    ): InvariantVerdict = if (observedStock == null) {
        InvariantVerdict(
            id = id,
            title = title,
            outcome = InvariantOutcome.NOT_EVALUATED,
            expected = expectedFinalStock.toString(),
            observed = "not reported",
            detail = "The Target did not report the final $source stock",
        )
    } else {
        verdict(id, title, expectedFinalStock.toString(), observedStock.toString(), observedStock == expectedFinalStock)
    }

    /**
     * Whether the Target satisfied the domain invariants.
     *
     * A workload the Target never completed is a precondition failure, not a domain violation: the counts it reported
     * describe an aborted run, so treating them as a reliability finding would overstate what was observed. Once the
     * workload did complete, an observed violation outranks missing evidence, because a broken rule is known even when
     * some other observation is absent.
     */
    private fun outcomeOf(workloadCompleted: Boolean, verdicts: List<InvariantVerdict>): SystemOutcome = when {
        !workloadCompleted -> SystemOutcome.INCONCLUSIVE
        verdicts.any { verdict -> verdict.outcome == InvariantOutcome.FAILED } -> SystemOutcome.FAILED
        verdicts.any { verdict -> verdict.outcome == InvariantOutcome.NOT_EVALUATED } -> SystemOutcome.INCONCLUSIVE
        else -> SystemOutcome.PASSED
    }

    private fun reasonOf(
        outcome: SystemOutcome,
        workloadCompleted: Boolean,
        targetStatus: String,
        verdicts: List<InvariantVerdict>,
    ): String = when {
        !workloadCompleted ->
            "The Target reported '$targetStatus' instead of $COMPLETED_STATUS, so the " +
                "${verdicts.size} STOCK_CONCURRENCY invariants could not be judged"

        outcome == SystemOutcome.PASSED -> "All ${verdicts.size} STOCK_CONCURRENCY invariants passed"
        outcome == SystemOutcome.FAILED -> summarize("failed", verdicts, InvariantOutcome.FAILED) { verdict ->
            "${verdict.id} (expected ${verdict.expected}, observed ${verdict.observed})"
        }

        else -> summarize("could not be evaluated", verdicts, InvariantOutcome.NOT_EVALUATED) { verdict ->
            "${verdict.id} (${verdict.detail})"
        }
    }

    /** Trims at an item boundary and says how many were dropped, so a long reason never ends mid-identifier. */
    private fun summarize(
        label: String,
        verdicts: List<InvariantVerdict>,
        outcome: InvariantOutcome,
        describe: (InvariantVerdict) -> String,
    ): String {
        val descriptions = verdicts.filter { verdict -> verdict.outcome == outcome }.map(describe)
        val kept = mutableListOf<String>()
        var length = 0
        for (description in descriptions) {
            if (length + description.length > MAX_REASON_DETAIL_LENGTH) break
            kept.add(description)
            length += description.length + SEPARATOR_LENGTH
        }
        val omitted = descriptions.size - kept.size
        val suffix = if (omitted > 0) " (and $omitted more)" else ""
        return "${descriptions.size} STOCK_CONCURRENCY invariant(s) $label: ${kept.joinToString("; ")}$suffix"
    }

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)
    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ACTION_ID = "stock-concurrency-target"
        const val DEFINITION_VERSION = "stock-concurrency-v1"

        /** Bump when a rule change would judge the same observations differently. */
        const val INVARIANT_VERSION = "stock-concurrency-invariants-v1"
        const val COMPLETED_STATUS = "COMPLETED"
        const val MAX_REASON_DETAIL_LENGTH = 800
        const val SEPARATOR_LENGTH = 2
        const val MAX_MESSAGE_LENGTH = 500
    }
}

/**
 * Why a run's cleanup could not be verified.
 *
 * Both cases block the next run on the Target, but they send the operator to different places: a Target that reported
 * nothing it created has a defective Harness contract, while a Target whose recorded resources went unverified may
 * have left real state behind. Reporting one code for both would hide that difference from everything downstream, so
 * the code and its message are chosen together here and cannot drift apart.
 */
private enum class CleanupFailure(val code: String, val message: String) {
    NO_RESOURCES_RECORDED(
        "CLEANUP_NO_RESOURCES",
        "The Target reported no created resources, so cleanup could not be verified",
    ),
    NOT_VERIFIED(
        "CLEANUP_NOT_VERIFIED",
        "The Target Profile did not verify cleanup for all recorded resources",
    ),
    ;

    companion object {
        fun of(resources: List<TargetResource>): CleanupFailure =
            if (resources.isEmpty()) NO_RESOURCES_RECORDED else NOT_VERIFIED
    }
}
