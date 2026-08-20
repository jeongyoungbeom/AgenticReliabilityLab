package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.targetprofile.application.port.TargetProfileStore
import com.project.agenticreliabilitylab.testspec.application.TestSpecExecutionProfileMapper
import com.project.agenticreliabilitylab.testspec.application.port.ActiveTestSpecExecutionProfile
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecExecutionProfileCatalog
import org.springframework.stereotype.Component

/** Reads only the active immutable Profile Version before producing Runner authority. */
@Component
class ActiveTestSpecExecutionProfileCatalog(
    private val profileStore: TargetProfileStore,
    private val mapper: TestSpecExecutionProfileMapper,
) : TestSpecExecutionProfileCatalog {
    override fun requireActive(targetSystemId: String): ActiveTestSpecExecutionProfile {
        val version = profileStore.findActive(targetSystemId)
            ?: throw IllegalArgumentException("Target '$targetSystemId' does not have an active Target Profile")
        return mapper.map(version)
    }
}
