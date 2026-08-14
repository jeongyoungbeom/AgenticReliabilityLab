package com.project.agenticreliabilitylab.common

/** A request that is syntactically valid but cannot be accepted in its current state. */
interface ClientRequestFailure {
    val code: String
}
