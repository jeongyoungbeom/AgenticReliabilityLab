package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.port.MultiAgentSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("arl.multi-agent")
class MultiAgentProperties : MultiAgentSettings {
    override var enabled: Boolean = true
    override var promptVersion: String = "multi-reliability-agent-v1"
    override var maxStepOutputBytes: Int = 65_536
}
