package com.project.agenticreliabilitylab.targetintelligence.api.dto

import com.project.agenticreliabilitylab.targetintelligence.application.TargetKnowledgeSnapshotView
import java.time.Instant
import java.util.UUID

/**
 * Read model for one Knowledge Snapshot.
 *
 * [profileVersionActive] is computed per request rather than stored, so a Snapshot built for a superseded Profile
 * Version is always reported as unusable for new planning.
 */
data class TargetKnowledgeSnapshotResponse(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val profileVersionActive: Boolean,
    val checksum: String,
    val extractionVersion: String,
    val confirmed: Boolean,
    val confirmedBy: String?,
    val confirmedAt: Instant?,
    val createdBy: String,
    val createdAt: Instant,
    val sources: List<KnowledgeSourceDocumentResponse>,
    val operations: List<ExtractedOperationResponse>,
    val workflows: List<ExtractedWorkflowResponse>,
    val domainHypotheses: List<DomainHypothesisResponse>,
    val invariants: List<ExtractedInvariantResponse>,
    val riskSignals: List<RiskSignalResponse>,
    val warnings: List<ExtractionWarningResponse>,
) {
    companion object {
        fun from(view: TargetKnowledgeSnapshotView): TargetKnowledgeSnapshotResponse {
            val snapshot = view.snapshot
            val content = snapshot.content
            return TargetKnowledgeSnapshotResponse(
                id = snapshot.id,
                targetSystemId = snapshot.targetSystemId,
                profileVersionId = snapshot.profileVersionId,
                profileVersionActive = view.profileVersionActive,
                checksum = snapshot.checksum,
                extractionVersion = snapshot.extractionVersion,
                confirmed = snapshot.confirmed,
                confirmedBy = snapshot.confirmedBy,
                confirmedAt = snapshot.confirmedAt,
                createdBy = snapshot.createdBy,
                createdAt = snapshot.createdAt,
                sources = content.sources.map(KnowledgeSourceDocumentResponse::from),
                operations = content.operations.map(ExtractedOperationResponse::from),
                workflows = content.workflows.map(ExtractedWorkflowResponse::from),
                domainHypotheses = content.domainHypotheses.map(DomainHypothesisResponse::from),
                invariants = content.invariants.map(ExtractedInvariantResponse::from),
                riskSignals = content.riskSignals.map(RiskSignalResponse::from),
                warnings = content.warnings.map(ExtractionWarningResponse::from),
            )
        }
    }
}
