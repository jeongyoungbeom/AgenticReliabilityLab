package com.project.agenticreliabilitylab.execution.application

import com.project.agenticreliabilitylab.execution.domain.OutboxJob
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import java.util.UUID

/** Feature-owned executor for one durable outbox job type. */
interface OutboxJobHandler {
    val type: OutboxJobType

    fun execute(aggregateId: UUID): OutboxJobExecutionResult
}

class TypedOutboxJobHandler(
    override val type: OutboxJobType,
    private val operation: (UUID) -> OutboxJobExecutionResult,
) : OutboxJobHandler {
    override fun execute(aggregateId: UUID): OutboxJobExecutionResult = operation(aggregateId)
}

class OutboxJobHandlerRegistry(handlers: List<OutboxJobHandler>) {
    private val handlersByType = handlers.associateBy(OutboxJobHandler::type)

    init {
        require(handlersByType.size == handlers.size) { "Each outbox job type must have exactly one handler" }
        require(handlersByType.keys == OutboxJobType.entries.toSet()) {
            "Every outbox job type must have a handler"
        }
    }

    fun execute(job: OutboxJob): OutboxJobExecutionResult =
        handlersByType.getValue(job.type).execute(job.aggregateId)
}
