package com.project.agenticreliabilitylab.access

import com.project.agenticreliabilitylab.common.AccessDeniedException
import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@ConfigurationProperties("arl.access")
data class OperatorAccessProperties(
    val mode: OperatorAccessMode = OperatorAccessMode.SECURED,
    val viewerToken: String = "",
    val profileEditorToken: String = "",
    val executorToken: String = "",
)

enum class OperatorAccessMode {
    LOCAL,
    SECURED,
}

/** Small self-hosted access boundary. Phase 10.7 confines LOCAL mode to loopback and configures its token. */
@Component
class OperatorAccessService(
    private val properties: OperatorAccessProperties,
) {
    @PostConstruct
    fun validateConfiguration() {
        if (properties.mode == OperatorAccessMode.SECURED) {
            require(properties.viewerToken.isNotBlank()) { "SECURED access mode requires arl.access.viewer-token" }
            require(properties.profileEditorToken.isNotBlank()) {
                "SECURED access mode requires arl.access.profile-editor-token"
            }
            require(properties.executorToken.isNotBlank()) { "SECURED access mode requires arl.access.executor-token" }
        }
    }

    fun requireProfileEditor(authorization: String?): String = requireRole(
        authorization,
        listOf(AccessGrant(PROFILE_EDITOR_ACTOR, properties.profileEditorToken)),
        "Profile editor authorization is required",
    )

    fun requireExecutor(authorization: String?): String = requireRole(
        authorization,
        listOf(AccessGrant(EXECUTOR_ACTOR, properties.executorToken)),
        "Executor authorization is required",
    )

    fun requireViewer(authorization: String?): String = requireRole(
        authorization,
        listOf(
            AccessGrant(VIEWER_ACTOR, properties.viewerToken),
            AccessGrant(PROFILE_EDITOR_ACTOR, properties.profileEditorToken),
            AccessGrant(EXECUTOR_ACTOR, properties.executorToken),
        ),
        "Viewer authorization is required",
    )

    private fun requireRole(
        authorization: String?,
        grants: List<AccessGrant>,
        message: String,
    ): String {
        if (properties.mode == OperatorAccessMode.LOCAL) return LOCAL_OPERATOR_ACTOR
        val suppliedToken = authorization
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            .orEmpty()
        return grants.firstOrNull { grant ->
            grant.token.isNotBlank() && tokensMatch(suppliedToken, grant.token)
        }?.actor ?: throw AccessDeniedException(message)
    }

    private fun tokensMatch(suppliedToken: String, configuredToken: String): Boolean =
        MessageDigest.isEqual(
            suppliedToken.toByteArray(StandardCharsets.UTF_8),
            configuredToken.toByteArray(StandardCharsets.UTF_8),
        )

    private companion object {
        const val BEARER_PREFIX = "Bearer "
        const val VIEWER_ACTOR = "VIEWER"
        const val PROFILE_EDITOR_ACTOR = "PROFILE_EDITOR"
        const val EXECUTOR_ACTOR = "EXECUTOR"
        const val LOCAL_OPERATOR_ACTOR = "LOCAL_OPERATOR"
    }

    private data class AccessGrant(val actor: String, val token: String)
}
