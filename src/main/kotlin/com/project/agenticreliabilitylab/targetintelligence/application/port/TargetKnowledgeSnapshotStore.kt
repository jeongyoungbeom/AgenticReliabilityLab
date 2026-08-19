package com.project.agenticreliabilitylab.targetintelligence.application.port

import com.project.agenticreliabilitylab.targetintelligence.domain.TargetKnowledgeSnapshot
import java.time.Instant
import java.util.UUID

/**
 * Persistence boundary for immutable Knowledge Snapshots.
 *
 * Extracted content is written once and never rewritten. Only the user confirmation fields may transition, and only
 * from unset to set, so earlier Snapshots stay available for audit and reproduction.
 */
interface TargetKnowledgeSnapshotStore {
    fun createIfAbsent(snapshot: TargetKnowledgeSnapshot): Boolean
    fun findById(id: UUID): TargetKnowledgeSnapshot?
    fun findByProfileVersionAndChecksum(profileVersionId: UUID, checksum: String): TargetKnowledgeSnapshot?
    fun findByTarget(targetSystemId: String, limit: Int): List<TargetKnowledgeSnapshot>
    fun confirm(id: UUID, actor: String, correlationId: String, confirmedAt: Instant): Boolean
}
