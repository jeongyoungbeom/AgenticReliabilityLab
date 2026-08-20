package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.testspec.application.port.SpecExecutionSettings
import com.project.agenticreliabilitylab.testspec.domain.ReadTiming
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Reads one value from the Target, waiting for it to settle when it has to.
 *
 * Both an observation and a reset check need exactly this, and they need it to behave identically: a reset that
 * "verified" on a value still propagating is the same lie as an observation that read too early, and it is the
 * more dangerous of the two because it unblocks the next run.
 */
@Component
class SpecValueReader(
    private val caller: SpecHttpCaller,
    private val evaluator: ResponsePathEvaluator,
    private val settings: SpecExecutionSettings,
) {
    @Suppress("ReturnCount", "LongParameterList") // Settling, timing out and never reading are distinct endings.
    fun read(
        target: RegisteredTarget,
        call: SpecHttpCall,
        expression: String,
        readTiming: ReadTiming,
        bindings: Map<String, String>,
        runId: String,
        label: String,
    ): ObservedValue {
        val required = readTiming.rule.consecutiveReads()
        val deadline = System.nanoTime() + effectiveWait(readTiming.maxWait).toNanos()
        val interval = readTiming.interval.coerceAtLeast(MINIMUM_INTERVAL)

        var previous: Any? = null
        var repeats = 0
        var lastFailure: String? = null
        while (true) {
            val attempt = runCatching { readOnce(target, call, expression, bindings, runId, label) }
            attempt.onSuccess { value ->
                repeats = if (repeats > 0 && value == previous) repeats + 1 else 1
                previous = value
                if (repeats >= required) return ObservedValue.of(value)
            }.onFailure { failure -> lastFailure = failure.message ?: failure.javaClass.simpleName }
            if (System.nanoTime() >= deadline) break
            Thread.sleep(interval.toMillis())
        }
        return ObservedValue.missing(
            lastFailure ?: "value did not settle within ${readTiming.maxWait.toMillis()}ms",
        )
    }

    private fun readOnce(
        target: RegisteredTarget,
        call: SpecHttpCall,
        expression: String,
        bindings: Map<String, String>,
        runId: String,
        label: String,
    ): Any {
        val response = caller.send(target, call, bindings, FIRST_REQUEST, runId)
        if (!response.delivered || response.statusCode !in SUCCESS_STATUS) {
            throw ObservationExpressionException(
                "read of '$label' returned ${response.failure ?: "HTTP ${response.statusCode}"}",
            )
        }
        return evaluator.evaluate(expression, evaluator.responseScope(response))
    }

    /** The Runner's own ceiling wins. A specification cannot hold a Target for longer than an operator allowed. */
    private fun effectiveWait(requested: Duration): Duration = minOf(requested, settings.maxObservationWait)

    private fun StabilityRule.consecutiveReads(): Int = when (this) {
        StabilityRule.IMMEDIATE -> 1
        StabilityRule.TWO_CONSECUTIVE_EQUAL -> 2
        StabilityRule.THREE_CONSECUTIVE_EQUAL -> REQUIRED_THREE_EQUAL_READS
    }

    private companion object {
        const val FIRST_REQUEST = 1
        const val MINIMUM_INTERVAL_MILLIS = 50L
        const val REQUIRED_THREE_EQUAL_READS = 3
        val SUCCESS_STATUS = 200..299
        val MINIMUM_INTERVAL: Duration = Duration.ofMillis(MINIMUM_INTERVAL_MILLIS)
    }
}
