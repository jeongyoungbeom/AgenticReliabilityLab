package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.TestSpecMisjudgmentSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("arl.test-spec-misjudgment")
class TestSpecMisjudgmentProperties : TestSpecMisjudgmentSettings {
    override var enabled: Boolean = true
    override var promptVersion: String = "test-spec-misjudgment-v1"
    override var maxOutputBytes: Int = 65_536
}
