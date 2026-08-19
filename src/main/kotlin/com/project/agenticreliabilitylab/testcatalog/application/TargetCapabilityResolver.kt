package com.project.agenticreliabilitylab.testcatalog.application

import com.project.agenticreliabilitylab.experiment.domain.ExperimentType
import com.project.agenticreliabilitylab.targetprofile.application.port.TargetProfileStore
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.candidates
import org.springframework.stereotype.Component

/** Reads execution capability from the active Profile Version without contacting the Target. */
@Component
class TargetCapabilityResolver(
    private val profileStore: TargetProfileStore,
) {
    fun resolve(targetSystemId: String): TargetCapabilitySnapshot {
        val version = profileStore.findActive(targetSystemId)
            ?: throw IllegalArgumentException("Target '$targetSystemId' does not have an active Target Profile")
        return snapshot(version)
    }

    fun find(targetSystemId: String): TargetCapabilitySnapshot? =
        profileStore.findActive(targetSystemId)?.let(::snapshot)

    private fun snapshot(version: TargetProfileVersion): TargetCapabilitySnapshot {
        val definition = version.definition
        val generic = definition.genericHttp
        val registered = generic
            ?.candidates(version.targetSystemId, definition.target.healthPath)
            ?.associate { candidate -> candidate.id to candidate.path }
            .orEmpty()
        return TargetCapabilitySnapshot(
            targetSystemId = version.targetSystemId,
            profileVersionId = version.id,
            genericExecutionEnabled = generic?.executionEnabled == true,
            readOnlyCandidatePathsById = registered,
            availableExperimentTypes = experimentTypes(definition.experiment?.executionEnabled == true),
        )
    }

    /** The Experiment Catalog is fixed in code; a Profile can only enable or disable what already exists. */
    private fun experimentTypes(experimentExecutionEnabled: Boolean): Set<ExperimentType> =
        if (experimentExecutionEnabled) setOf(ExperimentType.STOCK_CONCURRENCY) else emptySet()
}
