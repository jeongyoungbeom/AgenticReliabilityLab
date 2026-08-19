package com.project.agenticreliabilitylab.targetintelligence.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.common.ResourceNotFoundException
import com.project.agenticreliabilitylab.targetintelligence.application.port.TargetKnowledgeSnapshotStore
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeContent
import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeSnapshot
import com.project.agenticreliabilitylab.targetprofile.application.port.ActiveTargetProfileVersionCatalog
import com.project.agenticreliabilitylab.targetprofile.application.port.TargetProfileStore
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Builds and reads immutable Target Knowledge Snapshots.
 *
 * Phase 11 never contacts the Target: the snapshot is produced from supplied documents only. It is bound to the Profile
 * Version that was active at creation time.
 *
 * Intake is deliberately made idempotent by content hash rather than by a client `Idempotency-Key` header, unlike the
 * Batch and Plan endpoints. Those resources may legitimately be created twice from the same selection, because each one
 * carries its own approval and execution, so only a client-chosen key can separate a genuine second request from a
 * network retry. A Knowledge Snapshot is derived, immutable knowledge instead: two identical Snapshots under the same
 * Profile Version and extraction version would carry no additional information and would only split the Snapshot ID
 * that later candidates reference. Retry safety still holds, because the response is a function of the request body,
 * the active Profile Version and the extraction version alone.
 */
@Service
class TargetKnowledgeSnapshotService(
    private val store: TargetKnowledgeSnapshotStore,
    private val openApiExtractor: OpenApiKnowledgeExtractor,
    private val readmeExtractor: ReadmeKnowledgeExtractor,
    private val briefExtractor: TargetBriefKnowledgeExtractor,
    private val merger: KnowledgeFragmentMerger,
    private val activeProfileVersions: ActiveTargetProfileVersionCatalog,
    private val profileStore: TargetProfileStore,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
) {
    @Suppress("ReturnCount") // Returning an existing Snapshot on retry or race is a deliberate early exit.
    fun create(
        command: CreateTargetKnowledgeSnapshot,
        actor: String,
        correlationId: String,
    ): TargetKnowledgeSnapshotView {
        require(command.hasAnyInput) { "At least one of openApiDocument, readmeDocument or brief is required" }
        val profileVersionId = activeProfileVersions.requireActiveVersionId(command.targetSystemId)
        val content = merger.merge(extract(command))
        val checksum = checksum(content)
        store.findByProfileVersionAndChecksum(profileVersionId, checksum)?.let { existing ->
            return view(existing)
        }
        val snapshot = TargetKnowledgeSnapshot(
            id = identifiers.next(),
            targetSystemId = command.targetSystemId,
            profileVersionId = profileVersionId,
            checksum = checksum,
            extractionVersion = KNOWLEDGE_EXTRACTION_VERSION,
            content = content,
            createdBy = actor,
            createdCorrelationId = correlationId,
            createdAt = clock.instant(),
        )
        if (!store.createIfAbsent(snapshot)) {
            return view(requireStored(profileVersionId, checksum))
        }
        return view(snapshot)
    }

    fun find(id: UUID): TargetKnowledgeSnapshotView = view(
        store.findById(id) ?: throw ResourceNotFoundException("TargetKnowledgeSnapshot", id),
    )

    fun findByTarget(targetSystemId: String): List<TargetKnowledgeSnapshotView> =
        store.findByTarget(targetSystemId, MAX_LISTED_SNAPSHOTS).map(::view)

    /** Records that a user reviewed the extracted evidence and assumptions. Confirming twice is not an error. */
    fun confirm(id: UUID, actor: String, correlationId: String): TargetKnowledgeSnapshotView {
        val snapshot = store.findById(id) ?: throw ResourceNotFoundException("TargetKnowledgeSnapshot", id)
        if (snapshot.confirmed) return view(snapshot)
        if (activeProfileVersions.requireActiveVersionId(snapshot.targetSystemId) != snapshot.profileVersionId) {
            throw ClientRequestException(
                code = "KNOWLEDGE_SNAPSHOT_PROFILE_VERSION_INACTIVE",
                message = "Knowledge Snapshot '$id' was built for a Profile Version that is no longer active",
            )
        }
        store.confirm(id, actor, correlationId, clock.instant())
        return find(id)
    }

    private fun extract(command: CreateTargetKnowledgeSnapshot): List<KnowledgeFragment> = buildList {
        command.openApiDocument?.takeIf(String::isNotBlank)?.let { document ->
            add(openApiExtractor.extract(document))
        }
        command.readmeDocument?.takeIf(String::isNotBlank)?.let { document ->
            add(readmeExtractor.extract(document))
        }
        command.brief?.takeIf { brief -> !brief.empty }?.let { brief ->
            add(briefExtractor.extract(brief))
        }
    }

    /**
     * Derives the Snapshot checksum from the supplied document hashes and the extraction version.
     *
     * Identical documents therefore stay idempotent across retries, while bumping [KNOWLEDGE_EXTRACTION_VERSION] after
     * an extractor change produces a new Snapshot for those same documents rather than returning the stale result.
     */
    private fun checksum(content: TargetKnowledgeContent): String = sha256Hex(
        content.sources
            .sortedBy { source -> source.type.name }
            .joinToString(separator = "|", prefix = "$KNOWLEDGE_EXTRACTION_VERSION|") { source ->
                "${source.type.name}:${source.checksum}"
            },
    )

    private fun requireStored(profileVersionId: UUID, checksum: String): TargetKnowledgeSnapshot =
        store.findByProfileVersionAndChecksum(profileVersionId, checksum)
            ?: throw ClientRequestException(
                code = "KNOWLEDGE_SNAPSHOT_CONFLICT",
                message = "Knowledge Snapshot creation conflicted; retry the request",
            )

    private fun view(snapshot: TargetKnowledgeSnapshot): TargetKnowledgeSnapshotView = TargetKnowledgeSnapshotView(
        snapshot = snapshot,
        profileVersionActive = profileStore.findActive(snapshot.targetSystemId)?.id == snapshot.profileVersionId,
    )

    private companion object {
        const val MAX_LISTED_SNAPSHOTS = 50
    }
}
