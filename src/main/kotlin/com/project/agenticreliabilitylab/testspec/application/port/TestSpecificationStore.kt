package com.project.agenticreliabilitylab.testspec.application.port

import com.project.agenticreliabilitylab.testspec.domain.StoredTestSpecification
import java.time.Instant
import java.util.UUID

interface TestSpecificationStore {
    fun create(specification: StoredTestSpecification)
    fun findById(id: UUID): StoredTestSpecification?
    fun findByTargetAndKey(targetSystemId: String, specKey: String): List<StoredTestSpecification>

    /** All currently APPROVED specifications for [targetSystemId], across every specKey and version. */
    fun findApprovedByTarget(targetSystemId: String): List<StoredTestSpecification>
    fun approve(id: UUID, actor: String, correlationId: String, approvedAt: Instant): Boolean

    /**
     * Moves [id] onto a new Profile Version without changing its version, status or approval.
     *
     * Used only after the stored document has been revalidated against the new Profile's capabilities and still
     * passes - a Profile Version bump that changes nothing referenced must not force a re-approval. The write is a
     * compare-and-swap on [expectedProfileVersionId] (the Version [id] was read as being on) so two concurrent
     * reconciliations of the same row - or a reconciliation racing a [supersede] - cannot silently clobber each
     * other; a `false` result means [id] had already moved on and the caller should re-read it.
     */
    fun reviseProfileVersion(id: UUID, expectedProfileVersionId: UUID, profileVersionId: UUID): Boolean
    fun supersede(id: UUID, reason: String): Boolean
}
