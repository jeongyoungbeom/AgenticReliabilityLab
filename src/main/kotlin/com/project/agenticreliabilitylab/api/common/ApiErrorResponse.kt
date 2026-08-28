package com.project.agenticreliabilitylab.api.common

import com.project.agenticreliabilitylab.diagnosis.FailureDiagnosis

/** Stable error shape returned by ARL API exception handlers. */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String? = null,
    val diagnosis: FailureDiagnosis? = null,
)
