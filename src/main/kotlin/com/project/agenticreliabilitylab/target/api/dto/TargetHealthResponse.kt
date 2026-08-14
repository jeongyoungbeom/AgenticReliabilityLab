package com.project.agenticreliabilitylab.target.api.dto

import com.project.agenticreliabilitylab.target.domain.TargetHealthStatus
import com.project.agenticreliabilitylab.target.domain.TargetSystemHealth
import java.time.Instant

data class TargetHealthResponse(
    val targetId: String,
    val status: TargetHealthStatus,
    val httpStatus: Int?,
    val latencyMs: Long,
    val observedAt: Instant,
    val message: String,
) {
    companion object {
        fun from(health: TargetSystemHealth) = TargetHealthResponse(
            targetId = health.targetId, status = health.status, httpStatus = health.httpStatus,
            latencyMs = health.latencyMs, observedAt = health.observedAt, message = health.message,
        )
    }
}
