package com.project.agenticreliabilitylab.analysis.application

import com.project.agenticreliabilitylab.analysis.domain.AnalysisVerdict
import com.project.agenticreliabilitylab.analysis.domain.FindingSeverity
import com.project.agenticreliabilitylab.analysis.domain.RecommendationPriority
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisCompletion
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisFinding
import com.project.agenticreliabilitylab.analysis.application.port.NewAnalysisRecommendation
import com.project.agenticreliabilitylab.analysis.application.port.ReliabilityAgentSettings
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/** Shared strict validator for the final analysis produced by any agent architecture. */
@Component
class AnalysisOutputValidator(
    private val objectMapper: ObjectMapper,
    private val properties: ReliabilityAgentSettings,
) {
    fun parseFinal(content: String, allowedEvidenceIds: Set<String>): AnalysisCompletion {
        if (content.toByteArray(StandardCharsets.UTF_8).size > properties.maxOutputBytes) {
            throw AnalysisOutputException("Model response exceeds the configured limit ${properties.maxOutputBytes}")
        }
        val root = try {
            objectMapper.readTree(content)
        } catch (exception: Exception) {
            throw AnalysisOutputException("Model response is not valid JSON: ${exception.javaClass.simpleName}")
        }
        if (!root.isObject) throw AnalysisOutputException("Model response must be a JSON object")
        val unexpected = root.propertyNames().toSet() - OUTPUT_FIELDS
        if (unexpected.isNotEmpty()) throw AnalysisOutputException("Model response has unsupported field '${unexpected.sorted().first()}'")
        val summary = root.requiredString("summary", 2_000)
        val verdict = root.requiredEnum("verdict", AnalysisVerdict.entries)
        val findings = root.requiredArray("findings").mapIndexed { index, node ->
            if (index >= MAX_FINDINGS) throw AnalysisOutputException("Model returned more than $MAX_FINDINGS findings")
            NewAnalysisFinding(
                severity = node.requiredEnum("severity", FindingSeverity.entries),
                title = node.requiredString("title", 300),
                rationale = node.requiredString("rationale", 4_000),
                evidenceIds = node.requiredEvidenceIds(allowedEvidenceIds),
            )
        }
        val recommendations = root.requiredArray("recommendations").mapIndexed { index, node ->
            if (index >= MAX_RECOMMENDATIONS) throw AnalysisOutputException("Model returned more than $MAX_RECOMMENDATIONS recommendations")
            NewAnalysisRecommendation(
                priority = node.requiredEnum("priority", RecommendationPriority.entries),
                title = node.requiredString("title", 300),
                recommendedAction = node.requiredString("recommendedAction", 2_000),
                rationale = node.requiredString("rationale", 4_000),
                evidenceIds = node.requiredEvidenceIds(allowedEvidenceIds),
            )
        }
        return AnalysisCompletion(summary, verdict, content, findings, recommendations)
    }

    private fun JsonNode.requiredArray(field: String): List<JsonNode> {
        val value = path(field)
        if (!value.isArray) throw AnalysisOutputException("Model response field '$field' must be an array")
        return value.toList()
    }

    private fun JsonNode.requiredString(field: String, maxLength: Int): String {
        val value = path(field).asString().trim()
        if (value.isEmpty() || value.length > maxLength) {
            throw AnalysisOutputException("Model response field '$field' must contain 1 to $maxLength characters")
        }
        return value
    }

    private fun <T : Enum<T>> JsonNode.requiredEnum(field: String, values: Iterable<T>): T {
        val value = path(field).asString()
        return values.firstOrNull { it.name == value }
            ?: throw AnalysisOutputException("Model response field '$field' has unsupported value '$value'")
    }

    private fun JsonNode.requiredEvidenceIds(allowedEvidenceIds: Set<String>): List<String> {
        val node = path("evidenceIds")
        if (!node.isArray || node.size() == 0) {
            throw AnalysisOutputException("Each finding and recommendation must reference at least one evidence ID")
        }
        val evidenceIds = node.values().map { it.asString() }
        if (evidenceIds.any { it !in allowedEvidenceIds } || evidenceIds.distinct().size != evidenceIds.size) {
            throw AnalysisOutputException("Model response references an unknown or duplicate evidence ID")
        }
        return evidenceIds
    }

    private companion object {
        const val MAX_FINDINGS = 10
        const val MAX_RECOMMENDATIONS = 10
        val OUTPUT_FIELDS = setOf("summary", "verdict", "findings", "recommendations")
    }
}
