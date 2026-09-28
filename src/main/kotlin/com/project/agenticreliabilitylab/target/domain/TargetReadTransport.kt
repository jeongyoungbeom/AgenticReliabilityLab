package com.project.agenticreliabilitylab.target.domain

import java.net.URI
import java.time.Duration

/** Port for ARL's restricted, CIDR-pinned outbound read requests. */
interface TargetReadTransport {
    fun send(
        target: RegisteredTarget,
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeout: Duration,
    ): TargetReadResponse
}

data class TargetReadResponse(
    val statusCode: Int,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
)

class TargetReadTransportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
