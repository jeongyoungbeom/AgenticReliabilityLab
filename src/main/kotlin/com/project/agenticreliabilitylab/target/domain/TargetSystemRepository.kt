package com.project.agenticreliabilitylab.target.domain

interface TargetSystemRepository {
    fun findAll(): List<RegisteredTarget>

    fun findById(id: String): RegisteredTarget?

    fun upsert(target: RegisteredTarget)

    /** Serializes Target Profile activation for one Target inside the calling transaction. */
    fun lockForProfileActivation(id: String)
}
