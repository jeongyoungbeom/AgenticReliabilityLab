package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.targetcredential.application.RuntimeTargetCredentialStore
import com.project.agenticreliabilitylab.testspec.application.port.SpecAuthProvider
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/** Prefers the short-lived UI value and falls back to the deployment secret reference resolved from the environment. */
@Primary
@Component
class RuntimeAwareSpecAuthProvider(
    private val runtimeCredentials: RuntimeTargetCredentialStore,
    private val environmentCredentials: EnvironmentSpecAuthProvider,
) : SpecAuthProvider {
    override fun headersFor(targetSystemId: String, authProfile: String): Map<String, String> =
        environmentCredentials.headersFor(targetSystemId, authProfile)

    override fun headersFor(
        targetSystemId: String,
        authProfile: String,
        credentialSessionId: String?,
    ): Map<String, String> = runtimeCredentials.headersFor(targetSystemId, credentialSessionId, authProfile)
            ?: environmentCredentials.headersFor(targetSystemId, authProfile)
}
