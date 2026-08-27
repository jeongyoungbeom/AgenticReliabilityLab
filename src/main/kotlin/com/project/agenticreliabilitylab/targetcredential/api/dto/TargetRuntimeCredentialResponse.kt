package com.project.agenticreliabilitylab.targetcredential.api.dto

import com.project.agenticreliabilitylab.targetcredential.application.TargetRuntimeCredentialStatus

/**
 * What the browser is allowed to know about a Target credential session.
 *
 * The session id itself is deliberately absent: it travels only in the HttpOnly cookie, so page scripts cannot read
 * it. `sessionActive` is enough for the UI to decide what to render.
 */
data class TargetRuntimeCredentialResponse(
    val targetSystemId: String,
    val storedRoles: Set<String>,
    val sessionActive: Boolean,
) {
    companion object {
        fun from(status: TargetRuntimeCredentialStatus) = TargetRuntimeCredentialResponse(
            targetSystemId = status.targetSystemId,
            storedRoles = status.storedRoles,
            sessionActive = status.storedRoles.isNotEmpty(),
        )
    }
}
