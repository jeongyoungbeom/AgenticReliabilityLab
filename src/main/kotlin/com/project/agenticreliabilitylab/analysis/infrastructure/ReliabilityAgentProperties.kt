package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.ReliabilityAgentSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("arl.agent")
class ReliabilityAgentProperties : ReliabilityAgentSettings {
    override var enabled: Boolean = true
    override var defaultModelKey: String = "GPT_OSS"
    override var promptVersion: String = "single-reliability-agent-v1"
    override var maxEvidenceCount: Int = 50
    override var maxEvidenceBytes: Int = 131_072
    override var maxOutputBytes: Int = 65_536
}
