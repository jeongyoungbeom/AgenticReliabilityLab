package com.project.agenticreliabilitylab.target.http

import com.project.agenticreliabilitylab.target.domain.IdentityVerificationStatus
import com.project.agenticreliabilitylab.target.domain.NetworkCidr
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetCapability
import com.project.agenticreliabilitylab.target.domain.TargetEnvironment
import com.project.agenticreliabilitylab.target.domain.TargetHealthStatus
import com.project.agenticreliabilitylab.target.infrastructure.TargetHttpProperties
import com.project.agenticreliabilitylab.target.domain.TargetNetworkPolicy
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.time.Clock
import kotlin.time.measureTime
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpTargetSystemTests {
    private val pinnedTransport = PinnedTargetHttpTransport(TargetNetworkPolicy())
    private val targetSystem = HttpTargetSystem(
        TargetHttpProperties(
            connectTimeout = Duration.ofMillis(200),
            readTimeout = Duration.ofMillis(500),
        ),
        pinnedTransport,
        Clock.systemUTC(),
    )

    @Test
    fun `reports UP for a successful health endpoint`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/actuator/health") { exchange ->
            val body = "{\"status\":\"UP\"}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val origin = URI("http://127.0.0.1:${server.address.port}")
            val health = targetSystem.health(target(origin, origin))

            assertEquals(TargetHealthStatus.UP, health.status)
            assertEquals(200, health.httpStatus)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects a health URI outside the configured origin`() {
        val target = target(
            baseUri = URI("http://127.0.0.1:18080"),
            allowedOrigin = URI("http://localhost:18080"),
        )

        assertFailsWith<IllegalArgumentException> {
            targetSystem.health(target)
        }
    }

    @Test
    fun `does not connect when DNS resolves outside the registered CIDR allowlist`() {
        val uri = URI("http://127.0.0.1:18080")
        val health = targetSystem.health(
            target(
                baseUri = uri,
                allowedOrigin = uri,
                allowedNetworkCidrs = setOf(NetworkCidr.parse("10.0.0.0/8")),
            ),
        )

        assertEquals(TargetHealthStatus.UNREACHABLE, health.status)
    }

    @Test
    fun `bounds a target response before buffering beyond one MiB`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/large") { exchange ->
            val body = ByteArray(1_048_577) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val origin = URI("http://127.0.0.1:${server.address.port}")
            assertFailsWith<TargetHttpTransportException> {
                pinnedTransport.send(
                    target(origin, origin),
                    origin.resolve("/large"),
                    "GET",
                    emptyMap(),
                    ByteArray(0),
                    Duration.ofSeconds(2),
                )
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `bounds the total response header count before buffering target headers`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/many-headers") { exchange ->
            repeat(65) { index -> exchange.responseHeaders.add("X-Target-Header-$index", "value") }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()

        try {
            val origin = URI("http://127.0.0.1:${server.address.port}")
            assertFailsWith<TargetHttpTransportException> {
                pinnedTransport.send(
                    target(origin, origin),
                    origin.resolve("/many-headers"),
                    "GET",
                    emptyMap(),
                    ByteArray(0),
                    Duration.ofSeconds(2),
                )
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `enforces an overall deadline for a continuously streaming target response`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/drip") { exchange ->
            val body = "0123456789".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { output ->
                body.forEach { value ->
                    output.write(value.toInt())
                    output.flush()
                    Thread.sleep(40)
                }
            }
        }
        server.start()

        try {
            val origin = URI("http://127.0.0.1:${server.address.port}")
            val elapsed = measureTime {
                assertFailsWith<TargetHttpTransportException> {
                    pinnedTransport.send(
                        target(origin, origin),
                        origin.resolve("/drip"),
                        "GET",
                        emptyMap(),
                        ByteArray(0),
                        Duration.ofMillis(120),
                    )
                }
            }
            assert(elapsed.inWholeMilliseconds < 350) { "Pinned request ignored the overall deadline: $elapsed" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `preserves a configured query string in a pinned target request`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/catalog") { exchange ->
            check(exchange.requestURI.rawQuery == "limit=2")
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()

        try {
            val origin = URI("http://127.0.0.1:${server.address.port}")
            val response = pinnedTransport.send(
                target(origin, origin),
                URI("$origin/catalog?limit=2"),
                "GET",
                emptyMap(),
                ByteArray(0),
                Duration.ofSeconds(2),
            )

            assertEquals(204, response.statusCode)
        } finally {
            server.stop(0)
        }
    }

    private fun target(
        baseUri: URI,
        allowedOrigin: URI,
        allowedNetworkCidrs: Set<NetworkCidr> = setOf(NetworkCidr.parse("127.0.0.0/8")),
    ): RegisteredTarget = RegisteredTarget(
        id = "contract-test-target",
        name = "Contract Test Target",
        adapterType = "HTTP_TARGET",
        environment = TargetEnvironment.TEST,
        baseUri = baseUri,
        allowedOrigin = allowedOrigin,
        allowedNetworkCidrs = allowedNetworkCidrs,
        healthPath = "/actuator/health",
        sourceRepository = "target-profile-test",
        identityVerification = IdentityVerificationStatus.CONFIGURATION_ONLY,
        capabilities = setOf(TargetCapability.HEALTH, TargetCapability.HTTP_API),
        enabled = true,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
