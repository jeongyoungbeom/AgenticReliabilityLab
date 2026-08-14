package com.project.agenticreliabilitylab.analysis.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.analysis.domain.AnalysisArchitecture
import com.project.agenticreliabilitylab.analysis.domain.AnalysisComparisonRecord
import com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord
import com.project.agenticreliabilitylab.analysis.application.model.AnalysisComparisonConfiguration
import com.project.agenticreliabilitylab.analysis.application.model.AnalysisComparisonDetails
import com.project.agenticreliabilitylab.analysis.application.model.ComparisonAnalysisRun
import com.project.agenticreliabilitylab.analysis.application.model.RequestedComparisonConfiguration
import com.project.agenticreliabilitylab.analysis.application.model.MultiAgentModelSelection
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisEvaluationStore
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisComparison
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisModelCatalog
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Runs only the explicitly selected architecture/model combinations against one
 * immutable dataset. The comparison service never supplies a target client to
 * either agent; it orchestrates persisted analysis runs only.
 */
@Service
class AnalysisComparisonService(
    private val datasetService: AnalysisDatasetService,
    private val singleAgent: SingleReliabilityAgent,
    private val multiAgent: MultiReliabilityAgent,
    private val modelRegistry: AnalysisModelCatalog,
    private val evaluationRepository: AnalysisEvaluationStore,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    fun start(
        experimentRunId: UUID,
        idempotencyKey: String,
        requestedConfigurations: List<RequestedComparisonConfiguration>?,
    ): AnalysisComparisonDetails = startForSource(
        idempotencyKey = idempotencyKey,
        requestedConfigurations = requestedConfigurations,
        findExisting = { evaluationRepository.findComparisonByExperimentAndIdempotencyKey(experimentRunId, idempotencyKey) },
        createDataset = { datasetService.createForExperiment(experimentRunId) },
        newComparison = { dataset, modelKeys, configurationJson, configurationHash ->
            NewAnalysisComparison(
                id = identifierGenerator.next(),
                experimentRunId = experimentRunId,
                analysisDatasetId = dataset.id,
                idempotencyKey = idempotencyKey,
                modelKeys = modelKeys,
                configurationJson = configurationJson,
                configurationHash = configurationHash,
                requestedAt = clock.instant(),
            )
        },
    )

    fun startForTargetTestBatch(
        targetTestBatchId: UUID,
        idempotencyKey: String,
        requestedConfigurations: List<RequestedComparisonConfiguration>?,
    ): AnalysisComparisonDetails = startForSource(
        idempotencyKey = idempotencyKey,
        requestedConfigurations = requestedConfigurations,
        findExisting = { evaluationRepository.findComparisonByTargetTestBatchAndIdempotencyKey(targetTestBatchId, idempotencyKey) },
        createDataset = { datasetService.createForTargetTestBatch(targetTestBatchId) },
        newComparison = { dataset, modelKeys, configurationJson, configurationHash ->
            NewAnalysisComparison(
                id = identifierGenerator.next(),
                targetTestBatchId = targetTestBatchId,
                analysisDatasetId = dataset.id,
                idempotencyKey = idempotencyKey,
                modelKeys = modelKeys,
                configurationJson = configurationJson,
                configurationHash = configurationHash,
                requestedAt = clock.instant(),
            )
        },
    )

    fun find(comparisonId: UUID): AnalysisComparisonDetails {
        val comparison = evaluationRepository.findComparison(comparisonId)
            ?: throw AnalysisComparisonNotFoundException(comparisonId)
        val dataset = datasetService.find(comparison.analysisDatasetId)
        val configurations = configurationsFor(comparison)
        val configurationsBySelectionKey = configurations.associateBy { it.selectionKey }
        val runs = evaluationRepository.findComparisonRuns(comparison.id).map { mapping ->
            val configuration = configurationsBySelectionKey[mapping.modelKey]
                ?: throw IllegalStateException("Comparison '${comparison.id}' has a run without a selected configuration")
            ComparisonAnalysisRun(mapping, configuration, singleAgent.find(mapping.analysisRunId))
        }
        return AnalysisComparisonDetails(comparison, dataset, configurations, runs)
    }

    private fun startForSource(
        idempotencyKey: String,
        requestedConfigurations: List<RequestedComparisonConfiguration>?,
        findExisting: () -> AnalysisComparisonRecord?,
        createDataset: () -> AnalysisDatasetRecord,
        newComparison: (AnalysisDatasetRecord, List<String>, String, String) -> NewAnalysisComparison,
    ): AnalysisComparisonDetails {
        require(IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            "Idempotency-Key must contain 1 to 200 letters, numbers, '.', '_', ':' or '-'"
        }
        val configurations = normalizeConfigurations(requestedConfigurations)
        val configurationJson = objectMapper.writeValueAsString(configurations)
        val configurationHash = configurationHash(configurations)

        findExisting()?.let {
            ensureSameConfiguration(it, configurations, configurationHash)
            return find(it.id)
        }

        val comparisonId = try {
            transactionTemplate.execute {
                findExisting()?.let {
                    ensureSameConfiguration(it, configurations, configurationHash)
                    return@execute it.id
                }
                val dataset = createDataset()
                val comparison = newComparison(
                    dataset,
                    configurations.map { it.modelKey }.distinct(),
                    configurationJson,
                    configurationHash,
                )
                evaluationRepository.createComparison(comparison)
                configurations.forEach { configuration ->
                    val run = when (configuration.architecture) {
                        AnalysisArchitecture.SINGLE -> singleAgent.startForDataset(
                            dataset.id,
                            "comparison:${comparison.id}:${configuration.selectionKey}",
                            configuration.modelKey,
                        )
                        AnalysisArchitecture.MULTI -> multiAgent.startForDataset(
                            dataset.id,
                            "comparison:${comparison.id}:${configuration.selectionKey}",
                            MultiAgentModelSelection(modelKey = configuration.modelKey),
                        )
                    }
                    evaluationRepository.attachComparisonRun(comparison.id, configuration.selectionKey, run.id)
                }
                comparison.id
            }
        } catch (_: DuplicateKeyException) {
            // The duplicate insert transaction has rolled back before this lookup.
            // PostgreSQL would otherwise reject the lookup in the failed transaction.
            val existing = findExisting()
                ?: throw AnalysisRequestException("COMPARISON_CREATE_RACE", "Could not recover duplicate comparison")
            ensureSameConfiguration(existing, configurations, configurationHash)
            existing.id
        }
        return find(comparisonId)
    }

    private fun normalizeConfigurations(
        requestedConfigurations: List<RequestedComparisonConfiguration>?,
    ): List<AnalysisComparisonConfiguration> {
        val requested = requestedConfigurations ?: DEFAULT_CONFIGURATIONS
        require(requested.size in 2..4) { "A comparison must select between 2 and 4 configurations" }
        val normalized = requested.map { requestedConfiguration ->
            val modelKey = requestedConfiguration.modelKey.trim().uppercase()
            require(modelKey.isNotBlank()) { "Each comparison configuration must specify a modelKey" }
            modelRegistry.resolveRequired(modelKey)
            RequestedComparisonConfiguration(requestedConfiguration.architecture, modelKey)
        }.sortedWith(compareBy<RequestedComparisonConfiguration> { it.architecture.name }.thenBy { it.modelKey })
        require(normalized.distinct().size == normalized.size) {
            "A comparison cannot select the same architecture and model twice"
        }
        return normalized.mapIndexed { index, configuration ->
            AnalysisComparisonConfiguration(
                selectionKey = "selection-${index + 1}",
                architecture = configuration.architecture,
                modelKey = configuration.modelKey,
            )
        }
    }

    private fun configurationsFor(comparison: AnalysisComparisonRecord): List<AnalysisComparisonConfiguration> {
        val storedJson = comparison.configurationJson
        if (storedJson == null) {
            // Phase 3/4 records did not distinguish architecture. They were all single-agent comparisons.
            return comparison.modelKeys.mapIndexed { index, modelKey ->
                AnalysisComparisonConfiguration(modelKey, AnalysisArchitecture.SINGLE, modelKey)
            }
        }
        val root = objectMapper.readTree(storedJson)
        require(root.isArray) { "Stored comparison configuration must be an array" }
        return root.values().map { node ->
            AnalysisComparisonConfiguration(
                selectionKey = node.path("selectionKey").asString(),
                architecture = AnalysisArchitecture.valueOf(node.path("architecture").asString()),
                modelKey = node.path("modelKey").asString(),
            )
        }
    }

    private fun ensureSameConfiguration(
        existing: AnalysisComparisonRecord,
        requestedConfigurations: List<AnalysisComparisonConfiguration>,
        requestedHash: String,
    ) {
        val existingHash = existing.configurationHash ?: configurationHash(configurationsFor(existing))
        if (existingHash != requestedHash) {
            throw AnalysisRequestException(
                "COMPARISON_IDEMPOTENCY_CONFLICT",
                "Idempotency-Key is already associated with different selected architectures or models",
            )
        }
        if (configurationsFor(existing).map { it.copy(selectionKey = "") } !=
            requestedConfigurations.map { it.copy(selectionKey = "") }
        ) {
            throw AnalysisRequestException(
                "COMPARISON_IDEMPOTENCY_CONFLICT",
                "Idempotency-Key is already associated with different selected architectures or models",
            )
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun configurationHash(configurations: List<AnalysisComparisonConfiguration>): String = objectMapper.writeValueAsString(
        configurations.map { linkedMapOf("architecture" to it.architecture.name, "modelKey" to it.modelKey) },
    ).sha256()

    private companion object {
        val DEFAULT_CONFIGURATIONS = listOf(
            RequestedComparisonConfiguration(AnalysisArchitecture.SINGLE, "GPT_OSS"),
            RequestedComparisonConfiguration(AnalysisArchitecture.SINGLE, "QWEN"),
        )
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]{1,200}")
    }
}

class AnalysisComparisonNotFoundException(comparisonId: UUID) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Analysis comparison", comparisonId)
