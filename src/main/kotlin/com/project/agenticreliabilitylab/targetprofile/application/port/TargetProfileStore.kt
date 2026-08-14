package com.project.agenticreliabilitylab.targetprofile.application.port

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileAuditEvent
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import java.time.Instant
import java.util.UUID

interface TargetProfileStore {
    fun findVersion(id: UUID): TargetProfileVersion?
    fun findVersionByTargetAndChecksum(targetSystemId: String, checksum: String): TargetProfileVersion?
    fun findActive(targetSystemId: String): TargetProfileVersion?
    fun findAllActive(): List<TargetProfileVersion>
    fun createIfAbsent(version: TargetProfileVersion): Boolean
    fun activate(targetSystemId: String, versionId: UUID, actor: String, activatedAt: Instant): Boolean
    fun appendAuditEvent(event: TargetProfileAuditEvent)
}
