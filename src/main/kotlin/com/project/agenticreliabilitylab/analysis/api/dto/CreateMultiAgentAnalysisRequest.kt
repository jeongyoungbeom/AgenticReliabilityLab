package com.project.agenticreliabilitylab.analysis.api.dto

import com.project.agenticreliabilitylab.analysis.application.model.MultiAgentModelSelection
import com.project.agenticreliabilitylab.analysis.domain.MultiAgentRole

data class CreateMultiAgentAnalysisRequest(
    val modelKey: String? = null,
    val roleModelKeys: Map<String, String>? = null,
) {
    fun toSelection(): MultiAgentModelSelection {
        if (roleModelKeys == null) return MultiAgentModelSelection(modelKey = modelKey)
        val normalized = roleModelKeys.mapKeys { (role, _) ->
            MultiAgentRole.entries.firstOrNull { it.name == role.trim().uppercase() }
                ?: throw IllegalArgumentException("roleModelKeys contains unsupported role '$role'")
        }
        require(normalized.size == roleModelKeys.size) {
            "roleModelKeys must not contain the same role more than once"
        }
        return MultiAgentModelSelection(modelKey = modelKey, roleModelKeys = normalized)
    }
}
