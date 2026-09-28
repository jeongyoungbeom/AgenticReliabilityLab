@file:Suppress("MaxLineLength") // Literal HTTP contract fixtures are kept intact for inspection.

package com.project.agenticreliabilitylab.harness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.project.agenticreliabilitylab.target.domain.TargetReadResponse
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.testspec.application.FixedSpecExecutionSettings
import com.project.agenticreliabilitylab.testspec.application.SpecHttpCaller
import com.project.agenticreliabilitylab.testspec.application.SpecReferenceResolver
import com.project.agenticreliabilitylab.testspec.application.StubAuthProvider
import com.project.agenticreliabilitylab.testspec.application.testTarget
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HTTP probes shared by the H1 contract and the future H2 isolated adapter test. */
class HarnessV1ConformanceTests {
    @Test
    fun `runner sends a fixed version to the V1 adapter`() {
        LocalHarness().use { adapter ->
            val client = HttpClient.newHttpClient()
            val transport = object : TargetReadTransport {
                override fun send(
                    target: RegisteredTarget, uri: URI, method: String, headers: Map<String, String>,
                    body: ByteArray, timeout: Duration,
                ): TargetReadResponse {
                    val request = HttpRequest.newBuilder(uri).timeout(timeout).apply {
                        headers.forEach { (name, value) -> header(name, value) }
                    }.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody()
                        else HttpRequest.BodyPublishers.ofByteArray(body)).build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    return TargetReadResponse(response.statusCode(), response.body())
                }
            }
            val caller = SpecHttpCaller(
                transport, SpecReferenceResolver(ObjectMapper()),
                StubAuthProvider(mapOf("harness" to mapOf("X-ARL-Harness-Key" to "test-key"))),
                FixedSpecExecutionSettings(),
            )
            val runId = UUID.randomUUID().toString()
            val target = testTarget().copy(baseUri = adapter.baseUri, allowedOrigin = adapter.baseUri)
            val call = SpecHttpCall("GET", "/api/harness/state", "harness", emptyMap(), null)
            assertEquals(200, caller.send(target, call, emptyMap(), 1, runId, harnessRequest = true).statusCode)
            assertEquals(426, caller.send(target, call, emptyMap(), 1, runId).statusCode)
        }
    }

    @Test
    fun `version authentication run isolation and repeated reset`() {
        LocalHarness().use { target ->
            val probe = Probe(target.baseUri)
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            val manifest = probe.json("GET", "/api/harness/manifest", first, 200)
            assertTrue(manifest.path("capabilities").path("readinessKinds").isArray)
            assertTrue(manifest.path("capabilities").path("faultTypes").isArray)
            probe.expectError("GET", "/api/harness/manifest", first, 401, "UNAUTHORIZED", key = "wrong")
            probe.expectError("GET", "/api/harness/manifest", first, 426, "UNSUPPORTED_HARNESS_VERSION", version = "2")
            probe.expectError("GET", "/api/harness/manifest", first, 426, "UNSUPPORTED_HARNESS_VERSION", version = "")
            probe.expectError("GET", "/api/harness/state", "bad-id", 400, "INVALID_REQUEST")

            target.fixtures[first] = 1
            target.fixtures[second] = 1
            assertEquals(1, probe.json("GET", "/api/harness/state", first, 200).path("fixtureCount").asInt())
            target.failReset = true
            probe.expectError("POST", "/api/harness/reset", first, 500, "RESET_FAILED")
            assertEquals(1, probe.json("GET", "/api/harness/state", first, 200).path("fixtureCount").asInt())
            target.failReset = false
            assertEquals(1, probe.json("POST", "/api/harness/reset", first, 200).path("removedFixtureCount").asInt())
            assertEquals(0, probe.json("POST", "/api/harness/reset", first, 200).path("removedFixtureCount").asInt())
            assertEquals(0, probe.json("GET", "/api/harness/state", first, 200).path("fixtureCount").asInt())
            assertEquals(1, probe.json("GET", "/api/harness/state", second, 200).path("fixtureCount").asInt())
        }
    }

    @Test
    fun `readiness times out without another business write and rejects foreign run`() {
        LocalHarness().use { target ->
            val probe = Probe(target.baseUri)
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            val deadline = System.nanoTime() + Duration.ofMillis(60).toNanos()
            var ready = false
            while (System.nanoTime() < deadline) {
                val result = probe.json("GET", "/api/harness/readiness/products/pending", first, 200)
                ready = result.path("ready").asBoolean()
                assertTrue(result.path("reason").asString().isNotBlank())
                if (ready) break
                Thread.sleep(10)
            }
            assertFalse(ready)
            assertEquals(0, target.businessWrites)
            assertTrue(probe.json("GET", "/api/harness/readiness/products/ready", first, 200)
                .path("ready").asBoolean())
            probe.expectError(
                "GET", "/api/harness/readiness/products/owned-$first", second,
                409, "RUN_SCOPE_MISMATCH",
            )
            probe.expectError("GET", "/api/harness/readiness/unknown/id", first, 404, "CAPABILITY_NOT_SUPPORTED")
        }
    }

    @Test
    fun `fault release failure remains visible and handles are run scoped`() {
        LocalHarness().use { target ->
            val probe = Probe(target.baseUri)
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            val body = """{"runId":"$first","trialScope":"trial-1","faultType":"PAYMENT_FAILURE","scope":"next-1","ttlMs":30000}"""
            probe.expectError(
                "POST", "/api/harness/fault", second, 409, "RUN_SCOPE_MISMATCH", body,
            )
            probe.expectError(
                "POST", "/api/harness/fault", first, 422, "INVALID_FAULT_TTL",
                body.replace("30000", "0"),
            )
            val faultId = probe.json("POST", "/api/harness/fault", first, 201, body).path("faultId").asString()
            assertTrue(faultId.isNotBlank())
            val release = """{"runId":"$first","faultId":"$faultId"}"""
            probe.expectError("POST", "/api/harness/fault/release", second, 409, "RUN_SCOPE_MISMATCH", release)
            target.failRelease = true
            probe.expectError("POST", "/api/harness/fault/release", first, 500, "FAULT_RELEASE_FAILED", release)
            assertEquals(1, probe.json("GET", "/api/harness/state", first, 200).path("activeFaultCount").asInt())
            target.failRelease = false
            assertTrue(probe.json("POST", "/api/harness/fault/release", first, 200, release)
                .path("released").asBoolean())
            probe.expectError(
                "POST", "/api/harness/fault/release", first, 404, "RESOURCE_NOT_FOUND",
                """{"runId":"$first","faultId":"unknown"}""",
            )
            assertEquals(0, probe.json("GET", "/api/harness/state", first, 200).path("activeFaultCount").asInt())
            assertTrue(probe.json("POST", "/api/harness/fault/release", first, 200, release)
                .path("released").asBoolean())
            probe.expectError(
                "POST", "/api/harness/fault", first, 422, "UNSUPPORTED_FAULT",
                body.replace("PAYMENT_FAILURE", "OTHER"),
            )
        }
    }

    @Test
    fun `schema defines each response and error body`() {
        val schema = requireNotNull(javaClass.getResourceAsStream("/schema/harness-v1.schema.json"))
            .use { ObjectMapper().readTree(it) }
        listOf(
            "manifest", "fixtureRecipe", "state", "reset", "readiness", "faultRequest",
            "faultReleaseRequest", "faultInjection", "faultRelease", "error",
        )
            .forEach { name ->
                assertTrue(schema.path("\$defs").path(name).isObject, "Missing schema for $name")
                assertTrue(schema.path("\$defs").path(name).path("required").isArray)
            }
        val validator = SchemaProbe(ObjectMapper())
        val validRecipe = ObjectMapper().readTree("""{"kind":"SYNTHETIC_JSON_V1","inputs":[{"pointer":"/name","source":"RUN_TAGGED_STRING","prefix":"arl-product"},{"pointer":"/stock","source":"BOUNDED_INTEGER","value":10}]}""")
        assertTrue(validator.matches("fixtureRecipe", validRecipe))
        assertFalse(validator.matches("fixtureRecipe", ObjectMapper().readTree(""""unknown-recipe"""")))
        assertFalse(validator.matches("fixtureRecipe", ObjectMapper().readTree("""{"kind":"SYNTHETIC_JSON_V1","inputs":[{"pointer":"/name","source":"RAW_STRING","value":"real customer"}]}""")))
        assertFalse(validator.matches("fixtureRecipe", ObjectMapper().readTree("""{"kind":"SYNTHETIC_JSON_V1","inputs":[{"pointer":"/name","source":"BOUNDED_INTEGER","value":10001}]}""")))
        assertFalse(validator.matches("state", ObjectMapper().readTree("""{"version":"1.0"}""")))
        assertFalse(validator.matches("reset", ObjectMapper().readTree(
            """{"version":"1.0","runId":"${UUID.randomUUID()}","clean":false,"removedFixtureCount":0,"activeFaultCount":0}""",
        )))
    }
}

private class Probe(private val baseUri: URI) {
    private val client = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val schema = SchemaProbe(mapper)

    fun json(method: String, path: String, runId: String, status: Int, body: String = "") =
        mapper.readTree(expect(method, path, runId, status, body)).also { value ->
            assertEquals("1.0", value.path("version").asString())
            if (path != "/api/harness/manifest") assertEquals(runId, value.path("runId").asString())
            val shape = when {
                path.endsWith("/manifest") -> "manifest"
                path.endsWith("/state") -> "state"
                path.endsWith("/reset") -> "reset"
                path.contains("/readiness/") -> "readiness"
                path.endsWith("/fault/release") -> "faultRelease"
                else -> "faultInjection"
            }
            assertTrue(schema.matches(shape, value), "Invalid $shape response")
        }

    fun expect(method: String, path: String, runId: String, status: Int, body: String = ""): String {
        val response = send(method, path, runId, body)
        assertEquals(status, response.statusCode(), path)
        assertEquals("1", response.headers().firstValue("X-ARL-Harness-Version").orElse(null))
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        return response.body()
    }

    fun expectError(
        method: String, path: String, runId: String, status: Int, code: String,
        body: String = "", key: String = "test-key", version: String = "1",
    ) {
        val response = send(method, path, runId, body, key, version)
        assertEquals(status, response.statusCode(), path)
        assertEquals("1", response.headers().firstValue("X-ARL-Harness-Version").orElse(null))
        val value = mapper.readTree(response.body())
        assertEquals("1.0", value.path("version").asString())
        assertEquals(code, value.path("error").path("code").asString())
        assertTrue(value.path("error").path("message").asString().isNotBlank())
        assertFalse(response.body().contains("test-key"))
        assertTrue(schema.matches("error", value))
    }

    private fun send(
        method: String, path: String, runId: String, body: String = "",
        key: String = "test-key", version: String = "1",
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(baseUri.resolve(path))
            .header("X-ARL-Harness-Key", key)
            .header("X-ARL-Run-Id", runId)
            .header("X-ARL-Harness-Version", version)
            .header("Accept", "application/json")
            .method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString(body)
                else HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}

/** Validates the JSON Schema keywords used by this contract without adding a runtime dependency. */
private class SchemaProbe(mapper: ObjectMapper) {
    private val root = requireNotNull(javaClass.getResourceAsStream("/schema/harness-v1.schema.json"))
        .use { mapper.readTree(it) }

    fun matches(name: String, value: JsonNode): Boolean = check(value, root.path("\$defs").path(name))

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // One small verifier covers the schema keywords in this fixture.
    private fun check(value: JsonNode, raw: JsonNode): Boolean {
        val schema = raw.path("\$ref").asString().takeIf { it.isNotBlank() }
            ?.removePrefix("#/")
            ?.split("/")
            ?.fold(root) { node, part -> node.path(part) } ?: raw
        val alternatives = schema.path("oneOf")
        if (alternatives.isArray && alternatives.values().count { alternative -> check(value, alternative) } != 1) {
            return false
        }
        val types = schema.path("type").let { type ->
            when {
                type.isArray -> type.values().map(JsonNode::asString).toList()
                type.isString -> listOf(type.asString())
                else -> emptyList()
            }
        }
        if (types.isNotEmpty() && types.none { type -> matchesType(value, type) }) return false
        if (!schema.path("const").isMissingNode && schema.path("const") != value) return false
        val enums = schema.path("enum")
        if (enums.isArray && enums.values().none { it == value }) return false
        if (value.isString) {
            val text = value.asString()
            if (text.length < (schema.path("minLength").asString().toIntOrNull() ?: 0)) return false
            val pattern = schema.path("pattern").asString()
            if (pattern.isNotBlank() && !pattern.toRegex().containsMatchIn(text)) return false
            when (schema.path("format").asString()) {
                "uuid" -> if (runCatching { UUID.fromString(text) }.isFailure) return false
                "date-time" -> if (runCatching { Instant.parse(text) }.isFailure) return false
            }
        }
        if (value.isNumber) {
            val minimum = schema.path("minimum").asString().toDoubleOrNull()
            if (minimum != null && value.asString().toDouble() < minimum) return false
            val maximum = schema.path("maximum").asString().toDoubleOrNull()
            if (maximum != null && value.asString().toDouble() > maximum) return false
        }
        if (value.isArray) {
            val items = value.values().toList()
            if (items.size < (schema.path("minItems").asString().toIntOrNull() ?: 0)) return false
            if (schema.path("uniqueItems").asBoolean() && items.distinct().size != items.size) return false
            val itemSchema = schema.path("items")
            if (!itemSchema.isMissingNode && items.any { !check(it, itemSchema) }) return false
        }
        if (value.isObject) {
            val required = schema.path("required")
            if (required.isArray && required.values().any { value.path(it.asString()).isMissingNode }) return false
            val properties = schema.path("properties")
            val additional = schema.path("additionalProperties")
            if (value.propertyNames().any { name ->
                    val fieldSchema = properties.path(name)
                    when {
                        !fieldSchema.isMissingNode -> !check(value.path(name), fieldSchema)
                        additional.isObject -> !check(value.path(name), additional)
                        additional.isBoolean -> !additional.asBoolean()
                        else -> false
                    }
                }) return false
        }
        return true
    }

    private fun matchesType(value: JsonNode, type: String): Boolean = when (type) {
        "object" -> value.isObject
        "array" -> value.isArray
        "string" -> value.isString
        "integer" -> value.isIntegralNumber
        "number" -> value.isNumber
        "boolean" -> value.isBoolean
        "null" -> value.isNull
        else -> false
    }
}

private class LocalHarness : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUri: URI = URI("http://127.0.0.1:${server.address.port}")
    val fixtures = mutableMapOf<String, Int>()
    private val faults = mutableMapOf<String, String>()
    private val issuedFaults = mutableMapOf<String, String>()
    var failRelease = false
    var failReset = false
    val businessWrites = 0

    init {
        server.createContext("/api/harness/") { exchange -> handle(exchange) }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val key = exchange.requestHeaders.getFirst("X-ARL-Harness-Key")
            val version = exchange.requestHeaders.getFirst("X-ARL-Harness-Version")
            val runId = exchange.requestHeaders.getFirst("X-ARL-Run-Id")
            when {
                key != "test-key" -> error(exchange, 401, "UNAUTHORIZED")
                version != "1" -> error(exchange, 426, "UNSUPPORTED_HARNESS_VERSION")
                runId == null || runCatching { UUID.fromString(runId) }.isFailure ->
                    error(exchange, 400, "INVALID_REQUEST")
                else -> route(exchange, runId)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // Endpoint cases mirror the contract table one for one.
    private fun route(exchange: HttpExchange, runId: String) {
        val path = exchange.requestURI.path
        when {
            path == "/api/harness/manifest" && exchange.requestMethod == "GET" ->
                json(exchange, 200, """{"version":"1.0","capabilities":{"state":true,"reset":true,"readinessKinds":["products"],"faultTypes":["PAYMENT_FAILURE"]},"operations":[{"operationId":"createProduct","method":"POST","path":"/api/products","authProfile":"seller","fixtureRecipe":{"kind":"SYNTHETIC_JSON_V1","inputs":[{"pointer":"/name","source":"RUN_TAGGED_STRING","prefix":"arl-product"},{"pointer":"/stock","source":"BOUNDED_INTEGER","value":10},{"pointer":"/price","source":"BOUNDED_INTEGER","value":1000}]},"captures":{"productId":"/productId"},"observations":[{"field":"fixtureCount","expected":1}],"idempotency":"NONE","readinessKind":"products"}]}""")
            path == "/api/harness/state" && exchange.requestMethod == "GET" ->
                json(exchange, 200, """{"version":"1.0","runId":"$runId","fixtureCount":${fixtures[runId] ?: 0},"activeFaultCount":${faults.values.count { it == runId }}}""")
            path == "/api/harness/reset" && exchange.requestMethod == "POST" -> {
                if (failReset) {
                    error(exchange, 500, "RESET_FAILED")
                    return
                }
                val removed = fixtures.remove(runId) ?: 0
                faults.entries.removeIf { it.value == runId }
                json(exchange, 200, """{"version":"1.0","runId":"$runId","clean":true,"removedFixtureCount":$removed,"activeFaultCount":0}""")
            }
            path.startsWith("/api/harness/readiness/") && exchange.requestMethod == "GET" ->
                readiness(exchange, runId, path)
            path == "/api/harness/fault" && exchange.requestMethod == "POST" -> inject(exchange, runId)
            path == "/api/harness/fault/release" && exchange.requestMethod == "POST" -> release(exchange, runId)
            else -> error(exchange, 404, "CAPABILITY_NOT_SUPPORTED")
        }
    }

    private fun readiness(exchange: HttpExchange, runId: String, path: String) {
        val parts = path.removePrefix("/api/harness/readiness/").split("/")
        if (parts.size != 2 || parts[0] != "products") {
            error(exchange, 404, "CAPABILITY_NOT_SUPPORTED")
            return
        }
        if (parts[1].startsWith("owned-") && parts[1].removePrefix("owned-") != runId) {
            error(exchange, 409, "RUN_SCOPE_MISMATCH")
            return
        }
        val ready = parts[1] != "pending"
        json(exchange, 200, """{"version":"1.0","runId":"$runId","ready":$ready,"reason":"${if (ready) "READY" else "PENDING"}"}""")
    }

    @Suppress("ReturnCount") // Each rejected request ends the fake adapter response immediately.
    private fun inject(exchange: HttpExchange, runId: String) {
        val body = ObjectMapper().readTree(exchange.requestBody)
        if (!SchemaProbe(ObjectMapper()).matches("faultRequest", body)) {
            error(exchange, 400, "INVALID_REQUEST")
            return
        }
        if (body.path("runId").asString() != runId) {
            error(exchange, 409, "RUN_SCOPE_MISMATCH")
            return
        }
        if (body.path("faultType").asString() != "PAYMENT_FAILURE") {
            error(exchange, 422, "UNSUPPORTED_FAULT")
            return
        }
        if (body.path("ttlMs").asInt() !in 1..120000) {
            error(exchange, 422, "INVALID_FAULT_TTL")
            return
        }
        val faultId = UUID.randomUUID().toString()
        faults[faultId] = runId
        issuedFaults[faultId] = runId
        json(exchange, 201, """{"version":"1.0","runId":"$runId","faultId":"$faultId","expiresAt":"2026-09-28T00:01:00Z"}""")
    }

    @Suppress("ReturnCount") // Each rejected request ends the fake adapter response immediately.
    private fun release(exchange: HttpExchange, runId: String) {
        val body = ObjectMapper().readTree(exchange.requestBody)
        if (!SchemaProbe(ObjectMapper()).matches("faultReleaseRequest", body)) {
            error(exchange, 400, "INVALID_REQUEST")
            return
        }
        val faultId = body.path("faultId").asString()
        if (body.path("runId").asString() != runId || issuedFaults[faultId]?.let { it != runId } == true) {
            error(exchange, 409, "RUN_SCOPE_MISMATCH")
            return
        }
        if (faultId !in issuedFaults) {
            error(exchange, 404, "RESOURCE_NOT_FOUND")
            return
        }
        if (failRelease) {
            error(exchange, 500, "FAULT_RELEASE_FAILED")
            return
        }
        faults.remove(faultId)
        json(exchange, 200, """{"version":"1.0","runId":"$runId","released":true,"activeFaultCount":${faults.values.count { it == runId }}}""")
    }

    private fun error(exchange: HttpExchange, status: Int, code: String) =
        json(exchange, status, """{"version":"1.0","error":{"code":"$code","message":"Harness request failed"}}""")

    private fun json(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("X-ARL-Harness-Version", "1")
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() {
        server.stop(0)
    }
}
