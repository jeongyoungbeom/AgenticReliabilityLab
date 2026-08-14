package com.project.agenticreliabilitylab.execution.application

import java.time.Duration

/**
 * The result of one bounded outbox-worker turn.
 *
 * A deferred result is not a failure: it releases the worker slot and returns
 * the durable job to PENDING without consuming a retry attempt.
 */
sealed interface OutboxJobExecutionResult {
    data object Completed : OutboxJobExecutionResult

    data class Deferred(
        val delay: Duration,
    ) : OutboxJobExecutionResult {
        init {
            require(!delay.isNegative) { "Deferred outbox delay must not be negative" }
        }
    }
}
