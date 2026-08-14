package com.project.agenticreliabilitylab.target.infrastructure

import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("arl.targets")
data class TargetRegistrationProperties(
    val registrations: List<TargetRegistration> = emptyList(),
)

data class TargetRegistration(
    val id: String,
    val name: String,
    val adapterType: String,
    val environment: TargetEnvironment,
    val baseUrl: String,
    val allowedOrigin: String,
    val allowedCidrs: Set<String> = emptySet(),
    val healthPath: String,
    val sourceRepository: String,
    val identityVerification: IdentityVerificationStatus,
    val capabilities: Set<TargetCapability> = emptySet(),
    val enabled: Boolean = true,
)

@ConfigurationProperties("arl.http")
data class TargetHttpProperties(
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val readTimeout: Duration = Duration.ofSeconds(5),
)
