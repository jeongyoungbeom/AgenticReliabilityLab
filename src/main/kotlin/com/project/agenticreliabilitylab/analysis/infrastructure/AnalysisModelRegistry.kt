package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.AnalysisRequestException
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisModelCatalog
import com.project.agenticreliabilitylab.analysis.application.port.AnalysisModelDefinition
import org.springframework.stereotype.Component

/**
 * Whitelists model names that an API caller may select.  A caller never sends an
 * arbitrary Ollama model name to the transport layer.
 */
@Component
class AnalysisModelRegistry(properties: ReliabilityAnalysisModelsProperties) : AnalysisModelCatalog {
    private val models: Map<String, AnalysisModelDefinition>

    init {
        require(properties.registrations.isNotEmpty()) {
            "arl.analysis-models.registrations must contain at least one model"
        }
        val normalized = properties.registrations.map {
            val key = it.key.trim().uppercase()
            require(KEY_PATTERN.matches(key)) {
                "Analysis model key '${it.key}' must contain 2 to 40 uppercase letters, digits, or underscores"
            }
            val modelId = it.modelId.trim()
            require(MODEL_ID_PATTERN.matches(modelId)) {
                "Analysis model id for '$key' contains unsupported characters"
            }
            AnalysisModelDefinition(key, modelId)
        }
        require(normalized.map { it.key }.distinct().size == normalized.size) {
            "arl.analysis-models.registrations contains duplicate keys"
        }
        models = normalized.associateBy { it.key }
    }

    override fun resolve(requestedKey: String?, defaultKey: String): AnalysisModelDefinition {
        val key = (requestedKey ?: defaultKey).trim().uppercase()
        return models[key] ?: throw AnalysisRequestException(
            "MODEL_NOT_REGISTERED",
            "Analysis model '$key' is not registered",
        )
    }

    override fun resolveRequired(key: String): AnalysisModelDefinition = resolve(key, key)

    companion object {
        private val KEY_PATTERN = Regex("[A-Z][A-Z0-9_]{1,39}")
        private val MODEL_ID_PATTERN = Regex("[A-Za-z0-9._:/-]{1,200}")
    }
}
