package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.FollowUpSuggestionSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("arl.follow-up-suggestions")
class FollowUpSuggestionProperties : FollowUpSuggestionSettings {
    override var enabled: Boolean = true
    override var promptVersion: String = "follow-up-test-suggestion-v1"
    override var maxOutputBytes: Int = 65_536
    override var maxSuggestions: Int = 5
    override var maxCandidateCatalogCount: Int = 50
    override var maxCandidateCatalogBytes: Int = 65_536
    override var maxInputBytes: Int = 262_144
}
