package com.project.agenticreliabilitylab.testplan.application.port

import com.project.agenticreliabilitylab.testplan.domain.TestPlan
import com.project.agenticreliabilitylab.testplan.domain.TestPlanExecutionReference
import com.project.agenticreliabilitylab.testplan.domain.TestPlanItem
import com.project.agenticreliabilitylab.testplan.domain.TestPlanStatus
import java.time.Instant
import java.util.UUID

interface TestPlanStore {
    fun create(plan: TestPlan, items: List<TestPlanItem>)
    fun findById(id: UUID): TestPlan?
    fun findByTargetAndIdempotencyKey(targetSystemId: String, idempotencyKey: String): TestPlan?
    fun findByTarget(targetSystemId: String, limit: Int): List<TestPlan>
    fun findItems(planId: UUID): List<TestPlanItem>
    fun findExecutionReferences(planId: UUID): List<TestPlanExecutionReference>
    fun approve(planId: UUID, actor: String, correlationId: String, approvedAt: Instant): Boolean
    fun markDispatched(planId: UUID, dispatchedAt: Instant): Boolean
    fun markTerminal(planId: UUID, status: TestPlanStatus, reason: String): Boolean
    fun appendExecutionReference(reference: TestPlanExecutionReference)
}
