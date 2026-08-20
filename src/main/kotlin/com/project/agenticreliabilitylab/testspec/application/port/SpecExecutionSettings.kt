package com.project.agenticreliabilitylab.testspec.application.port

import java.time.Duration

/** Execution limits the Runner enforces regardless of what a specification asks for. */
interface SpecExecutionSettings {
    /** Deadline for one request to the Target. */
    val requestTimeout: Duration

    /** Upper bound on how long an observation may wait for a value to settle, whatever the specification says. */
    val maxObservationWait: Duration
}
