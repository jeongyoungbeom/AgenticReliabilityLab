package com.project.agenticreliabilitylab.api.common

/** Stable error shape returned by ARL API exception handlers. */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String? = null,
)
