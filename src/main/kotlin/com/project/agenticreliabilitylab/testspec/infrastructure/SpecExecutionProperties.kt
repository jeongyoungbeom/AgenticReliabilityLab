package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.SpecExecutionSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties("arl.test-spec")
class SpecExecutionProperties : SpecExecutionSettings {
    override var requestTimeout: Duration = Duration.ofSeconds(10)
    override var maxObservationWait: Duration = Duration.ofSeconds(60)
}
