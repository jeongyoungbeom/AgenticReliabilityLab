package com.project.agenticreliabilitylab.analysis.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunDetails
import com.project.agenticreliabilitylab.analysis.domain.AnalysisRunRecord
import com.project.agenticreliabilitylab.analysis.domain.MultiAgentRole
import com.project.agenticreliabilitylab.analysis.application.model.MultiAgentAnalysisDetails
import com.project.agenticreliabilitylab.analysis.application.model.MultiAgentModelSelection
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisRunStore
import com.project.agenticreliabilitylab.analysis.application.port.MultiAgentAnalysisStore
import com.project.agenticreliabilitylab.analysis.application.port.NewAgentStepRun
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisRun
import com.project.agenticreliabilitylab.analysis.application.port.NewMultiAgentAnalysis
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisModelCatalog
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisModelDefinition
import com.project.agenticreliabilitylab.analysis.application.port.MultiAgentSettings
import com.project.agenticreliabilitylab.analysis.application.port.ReliabilityAgentSettings
import com.project.agenticreliabilitylab.execution.application.OutboxJobPublisher
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
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
 * A sequential, read-only analysis architecture. Every role sees only the
 * immutable dataset and untrusted outputs from preceding roles; it has no target
 * client, tool port, shell, database, or write-capable dependency.
 */
