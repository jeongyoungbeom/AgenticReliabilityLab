package com.project.agenticreliabilitylab.analysis.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("arl.analysis-models")
class ReliabilityAnalysisModelsProperties {
    var registrations: List<Registration> = listOf(
        Registration("GPT_OSS", "gpt-oss:20b"),
        Registration("QWEN", "qwen3:4b"),
    )

    data class Registration(
        var key: String = "",
        var modelId: String = "",
    )
}
