package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.experiment.application.port.TargetExperimentProfileCatalog
import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.experiment.domain.TargetExperimentProfile
import com.project.agenticreliabilitylab.experiment.profile.TargetExperimentProfileNotFoundException
import com.project.agenticreliabilitylab.targetprofile.application.port.TargetProfileStore
import com.project.agenticreliabilitylab.targetprofile.application.port.ActiveTargetProfileVersionCatalog
import com.project.agenticreliabilitylab.targetprofile.domain.GenericHttpProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.candidates
import com.project.agenticreliabilitylab.targetprofile.domain.toDomain
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestCatalog
import com.project.agenticreliabilitylab.targetspec.application.port.TargetTestExecutionSettings
import com.project.agenticreliabilitylab.targetspec.domain.FailureInjectionCandidate
import com.project.agenticreliabilitylab.targetspec.domain.TargetTestCandidate
import org.springframework.stereotype.Component
import java.util.UUID

/** Resolves every executable capability from the single active Profile Version for a Target. */
@Component
class ActiveTargetProfileCatalog(
    private val profileStore: TargetProfileStore,
) : TargetTestCatalog, TargetExperimentProfileCatalog, ActiveTargetProfileVersionCatalog {
    override fun candidates(targetSystemId: String, healthPath: String): List<TargetTestCandidate> =
        requireGenericProfile(targetSystemId).candidates(targetSystemId, healthPath)

    override fun maxBatchSize(targetSystemId: String): Int = requireGenericProfile(targetSystemId).maxBatchSize

    override fun requireGenericExecutionEnabled(targetSystemId: String): TargetTestExecutionSettings {
        val profile = requireGenericProfile(targetSystemId)
        require(profile.executionEnabled) { "Target Spec '$targetSystemId' does not enable generic HTTP execution" }
        return TargetTestExecutionSettings(profile.hostResourceGroup, profile.requestTimeout)
    }

    override fun failureInjectionCandidates(targetSystemId: String): List<FailureInjectionCandidate> {
        val profile = requireGenericProfile(targetSystemId)
        require(profile.failureInjectionPlanningEnabled) {
            "Failure injection planning is disabled for Target '$targetSystemId'"
        }
        return profile.failureInjectionCandidates
    }

    override fun requireFailureInjectionPlanningEnabled(targetSystemId: String) {
        require(requireGenericProfile(targetSystemId).failureInjectionPlanningEnabled) {
            "Failure injection planning is disabled for Target '$targetSystemId'"
        }
    }

    override fun requireStockConcurrency(targetSystemId: String): TargetExperimentProfile =
        activeVersion(targetSystemId).definition.experiment?.toDomain(targetSystemId)
            ?: throw TargetExperimentProfileNotFoundException(targetSystemId, ExperimentType.STOCK_CONCURRENCY)

    override fun requireActiveVersionId(targetSystemId: String): UUID = activeVersion(targetSystemId).id

    private fun requireGenericProfile(targetSystemId: String): GenericHttpProfileDefinition =
        activeVersion(targetSystemId).definition.genericHttp
            ?: throw IllegalArgumentException(
                "Target Spec '$targetSystemId' is not registered in the active Target Profile",
            )

    private fun activeVersion(targetSystemId: String): TargetProfileVersion =
        profileStore.findActive(targetSystemId)
            ?: throw IllegalArgumentException("Target '$targetSystemId' does not have an active Target Profile")
}