@Service
class MultiReliabilityAgent(
    private val datasetService: AnalysisDatasetService,
    private val analysisRepository: AnalysisRunStore,
    private val multiAgentRepository: MultiAgentAnalysisStore,
    private val modelRegistry: AnalysisModelCatalog,
    private val objectMapper: ObjectMapper,
    private val agentProperties: ReliabilityAgentSettings,
    private val properties: MultiAgentSettings,
    transactionManager: PlatformTransactionManager,
    private val outboxJobPublisher: OutboxJobPublisher,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
    private val executor: MultiAgentAnalysisExecutor,
) : ReliabilityAnalysisAgent {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    init {
        require(properties.promptVersion.isNotBlank() && properties.promptVersion.length <= 100) {
            "arl.multi-agent.prompt-version must contain 1 to 100 characters"
        }
        require(properties.maxStepOutputBytes in 1_024..1_048_576) {
            "arl.multi-agent.max-step-output-bytes must be between 1024 and 1048576"
        }
    }

    fun startForExperiment(
        experimentRunId: UUID,
        idempotencyKey: String,
        selection: MultiAgentModelSelection = MultiAgentModelSelection(),
    ): AnalysisRunRecord {
        val normalized = normalizeSelection(selection)
        val internalKey = internalIdempotencyKey(idempotencyKey)
        findByExperiment(experimentRunId, internalKey)?.let { return ensureSameConfiguration(it, normalized) }
        val dataset = datasetService.createForExperiment(experimentRunId)
        return createForDataset(dataset, internalKey, normalized)
    }

    fun startForTargetTestBatch(
        targetTestBatchId: UUID,
        idempotencyKey: String,
        selection: MultiAgentModelSelection = MultiAgentModelSelection(),
    ): AnalysisRunRecord {
        val normalized = normalizeSelection(selection)
        val internalKey = internalIdempotencyKey(idempotencyKey)
        findByTargetTestBatch(targetTestBatchId, internalKey)?.let { return ensureSameConfiguration(it, normalized) }
        val dataset = datasetService.createForTargetTestBatch(targetTestBatchId)
        return createForDataset(dataset, internalKey, normalized)
    }

    /** Used by a comparison transaction to start one multi-agent run on an immutable dataset. */
    fun startForDataset(
        analysisDatasetId: UUID,
        idempotencyKey: String,
        selection: MultiAgentModelSelection = MultiAgentModelSelection(),
    ): AnalysisRunRecord {
        val normalized = normalizeSelection(selection)
        val internalKey = internalIdempotencyKey(idempotencyKey)
        return createForDataset(datasetService.find(analysisDatasetId), internalKey, normalized)
    }

    override val agentType: String = AGENT_TYPE

    override fun find(analysisRunId: UUID): AnalysisRunDetails = findDetails(analysisRunId).analysis

    fun findDetails(analysisRunId: UUID): MultiAgentAnalysisDetails {
        val configuration = multiAgentRepository.findConfiguration(analysisRunId)
            ?: throw MultiAgentAnalysisNotFoundException(analysisRunId)
        val analysis = analysisRepository.findDetails(analysisRunId)
            ?: throw AnalysisNotFoundException(analysisRunId)
        return MultiAgentAnalysisDetails(
            analysis = analysis,
            configurationJson = configuration.configurationJson,
            agentSteps = multiAgentRepository.findSteps(analysisRunId),
            invocations = multiAgentRepository.findInvocations(analysisRunId),
        )
    }

    fun recoverIncompleteRuns() {
        if (!properties.enabled || !agentProperties.enabled) return
        multiAgentRepository.findRunningAnalysisRunIds().forEach { analysisRunId ->
            val message = "ARL restarted while a multi-agent analysis was running; its model result was not replayed"
            multiAgentRepository.failIncompleteSteps(analysisRunId, "ARL_RESTART", message, clock.instant())
            analysisRepository.fail(analysisRunId, "ARL_RESTART", message, clock.instant())
        }
        multiAgentRepository.findRequestedAnalysisRunIds().forEach(::schedule)
    }

    private fun createForDataset(
        dataset: com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord,
        internalIdempotencyKey: String,
        selection: NormalizedSelection,
    ): AnalysisRunRecord {
        val existing = findByDataset(dataset, internalIdempotencyKey)
        if (existing != null) return ensureSameConfiguration(existing, selection)

        val now = clock.instant()
        val analysisRun = NewAnalysisRun(
            id = identifierGenerator.next(),
            experimentRunId = dataset.experimentRunId,
            targetTestBatchId = dataset.targetTestBatchId,
            idempotencyKey = internalIdempotencyKey,
            agentType = AGENT_TYPE,
            agentVersion = AGENT_VERSION,
            modelKey = "MULTI",
            modelId = "role-configured",
            promptVersion = properties.promptVersion,
            analysisDatasetId = dataset.id,
            inputChecksum = dataset.checksum,
            inputEvidenceCount = dataset.evidenceCount,
            requestedAt = now,
        )
        try {
            transactionTemplate.executeWithoutResult {
                analysisRepository.create(analysisRun)
                multiAgentRepository.create(
                    NewMultiAgentAnalysis(
                        analysisRunId = analysisRun.id,
                        configurationJson = selection.configurationJson,
                        configurationHash = selection.configurationHash,
                        createdAt = now,
                    ),
                    MultiAgentRole.entries.mapIndexed { index, role ->
                        val model = selection.models.getValue(role)
                        NewAgentStepRun(
                            id = identifierGenerator.next(),
                            analysisRunId = analysisRun.id,
                            sequenceNumber = index + 1,
                            role = role,
                            modelKey = model.key,
                            modelId = model.modelId,
                            promptVersion = properties.promptVersion,
                            requestedAt = now,
                        )
                    },
                )
                outboxJobPublisher.enqueue(OutboxJobType.MULTI_ANALYSIS, analysisRun.id)
            }
        } catch (_: DuplicateKeyException) {
            // The failed insert transaction has fully rolled back before this lookup.
            // PostgreSQL otherwise rejects every statement after a unique violation.
            return findByDataset(dataset, internalIdempotencyKey)?.let { ensureSameConfiguration(it, selection) }
                ?: throw AnalysisRequestException("MULTI_ANALYSIS_CREATE_RACE", "Could not recover duplicate multi-agent request")
        }
        return analysisRepository.findById(analysisRun.id)
            ?: throw IllegalStateException("Created multi-agent analysis '${analysisRun.id}' could not be read")
    }

    fun executeOutboxJob(analysisRunId: UUID) = executor.execute(analysisRunId)

    private fun normalizeSelection(selection: MultiAgentModelSelection): NormalizedSelection {
        if (!properties.enabled) throw AnalysisRequestException("MULTI_AGENT_DISABLED", "Multi-agent analysis is disabled")
        if (!agentProperties.enabled) throw AnalysisRequestException(
            "ANALYSIS_AGENT_DISABLED",
            "Multi-agent analysis requires arl.agent.enabled=true",
        )
        require(selection.modelKey == null || selection.roleModelKeys == null) {
            "modelKey and roleModelKeys cannot be used together"
        }
        val models = if (selection.roleModelKeys == null) {
            val model = modelRegistry.resolve(selection.modelKey, agentProperties.defaultModelKey)
            MultiAgentRole.entries.associateWith { model }
        } else {
            require(selection.roleModelKeys.keys == MultiAgentRole.entries.toSet()) {
                "roleModelKeys must select exactly SUPERVISOR, PLANNER, ANALYST and REVIEWER"
            }
            MultiAgentRole.entries.associateWith { role -> modelRegistry.resolveRequired(selection.roleModelKeys.getValue(role)) }
        }
        val configurationJson = objectMapper.writeValueAsString(
            linkedMapOf(
                "architecture" to AGENT_TYPE,
                "agentVersion" to AGENT_VERSION,
                "promptVersion" to properties.promptVersion,
                "toolPolicy" to "NO_TOOLS",
                "roles" to MultiAgentRole.entries.associate { role ->
                    role.name to linkedMapOf("modelKey" to models.getValue(role).key, "modelId" to models.getValue(role).modelId)
                },
            ),
        )
        return NormalizedSelection(models, configurationJson, configurationJson.sha256())
    }

    private fun ensureSameConfiguration(existing: AnalysisRunRecord, selection: NormalizedSelection): AnalysisRunRecord {
        val existingConfiguration = multiAgentRepository.findConfiguration(existing.id)
            ?: throw AnalysisRequestException("MULTI_ANALYSIS_IDEMPOTENCY_CONFLICT", "Idempotency key belongs to a non-multi-agent analysis")
        if (existingConfiguration.configurationHash != selection.configurationHash) {
            throw AnalysisRequestException("MULTI_ANALYSIS_IDEMPOTENCY_CONFLICT", "Idempotency-Key is already associated with a different multi-agent configuration")
        }
        return existing
    }

    private fun findByExperiment(experimentRunId: UUID, internalKey: String): AnalysisRunRecord? =
        analysisRepository.findByExperimentAndIdempotencyKey(experimentRunId, internalKey)

    private fun findByTargetTestBatch(targetTestBatchId: UUID, internalKey: String): AnalysisRunRecord? =
        analysisRepository.findByTargetTestBatchAndIdempotencyKey(targetTestBatchId, internalKey)

    private fun findByDataset(
        dataset: com.project.agenticreliabilitylab.analysis.domain.AnalysisDatasetRecord,
        internalKey: String,
    ): AnalysisRunRecord? = when {
        dataset.experimentRunId != null -> findByExperiment(dataset.experimentRunId, internalKey)
        dataset.targetTestBatchId != null -> findByTargetTestBatch(dataset.targetTestBatchId, internalKey)
        else -> throw AnalysisInputException("Analysis dataset '${dataset.id}' has no source")
    }

    private fun internalIdempotencyKey(clientKey: String): String {
        require(CLIENT_IDEMPOTENCY_PATTERN.matches(clientKey)) {
            "Idempotency-Key must contain 1 to 190 letters, numbers, '.', '_', ':' or '-'"
        }
        require(!clientKey.startsWith(INTERNAL_IDEMPOTENCY_PREFIX)) {
            "Idempotency-Key prefix '$INTERNAL_IDEMPOTENCY_PREFIX' is reserved"
        }
        return "$INTERNAL_IDEMPOTENCY_PREFIX$clientKey"
    }

    private fun scheduleAfterCommit(analysisRunId: UUID) {
        outboxJobPublisher.enqueue(OutboxJobType.MULTI_ANALYSIS, analysisRunId)
    }

    private fun schedule(analysisRunId: UUID) = scheduleAfterCommit(analysisRunId)

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class NormalizedSelection(
        val models: Map<MultiAgentRole, AnalysisModelDefinition>,
        val configurationJson: String,
        val configurationHash: String,
    )

    private companion object {
        const val AGENT_TYPE = "MULTI_RELIABILITY_AGENT"
        const val AGENT_VERSION = "phase5-v1"
        const val INTERNAL_IDEMPOTENCY_PREFIX = "multi:"
        val CLIENT_IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9._:-]{1,190}")
    }
}

class MultiAgentAnalysisNotFoundException(analysisRunId: UUID) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Multi-agent analysis", analysisRunId)
