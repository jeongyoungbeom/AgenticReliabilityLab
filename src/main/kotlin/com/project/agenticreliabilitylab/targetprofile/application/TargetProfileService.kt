package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.target.domain.TargetSystemRepository
import com.project.agenticreliabilitylab.targetprofile.application.port.TargetProfileStore
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileAuditEvent
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileAuditEventType
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileSource
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileStatus
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.toRegisteredTarget
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

@Service
class TargetProfileService(
    private val validator: TargetProfileValidator,
    private val profileStore: TargetProfileStore,
    private val targetSystemRepository: TargetSystemRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun validate(definition: TargetProfileDefinition) = validator.validate(definition)

    @Transactional
    fun import(
        definition: TargetProfileDefinition,
        source: TargetProfileSource,
        actor: String,
        correlationId: String,
    ): TargetProfileVersion {
        validator.validate(definition)
        val checksum = checksum(definition)
        return profileStore.findVersionByTargetAndChecksum(definition.target.id, checksum)
            ?: importNewVersion(definition, source, actor, correlationId, checksum)
    }

    private fun importNewVersion(
        definition: TargetProfileDefinition,
        source: TargetProfileSource,
        actor: String,
        correlationId: String,
        checksum: String,
    ): TargetProfileVersion {
        val now = clock.instant()
        val version = TargetProfileVersion(
            id = identifierGenerator.next(),
            targetSystemId = definition.target.id,
            source = source,
            status = TargetProfileStatus.DRAFT,
            checksum = checksum,
            definition = definition,
            createdBy = actor,
            createdAt = now,
        )
        if (profileStore.createIfAbsent(version)) {
            profileStore.appendAuditEvent(
                TargetProfileAuditEvent(
                    id = identifierGenerator.next(),
                    targetSystemId = version.targetSystemId,
                    profileVersionId = version.id,
                    eventType = TargetProfileAuditEventType.IMPORTED,
                    actor = actor,
                    correlationId = correlationId,
                    occurredAt = now,
                ),
            )
            return version
        }
        return profileStore.findVersionByTargetAndChecksum(definition.target.id, checksum)
            ?: throw TargetProfileImportRaceException(definition.target.id)
    }

    @Transactional
    fun activate(versionId: UUID, actor: String, correlationId: String): TargetProfileVersion {
        val version = requireVersion(versionId)
        validator.validate(version.definition)
        val now = clock.instant()
        val existing = targetSystemRepository.findById(version.targetSystemId)
        targetSystemRepository.upsert(version.definition.target.toRegisteredTarget(existing?.createdAt ?: now, now))
        targetSystemRepository.lockForProfileActivation(version.targetSystemId)
        require(profileStore.activate(version.targetSystemId, version.id, actor, now)) {
            "Target Profile Version '$versionId' could not be activated"
        }
        profileStore.appendAuditEvent(
            TargetProfileAuditEvent(
                id = identifierGenerator.next(),
                targetSystemId = version.targetSystemId,
                profileVersionId = version.id,
                eventType = TargetProfileAuditEventType.ACTIVATED,
                actor = actor,
                correlationId = correlationId,
                occurredAt = now,
            ),
        )
        return requireVersion(versionId)
    }

    @Transactional
    fun seedBootstrap(definition: TargetProfileDefinition): TargetProfileVersion {
        val existing = profileStore.findActive(definition.target.id)
        if (existing != null) return existing
        val imported = import(
            definition = definition,
            source = TargetProfileSource.BOOTSTRAP,
            actor = BOOTSTRAP_ACTOR,
            correlationId = BOOTSTRAP_CORRELATION_ID,
        )
        return activate(imported.id, BOOTSTRAP_ACTOR, BOOTSTRAP_CORRELATION_ID)
    }

    fun findVersion(versionId: UUID): TargetProfileVersion = requireVersion(versionId)

    fun findActive(targetSystemId: String): TargetProfileVersion? = profileStore.findActive(targetSystemId)

    fun findAllActive(): List<TargetProfileVersion> = profileStore.findAllActive()

    private fun requireVersion(versionId: UUID): TargetProfileVersion =
        profileStore.findVersion(versionId) ?: throw TargetProfileVersionNotFoundException(versionId)

    private fun checksum(definition: TargetProfileDefinition): String =
        MessageDigest.getInstance("SHA-256")
            .digest(objectMapper.writeValueAsBytes(definition))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BOOTSTRAP_ACTOR = "SYSTEM_BOOTSTRAP"
        const val BOOTSTRAP_CORRELATION_ID = "bootstrap-target-profile"
    }
}

class TargetProfileVersionNotFoundException(id: UUID) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Target Profile Version", id)

class TargetProfileImportRaceException(targetSystemId: String) :
    IllegalStateException("Target Profile import for '$targetSystemId' could not be recovered")
