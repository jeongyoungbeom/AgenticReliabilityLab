package com.project.agenticreliabilitylab.targetspec.application

import com.project.agenticreliabilitylab.target.application.TargetSystemService
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.targetprofile.application.port.ActiveTargetProfileVersionCatalog
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestCatalog
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestExecutionSettings
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import org.springframework.stereotype.Component
import java.util.UUID

/** Owns the Target registry and environment gates shared by batch creation and execution. */
@Component
class TargetTestBatchTargetPolicy(
    private val targetSystemService: TargetSystemService,
    private val targetTestCatalog: TargetTestCatalog,
    private val activeProfileVersions: ActiveTargetProfileVersionCatalog,
) {
    fun candidates(targetSystemId: String): List<TargetTestCandidate> {
        val target = targetSystemService.findById(targetSystemId)
        return targetTestCatalog.candidates(target.id, target.healthPath)
    }

    fun maxBatchSize(targetSystemId: String): Int = targetTestCatalog.maxBatchSize(targetSystemId)

    fun requireExecutableTarget(targetSystemId: String): RegisteredTarget {
        val target = targetSystemService.findById(targetSystemId)
        require(target.enabled) { "Target system '$targetSystemId' is disabled" }
        require(target.environment in EXECUTABLE_ENVIRONMENTS) {
            "Generic HTTP execution is enabled only for LOCAL or TEST targets"
        }
        require(TargetCapability.HTTP_API in target.capabilities) {
            "Target system '$targetSystemId' does not declare the HTTP_API capability"
        }
        return target
    }

    fun requireExecutionEnabled(targetSystemId: String): TargetTestExecutionSettings = try {
        targetTestCatalog.requireGenericExecutionEnabled(targetSystemId)
    } catch (exception: IllegalArgumentException) {
        throw TargetTestBatchRequestException(
            "TARGET_EXECUTION_DISABLED",
            exception.message ?: "Generic HTTP execution is disabled",
            exception,
        )
    }

    fun requireActiveProfileVersion(targetSystemId: String): UUID =
        activeProfileVersions.requireActiveVersionId(targetSystemId)

    fun requireBatchProfileIsActive(
        targetSystemId: String,
        profileVersionId: UUID?,
    ): TargetTestExecutionSettings = try {
        require(profileVersionId != null) {
            "Target test batch was created before Profile Version binding was available"
        }
        require(activeProfileVersions.requireActiveVersionId(targetSystemId) == profileVersionId) {
            "Target Profile Version is no longer active"
        }
        requireExecutableTarget(targetSystemId)
        requireExecutionEnabled(targetSystemId)
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("PROFILE_VERSION_INACTIVE: ${exception.message}", exception)
    }

    private companion object {
        val EXECUTABLE_ENVIRONMENTS = setOf(TargetEnvironment.LOCAL, TargetEnvironment.TEST)
    }
}
