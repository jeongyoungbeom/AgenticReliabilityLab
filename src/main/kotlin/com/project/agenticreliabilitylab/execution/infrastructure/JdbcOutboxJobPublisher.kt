package com.project.agenticreliabilitylab.execution.infrastructure

import com.project.agenticreliabilitylab.execution.application.OutboxJobPublisher
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class JdbcOutboxJobPublisher(
    private val repository: JdbcOutboxJobRepository,
    private val clock: Clock,
) : OutboxJobPublisher {

    override fun enqueue(type: OutboxJobType, aggregateId: UUID) {
        repository.enqueue(type, aggregateId, clock.instant())
    }
}
