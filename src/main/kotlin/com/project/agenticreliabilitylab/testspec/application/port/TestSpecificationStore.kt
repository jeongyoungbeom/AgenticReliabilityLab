package com.project.agenticreliabilitylab.testspec.application.port

import com.project.agenticreliabilitylab.testspec.domain.StoredTestSpecification
import java.time.Instant
import java.util.UUID

interface TestSpecificationStore {
    fun create(specification: StoredTestSpecification)
    fun findById(id: UUID): StoredTestSpecification?
    fun findByTargetAndKey(targetSystemId: String, specKey: String): List<StoredTestSpecification>
    fun approve(id: UUID, actor: String, correlationId: String, approvedAt: Instant): Boolean
    fun supersede(id: UUID, reason: String): Boolean
}
