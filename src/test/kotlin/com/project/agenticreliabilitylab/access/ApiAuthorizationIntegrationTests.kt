package com.project.agenticreliabilitylab.access

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiAuthorizationIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @Test
    fun `secured mode default-denies legacy APIs and preserves viewer-only reads`() {
        val unauthorizedRead = get("/api/targets")
        assertEquals(403, unauthorizedRead.statusCode())
        assertContains(unauthorizedRead.body(), "\"correlationId\":\"")
        assertEquals(200, get("/api/targets", "viewer-test-token").statusCode())

        assertEquals(403, get("/api/targets/contract-test-target/health", "viewer-test-token").statusCode())
        assertEquals(403, post("/api/experiments", "viewer-test-token").statusCode())
        assertEquals(403, post("/api/experiments").statusCode())
    }

    @Test
    fun `secured mode sends document authoring posts to their profile editor controllers`() {
        val paths = listOf(
            "/api/test-specifications",
            "/api/targets/contract-test-target/test-specification-generations",
            "/api/targets/contract-test-target/test-spec-misjudgment-reports",
        )

        paths.forEach { path ->
            assertEquals(400, post(path, "profile-editor-test-token").statusCode(), path)
            assertEquals(403, post(path, "viewer-test-token").statusCode(), path)
        }
    }

    private fun get(path: String, token: String? = null): HttpResponse<String> {
        val request = request(path).GET()
        token?.let { request.header(AUTHORIZATION_HEADER, "Bearer $it") }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, token: String? = null): HttpResponse<String> {
        val request = request(path).header("Content-Type", "application/json")
        token?.let { request.header(AUTHORIZATION_HEADER, "Bearer $it") }
        return httpClient.send(
            request.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI("http://127.0.0.1:$serverPort$path"))
        .timeout(Duration.ofSeconds(3))

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"

        @JvmStatic
        @DynamicPropertySource
        fun accessProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.access.mode") { "SECURED" }
            registry.add("arl.access.viewer-token") { "viewer-test-token" }
            registry.add("arl.access.profile-editor-token") { "profile-editor-test-token" }
            registry.add("arl.access.executor-token") { "executor-test-token" }
        }
    }
}
