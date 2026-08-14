package com.project.agenticreliabilitylab.execution.application

import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import java.util.UUID

/**
 * Publishes a durable job in the caller's transaction. The same type/aggregate
 * pair is idempotent, so callers may safely request recovery.
 */
interface OutboxJobPublisher {
    fun enqueue(type: OutboxJobType, aggregateId: UUID)
}
