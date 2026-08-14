package com.project.agenticreliabilitylab.targetprofiledraft.api

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
import kotlin.test.assertFalse
import tools.jackson.databind.ObjectMapper

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TargetProfileDraftApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val objectMapper = ObjectMapper()

    @Test
    fun `creates disabled YAML Draft from fixed OpenAPI GET operations only`() {
        val response = post(
            "/api/target-profile-drafts/openapi",
            """
                {
                  "openapi":"3.1.0",
                  "info":{"title":"Catalog Service"},
                  "servers":[{"url":"http://127.0.0.1:18080"}],
                  "paths":{
                    "/catalog":{"get":{"operationId":"catalog-list","responses":{"200":{"description":"ok"}}}},
                    "/catalog/{id}":{"get":{"responses":{"200":{"description":"ignored"}}}},
                    "/orders":{"post":{"responses":{"201":{"description":"ignored"}}}}
                  }
                }
            """.trimIndent(),
        )

        assertEquals(200, response.statusCode(), response.body())
        assertContains(response.body(), "catalog-list")
        assertContains(response.body(), "execution-enabled: false")
        assertContains(response.body(), "enabled: false")
        assertContains(response.body(), "http://127.0.0.1:18080")
        assertFalse(response.body().contains("/catalog/{id}"))
    }

    @Test
    fun `rejects external OpenAPI reference and keeps README extraction offline`() {
        val externalReference = post(
            "/api/target-profile-drafts/openapi",
            """{"openapi":"3.1.0","paths":{"/safe":{"get":{"${'$'}ref":"https://example.test/remote.yaml"}}}}""",
        )
        assertEquals(400, externalReference.statusCode(), externalReference.body())

        val readme = post(
            "/api/target-profile-drafts/readme",
            """
                # Commerce Service
                GET /products - list products
                POST /products - must not become a candidate
                GET /products/{id} - dynamic path must not become a candidate
            """.trimIndent(),
        )
        assertEquals(200, readme.statusCode(), readme.body())
        assertContains(readme.body(), "\"path\":\"/products\"")
        assertContains(readme.body(), "execution-enabled: false")
        assertFalse(readme.body().contains("/products/{id}"))
    }

    @Test
    fun `does not copy an OpenAPI server path that safe execution cannot preserve`() {
        val response = post(
            "/api/target-profile-drafts/openapi",
            """
                {"openapi":"3.1.0","servers":[{"url":"https://target.test/api/v1"}],
                "paths":{"/catalog":{"get":{"responses":{"200":{"description":"ok"}}}}}}
            """.trimIndent(),
        )

        assertEquals(200, response.statusCode(), response.body())
        assertFalse(response.body().contains("https://target.test/api/v1"))
        assertContains(response.body(), "http://127.0.0.1:8080")
    }

    @Test
    fun `resolves an OpenAPI document-local Path Item reference without fetching content`() {
        val response = post(
            "/api/target-profile-drafts/openapi",
            """
                {
                  "openapi":"3.1.0",
                  "paths":{"/catalog":{"${'$'}ref":"#/components/pathItems/catalog"}},
                  "components":{"pathItems":{"catalog":{"get":{"operationId":"catalog-list",
                  "responses":{"200":{"description":"ok"}}}}}}
                }
            """.trimIndent(),
        )

        assertEquals(200, response.statusCode(), response.body())
        assertContains(response.body(), "catalog-list")
    }

    @Test
    fun `rejects a Draft request before MVC materializes an oversized JSON body`() {
        val response = post("/api/target-profile-drafts/openapi", "x".repeat(2_100_000))

        assertEquals(413, response.statusCode())
    }

    private fun post(path: String, document: String): HttpResponse<String> = httpClient.send(
        HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:$serverPort$path"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer profile-editor-draft-token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(mapOf("document" to document))))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun accessProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.access.mode") { "SECURED" }
            registry.add("arl.access.viewer-token") { "viewer-draft-token" }
            registry.add("arl.access.profile-editor-token") { "profile-editor-draft-token" }
            registry.add("arl.access.executor-token") { "executor-draft-token" }
        }
    }
}
