package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.common.ResourceNotFoundException
import com.project.agenticreliabilitylab.targetintelligence.application.port.TargetKnowledgeSnapshotStore
import com.project.agenticreliabilitylab.targetintelligence.application.sha256Hex
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeSnapshot
import com.project.agenticreliabilitylab.testcatalog.application.port.TestCandidateStore
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidate
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateGeneration
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateGenerationSource
import com.project.agenticreliabilitylab.testcatalog.domain.TestCandidateReadiness
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Produces and reads recommended tests for a Target.
 *
 * Generation is idempotent on the Knowledge Snapshot, the generator version and the active Profile Version. Those three
 * decide every stored binding, so a Profile change that would bind candidates differently yields a new candidate set
 * instead of leaving stale bindings behind, while a plain retry returns the existing one.
 */
@Service
@Suppress("TooManyFunctions") // Generation, direct request and lookup share one use case.
class TestCandidateService(
    private val store: TestCandidateStore,
    private val snapshotStore: TargetKnowledgeSnapshotStore,
    private val capabilityResolver: TargetCapabilityResolver,
    private val generator: SnapshotTestCandidateGenerator,
    private val directResolver: DirectTestCandidateResolver,
    private val validator: TestCandidateValidator,
    private val readinessResolver: TestCandidateReadinessResolver,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
) {
    fun generate(
        knowledgeSnapshotId: UUID,
        actor: String,
        correlationId: String,
    ): TestCandidateGenerationView {
        val snapshot = requireSnapshot(knowledgeSnapshotId)
        val capabilities = capabilityResolver.resolve(snapshot.targetSystemId)
        requireCurrentProfileVersion(snapshot, capabilities)
        val drafts = generator.generate(snapshot.content, capabilities)
        return persist(
            snapshot = snapshot,
            capabilities = capabilities,
            drafts = drafts,
            source = TestCandidateGenerationSource.SNAPSHOT_RULES,
            actor = actor,
            correlationId = correlationId,
        )
    }

    fun request(
        command: RequestTestCandidate,
        actor: String,
        correlationId: String,
    ): TestCandidateGenerationView {
        require(command.title.isNotBlank()) { "title must not be blank" }
        val snapshot = requireSnapshot(command.knowledgeSnapshotId)
        val capabilities = capabilityResolver.resolve(snapshot.targetSystemId)
        requireCurrentProfileVersion(snapshot, capabilities)
        val draft = directResolver.resolve(command, capabilities)
        return persist(
            snapshot = snapshot,
            capabilities = capabilities,
            drafts = listOf(draft),
            source = TestCandidateGenerationSource.DIRECT_REQUEST,
            actor = actor,
            correlationId = correlationId,
            requestDiscriminator = command.discriminator(),
        )
    }

    fun find(generationId: UUID): TestCandidateGenerationView {
        val generation = store.findGeneration(generationId)
            ?: throw ResourceNotFoundException("TestCandidateGeneration", generationId)
        return view(generation, capabilityResolver.find(generation.targetSystemId))
    }

    /** Capability is resolved once for the whole page, and candidates are left to the detail endpoint. */
    fun findByTarget(targetSystemId: String): List<TestCandidateGenerationSummaryView> {
        val activeProfileVersionId = capabilityResolver.find(targetSystemId)?.profileVersionId
        return store.findGenerationsByTarget(targetSystemId, MAX_LISTED_GENERATIONS).map { generation ->
            TestCandidateGenerationSummaryView(
                generation = generation,
                profileVersionActive = activeProfileVersionId == generation.profileVersionId,
            )
        }
    }

    @Suppress("ReturnCount") // Returning an existing generation on retry or race is a deliberate early exit.
    private fun persist(
        snapshot: TargetKnowledgeSnapshot,
        capabilities: TargetCapabilitySnapshot,
        drafts: List<TestCandidateDraft>,
        source: TestCandidateGenerationSource,
        actor: String,
        correlationId: String,
        requestDiscriminator: String = "",
    ): TestCandidateGenerationView {
        validator.validate(drafts, capabilities)
        val checksum = checksum(snapshot, capabilities, source, requestDiscriminator)
        store.findGenerationBySnapshotAndChecksum(snapshot.id, checksum)
            ?.let { existing -> return view(existing, capabilities) }
        val generation = TestCandidateGeneration(
            id = identifiers.next(),
            targetSystemId = snapshot.targetSystemId,
            knowledgeSnapshotId = snapshot.id,
            profileVersionId = capabilities.profileVersionId,
            source = source,
            generatorVersion = TEST_CANDIDATE_GENERATOR_VERSION,
            checksum = checksum,
            createdBy = actor,
            createdCorrelationId = correlationId,
            createdAt = clock.instant(),
        )
        if (!store.createIfAbsent(generation, drafts.toCandidates(generation.id))) {
            return view(requireStored(snapshot.id, checksum), capabilities)
        }
        return view(generation, capabilities)
    }

    private fun List<TestCandidateDraft>.toCandidates(generationId: UUID): List<TestCandidate> =
        mapIndexed { index, draft ->
            TestCandidate(
                id = identifiers.next(),
                generationId = generationId,
                sequenceNumber = index + 1,
                category = draft.category,
                title = draft.title.take(MAX_TITLE_CHARACTERS),
                description = draft.description.take(MAX_DESCRIPTION_CHARACTERS),
                risk = draft.risk,
                confidence = draft.confidence,
                verifiedExpectation = draft.verifiedExpectation,
                preconditions = draft.preconditions,
                binding = draft.binding,
                citations = draft.citations,
                requiredEvidence = draft.requiredEvidence,
                dataLifecyclePlan = draft.dataLifecyclePlan,
            )
        }

    private fun requireSnapshot(knowledgeSnapshotId: UUID): TargetKnowledgeSnapshot =
        snapshotStore.findById(knowledgeSnapshotId)
            ?: throw ResourceNotFoundException("TargetKnowledgeSnapshot", knowledgeSnapshotId)

    /**
     * Candidates may only be generated from a Snapshot that still describes the Profile Version in force.
     *
     * The check reuses the capability the caller already resolved, so the version the bindings are built against is the
     * same one the request was validated against.
     */
    private fun requireCurrentProfileVersion(
        snapshot: TargetKnowledgeSnapshot,
        capabilities: TargetCapabilitySnapshot,
    ) {
        if (capabilities.profileVersionId != snapshot.profileVersionId) {
            throw ClientRequestException(
                code = "KNOWLEDGE_SNAPSHOT_PROFILE_VERSION_INACTIVE",
                message = "Knowledge Snapshot '${snapshot.id}' is bound to an inactive Profile Version",
            )
        }
    }

    private fun checksum(
        snapshot: TargetKnowledgeSnapshot,
        capabilities: TargetCapabilitySnapshot,
        source: TestCandidateGenerationSource,
        requestDiscriminator: String,
    ): String = sha256Hex(
        listOf(
            TEST_CANDIDATE_GENERATOR_VERSION,
            source.name,
            snapshot.checksum,
            capabilities.profileVersionId.toString(),
            requestDiscriminator,
        ).joinToString("|"),
    )

    private fun RequestTestCandidate.discriminator(): String = listOf(
        category.name,
        title,
        description,
        targetOperationPath.orEmpty(),
        invariantStatement.orEmpty(),
    ).joinToString("~")

    private fun requireStored(knowledgeSnapshotId: UUID, checksum: String): TestCandidateGeneration =
        store.findGenerationBySnapshotAndChecksum(knowledgeSnapshotId, checksum)
            ?: throw ClientRequestException(
                code = "TEST_CANDIDATE_GENERATION_CONFLICT",
                message = "Test candidate generation conflicted; retry the request",
            )

    private fun view(
        generation: TestCandidateGeneration,
        capabilities: TargetCapabilitySnapshot?,
    ): TestCandidateGenerationView {
        val candidates = store.findCandidates(generation.id).map { candidate ->
            TestCandidateView(
                candidate = candidate,
                readiness = readiness(candidate, capabilities),
            )
        }
        return TestCandidateGenerationView(
            generation = generation,
            profileVersionActive = capabilities?.profileVersionId == generation.profileVersionId,
            candidates = candidates,
        )
    }

    /** Without an active Profile nothing can run, so every candidate reports its capability as unavailable. */
    private fun readiness(
        candidate: TestCandidate,
        capabilities: TargetCapabilitySnapshot?,
    ): TestCandidateReadiness = capabilities
        ?.let { available -> readinessResolver.resolve(candidate.binding, available) }
        ?: TestCandidateReadiness.CAPABILITY_UNAVAILABLE

    private companion object {
        const val MAX_LISTED_GENERATIONS = 50

        /** Matches the stored column widths so a long document path or workflow title cannot fail the insert. */
        const val MAX_TITLE_CHARACTERS = 200
        const val MAX_DESCRIPTION_CHARACTERS = 1_000
    }
}
