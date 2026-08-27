package com.project.agenticreliabilitylab.targetcredential.api.dto

/** How much the browser's credential session actually held when it ended. */
data class EndTargetCredentialSessionResponse(
    val clearedTargetCount: Int,
)
