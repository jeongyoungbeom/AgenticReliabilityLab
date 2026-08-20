package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.application.CreateTestSpecification
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class CreateTestSpecificationRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val targetSystemId: String,
    val source: SpecSource,
    val document: JsonNode,
) {
    fun toCommand(objectMapper: ObjectMapper): CreateTestSpecification {
        require(document.isObject) { "document must be a JSON object" }
        return CreateTestSpecification(
            targetSystemId = targetSystemId,
            source = source,
            documentJson = objectMapper.writeValueAsString(document),
        )
    }
}

data class ApproveTestSpecificationRequest(
    @field:NotBlank
    val confirmation: String,
)
