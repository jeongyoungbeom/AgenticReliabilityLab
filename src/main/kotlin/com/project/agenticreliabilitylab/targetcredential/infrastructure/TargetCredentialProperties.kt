package com.project.agenticreliabilitylab.targetcredential.infrastructure

import com.project.agenticreliabilitylab.targetcredential.application.port.TargetCredentialSettings
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties("arl.target-credential")
class TargetCredentialProperties : TargetCredentialSettings {
    override var cookieSecure: Boolean = false
    override var idleTimeout: Duration = Duration.ofHours(8)
    override var maxSessions: Int = 100
}
