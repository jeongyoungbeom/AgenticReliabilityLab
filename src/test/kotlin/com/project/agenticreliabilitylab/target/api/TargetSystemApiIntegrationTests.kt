package com.project.agenticreliabilitylab.target.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TargetSystemApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Test
    fun `lists the configured target through the HTTP API`() {
        val response = get("/api/targets")

        assertEquals(200, response.statusCode())
        assertContains(response.body(), "contract-test-target")
        assertContains(response.body(), "CONFIGURATION_ONLY")
    }

    @Test
    fun `reports an unreachable target without failing the API`() {
        val response = get("/api/targets/contract-test-target/health")

        assertEquals(200, response.statusCode())
        assertContains(response.body(), "UNREACHABLE")
    }

    @Test
    fun `returns the declared target identity without exposing repository paths`() {
        val response = get("/api/targets/contract-test-target/identity")

        assertEquals(200, response.statusCode())
        assertContains(response.body(), "CONFIGURATION_ONLY")
        assertFalse(response.body().contains("private-target-reference"))
    }

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
