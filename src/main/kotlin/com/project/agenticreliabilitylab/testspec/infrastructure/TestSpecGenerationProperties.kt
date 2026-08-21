package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.TestSpecGenerationSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("arl.test-spec-generation")
class TestSpecGenerationProperties : TestSpecGenerationSettings {
    override var enabled: Boolean = true
    override var promptVersion: String = "test-spec-generation-v1"
    override var maxOutputBytes: Int = 131_072
    override var maxCandidates: Int = 5
    override var maxOpenApiDocumentBytes: Int = 1_048_576
    override var maxInputBytes: Int = 1_572_864
}
