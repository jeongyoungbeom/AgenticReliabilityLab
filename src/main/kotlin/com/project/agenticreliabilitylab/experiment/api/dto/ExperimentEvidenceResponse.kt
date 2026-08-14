package com.project.agenticreliabilitylab.experiment.api.dto

import com.project.agenticreliabilitylab.experiment.domain.ExperimentEvidenceRecord
import java.time.Instant

data class ExperimentEvidenceResponse(
    val id: String,
    val type: String,
    val schemaVersion: String,
    val source: String,
    val observedAt: Instant?,
    val completeness: String,
    val payload: String,
    val artifactReferences: String,
    val checksum: String,
) {
    companion object {
        fun from(evidence: ExperimentEvidenceRecord) = ExperimentEvidenceResponse(
            id = evidence.id.toString(), type = evidence.evidenceType, schemaVersion = evidence.schemaVersion,
            source = evidence.source, observedAt = evidence.observedAt, completeness = evidence.completeness,
            payload = evidence.payloadJson,
            artifactReferences = evidence.artifactRefsJson,
            checksum = evidence.checksum,
        )
    }
}
