package com.project.agenticreliabilitylab.testspec.application.port

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.testspec.application.DeclaredObservationSource
import com.project.agenticreliabilitylab.testspec.domain.ObservationWindow
import java.time.Duration

/** Reads Profile-approved fields from one source sample without deciding any invariant. */
interface DeclaredObservationSourceClient {
    fun read(request: DeclaredObservationRequest): Map<String, DeclaredObservationRead>
}

/**
 * One sample of one source.
 *
 * [window] bounds the time a source may be asked about. Snapshot sources ignore it because their answer is
 * whatever is true now, but a trace store answers about the past, and without a bound it would return traces from
 * an earlier trial or another developer's request and let them decide this trial's verdict.
 */
data class DeclaredObservationRequest(
    val target: RegisteredTarget,
    val source: DeclaredObservationSource,
    val fields: Set<String>,
    val runId: String,
    val trialScope: String,
    val timeout: Duration,
    val window: ObservationWindow?,
)

/** A failed read is data, not an execution failure: only invariants using this field become unjudgeable. */
data class DeclaredObservationRead(
    val present: Boolean,
    val value: Any?,
    val failure: String?,
) {
    companion object {
        fun observed(value: Any): DeclaredObservationRead = DeclaredObservationRead(true, value, null)

        fun missing(reason: String): DeclaredObservationRead = DeclaredObservationRead(false, null, reason)
    }
}
