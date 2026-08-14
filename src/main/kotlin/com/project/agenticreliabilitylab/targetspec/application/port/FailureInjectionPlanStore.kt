package com.project.agenticreliabilitylab.targetspec.application.port

import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanItemRecord
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionPlanRecord
import java.time.Instant
import java.util.UUID

/** Persistence boundary for advisory-only failure-injection planning. */
interface FailureInjectionPlanStore {
    fun create(command: NewFailureInjectionPlan)
    fun findById(id: UUID): FailureInjectionPlanRecord?
    fun findByTargetAndIdempotencyKey(targetId: String, key: String): FailureInjectionPlanRecord?
    fun findItems(planId: UUID): List<FailureInjectionPlanItemRecord>
    fun approve(planId: UUID, actor: String, correlationId: String, now: Instant): Boolean
}

data class NewFailureInjectionPlanCandidate(val id: UUID, val candidate: FailureInjectionCandidate)
data class NewFailureInjectionPlan(
    val id: UUID,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val idempotencyKey: String,
    val requestHash: String,
    val candidates: List<NewFailureInjectionPlanCandidate>,
    val createdAt: Instant,
)
