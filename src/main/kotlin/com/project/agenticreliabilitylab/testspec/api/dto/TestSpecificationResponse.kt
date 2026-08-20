package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.application.TestSpecificationView
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.TestSpecificationStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

data class TestSpecificationResponse(
    val id: UUID,
    val targetSystemId: String,
    val specKey: String,
    val version: Int,
    val title: String,
    val profileVersionId: UUID,
    val profileVersionActive: Boolean,
    val source: SpecSource,
    val category: SpecCategory,
    val risk: SpecRisk,
    val status: TestSpecificationStatus,
    val document: JsonNode,
    val checksum: String,
    val requiredConfirmation: String,
    val unfoundedThresholds: List<String>,
    val createdBy: String,
    val createdAt: Instant,
    val approvedBy: String?,
    val approvedAt: Instant?,
    val terminalReason: String?,
) {
    companion object {
        fun from(view: TestSpecificationView, objectMapper: ObjectMapper): TestSpecificationResponse {
            val specification = view.specification
            return TestSpecificationResponse(
                id = specification.id,
                targetSystemId = specification.targetSystemId,
                specKey = specification.specKey,
                version = specification.version,
                title = specification.title,
                profileVersionId = specification.profileVersionId,
                profileVersionActive = view.profileVersionActive,
                source = specification.source,
                category = specification.category,
                risk = specification.risk,
                status = specification.status,
                document = objectMapper.readTree(specification.documentJson),
                checksum = specification.checksum,
                requiredConfirmation = view.requiredConfirmation,
                unfoundedThresholds = view.unfoundedThresholds,
                createdBy = specification.createdBy,
                createdAt = specification.createdAt,
                approvedBy = specification.approvedBy,
                approvedAt = specification.approvedAt,
                terminalReason = specification.terminalReason,
            )
        }
    }
}
