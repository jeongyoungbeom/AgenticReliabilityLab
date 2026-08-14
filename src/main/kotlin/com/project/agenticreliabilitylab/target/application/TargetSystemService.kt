package com.project.agenticreliabilitylab.target.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetIdentity
import com.project.agenticreliabilitylab.target.domain.TargetSystem
import com.project.agenticreliabilitylab.target.domain.TargetSystemHealth
import com.project.agenticreliabilitylab.target.domain.TargetSystemRepository
import org.springframework.stereotype.Service

@Service
class TargetSystemService(
    private val repository: TargetSystemRepository,
    systems: List<TargetSystem>,
) {
    private val systemsByType = systems.associateBy { it.adapterType }

    init {
        require(systemsByType.size == systems.size) {
            "Only one TargetSystem implementation may be registered for each adapter type"
        }
    }

    fun findAll(): List<RegisteredTarget> = repository.findAll()

    fun findById(id: String): RegisteredTarget =
        repository.findById(id) ?: throw TargetSystemNotFoundException(id)

    fun identity(id: String): TargetIdentity {
        val target = findById(id)
        return systemFor(target).identity(target)
    }

    fun capabilities(id: String): Set<TargetCapability> {
        val target = findById(id)
        return systemFor(target).capabilities(target)
    }

    fun health(id: String): TargetSystemHealth {
        val target = findById(id)
        require(target.enabled) { "Target system '$id' is disabled" }
        require(TargetCapability.HEALTH in target.capabilities) {
            "Target system '$id' does not provide the HEALTH capability"
        }
        return systemFor(target).health(target)
    }

    private fun systemFor(target: RegisteredTarget): TargetSystem =
        systemsByType[target.adapterType]
            ?: error("No TargetSystem adapter is registered for type '${target.adapterType}'")
}

class TargetSystemNotFoundException(id: String) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Target system", id)
