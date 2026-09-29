package com.project.agenticreliabilitylab.targetprofile.api

import com.project.agenticreliabilitylab.testspec.application.port.TestSpecExecutionProfileCatalog
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.targetprofiledraft.application.BoundedOpenApiDocumentParser
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Suppress(
    "LargeClass", "LongMethod", "CyclomaticComplexMethod", "MaxLineLength",
) // HTTP fixture and end-to-end assertions cover Profile activation and generic pilot execution together.
class TargetProfileApiIntegrationTests {
    @Value("\${local.server.port}")
    private var serverPort: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var testSpecProfiles: TestSpecExecutionProfileCatalog

    @Autowired
    private lateinit var openApiParser: BoundedOpenApiDocumentParser

    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    @Test
    fun `generic proposals require explicit activation for two unrelated contracts`() {
        listOf(
            GenericFixture("widgets", "POST", "writer", "widgetCount"),
            GenericFixture("tasks", "PUT", "operator", "taskCount"),
        ).forEach { fixture ->
            val manifest = AtomicReference(genericManifest(fixture))
            val target = genericTarget(fixture, manifest)
            target.start()
            try {
                val name = "Generic ${fixture.resource} ${UUID.randomUUID().toString().take(8)}"
                val request = genericProposal(name, target.address.port)
                val proposed = post("/api/target-profiles/proposals", request, authorizationHeader())
                assertEquals(202, proposed.statusCode(), proposed.body())
                assertContains(proposed.body(), "\"status\":\"DRAFT\"")
                val versionId = objectMapper.readTree(proposed.body()).path("id").asString()
                val targetId = objectMapper.readTree(proposed.body()).path("targetSystemId").asString()
                val effective = get("/api/target-profiles/$versionId/effective-settings")
                val settings = objectMapper.readTree(effective.body())
                assertEquals("/health", settings.path("healthPath").asString())
                val calls = settings.path("allowedCalls").toString()
                assertContains(calls, "GET /health")
                assertContains(effective.body(), "${fixture.method} /api/${fixture.resource}")
                assertContains(effective.body(), fixture.role)
                assertFalse(effective.body().contains("/api/products"))
                assertEquals(404, get("/api/targets/$targetId/test-candidates").statusCode())

                val cookie = proposed.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
                val activated = post(
                    "/api/target-profiles/$versionId/activate",
                    "{\"confirmation\":\"ACTIVATE_TARGET_PROFILE_VERSION\"}",
                    authorizationHeader() + mapOf("Cookie" to cookie),
                )
                assertEquals(202, activated.statusCode(), activated.body())
                assertContains(activated.body(), "\"status\":\"ACTIVE\"")
                val withoutHarness = get("/api/targets/$targetId/pilot-discovery")
                assertEquals(200, withoutHarness.statusCode(), withoutHarness.body())
                assertContains(withoutHarness.body(), "\"id\":\"generic-read-1\"")
                assertFalse(withoutHarness.body().contains("\"id\":\"generic-write-1\""))
                val readRun = post(
                    "/api/targets/$targetId/pilot-template-runs",
                    """{"candidateIds":["generic-read-1"],"confirmation":"EXECUTE_PILOT_TEMPLATES"}""",
                    executorAuthorizationHeader() + mapOf("Idempotency-Key" to "read-${UUID.randomUUID()}"),
                )
                assertEquals(201, readRun.statusCode(), readRun.body())
                assertContains(readRun.body(), "\"resultOutcome\":\"PASSED\"")
                val candidates = get("/api/targets/$targetId/test-candidates")
                assertEquals(200, candidates.statusCode(), candidates.body())
                assertFalse(candidates.body().contains("\"path\":\"/api/${fixture.resource}\""))
                assertTrue(testSpecProfiles.requireActive(targetId).capabilities.allows(
                    SpecHttpCall(fixture.method, "/api/${fixture.resource}", fixture.role, emptyMap(), null),
                ))
                val credentials = put(
                    "/api/targets/$targetId/runtime-credentials",
                    objectMapper.writeValueAsString(mapOf("roles" to mapOf(fixture.role to "role-test-token"))),
                    executorAuthorizationHeader() + mapOf("Cookie" to cookie),
                )
                assertEquals(200, credentials.statusCode(), credentials.body())
                assertContains(credentials.body(), fixture.role)
                val preflight = post(
                    "/api/targets/$targetId/runtime-credentials/preflight", "{}",
                    executorAuthorizationHeader() + mapOf("Cookie" to cookie),
                )
                assertEquals(200, preflight.statusCode(), preflight.body())
                assertContains(preflight.body(), "\"role\":\"${fixture.role}\",\"status\":\"READY\"")
                val discovery = get(
                    "/api/targets/$targetId/pilot-discovery",
                    mapOf("Cookie" to cookie),
                )
                assertEquals(200, discovery.statusCode(), discovery.body())
                val genericCandidates = objectMapper.readTree(discovery.body()).path("candidates")
                assertTrue(genericCandidates.any {
                    it.path("id").asString() == "generic-read-1" &&
                        it.path("readiness").asString() == "READY"
                }, discovery.body())
                assertTrue(genericCandidates.any {
                    it.path("id").asString() == "generic-write-1" &&
                        it.path("readiness").asString() == "READY"
                }, discovery.body())
                val run = post(
                    "/api/targets/$targetId/pilot-template-runs",
                    """{"candidateIds":["generic-write-1"],"confirmation":"EXECUTE_PILOT_TEMPLATES"}""",
                    executorAuthorizationHeader() + mapOf(
                        "Cookie" to cookie,
                        "Idempotency-Key" to "generic-${UUID.randomUUID()}",
                    ),
                )
                assertEquals(201, run.statusCode(), run.body())
                assertContains(run.body(), "\"resultOutcome\":\"PASSED\"")
                assertContains(run.body(), "\"cleanupVerified\":true")
            } finally {
                target.stop(0)
            }
        }
    }

    @Test
    fun `public GET beside protected write stays ready and rejects OpenAPI drift without Harness credentials`() {
        val fixture = GenericFixture("public-items", "POST", "writer", "itemCount")
        val manifest = AtomicReference(genericManifest(fixture))
        val openApi = AtomicReference(genericOpenApi(fixture))
        val target = genericTarget(fixture, manifest, openApi, publicRead = true)
        target.start()
        try {
            val proposed = post(
                "/api/target-profiles/proposals",
                genericProposal("Public read ${UUID.randomUUID()}", target.address.port),
                authorizationHeader(),
            )
            assertEquals(202, proposed.statusCode(), proposed.body())
            val versionId = objectMapper.readTree(proposed.body()).path("id").asString()
            val targetId = objectMapper.readTree(proposed.body()).path("targetSystemId").asString()
            val cookie = proposed.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
            assertEquals(202, post(
                "/api/target-profiles/$versionId/activate",
                """{"confirmation":"ACTIVATE_TARGET_PROFILE_VERSION"}""",
                authorizationHeader() + mapOf("Cookie" to cookie),
            ).statusCode())
            val discovery = get("/api/targets/$targetId/pilot-discovery")
            assertEquals(200, discovery.statusCode(), discovery.body())
            assertTrue(objectMapper.readTree(discovery.body()).path("candidates").any {
                it.path("id").asString() == "generic-read-2" && it.path("readiness").asString() == "READY"
            }, discovery.body())
            val run = post(
                "/api/targets/$targetId/pilot-template-runs",
                """{"candidateIds":["generic-read-2"],"confirmation":"EXECUTE_PILOT_TEMPLATES"}""",
                executorAuthorizationHeader() + mapOf("Idempotency-Key" to "read-${UUID.randomUUID()}"),
            )
            assertEquals(201, run.statusCode(), run.body())
            assertContains(run.body(), "\"resultOutcome\":\"PASSED\"")

            openApi.set(openApi.get().replace("\"version\":\"1\"", "\"version\":\"2\""))
            assertEquals(409, get("/api/targets/$targetId/pilot-discovery").statusCode())
            val rejected = post(
                "/api/targets/$targetId/pilot-template-runs",
                """{"candidateIds":["generic-read-2"],"confirmation":"EXECUTE_PILOT_TEMPLATES"}""",
                executorAuthorizationHeader() + mapOf("Idempotency-Key" to "drift-${UUID.randomUUID()}"),
            )
            assertEquals(409, rejected.statusCode(), rejected.body())
            assertContains(rejected.body(), "TARGET_CONTRACT_CHANGED")
        } finally {
            target.stop(0)
        }
    }

    @Test
    fun `generic proposal rejects mismatches and activation rejects manifest drift`() {
        val fixture = GenericFixture("records", "POST", "creator", "recordCount")
        val manifest = AtomicReference(genericManifest(fixture))
        val target = genericTarget(fixture, manifest)
        target.start()
        try {
            val request = genericProposal("Reject ${UUID.randomUUID()}", target.address.port)
            manifest.set(genericManifest(fixture).replace("/api/records", "/api/missing"))
            val mismatch = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(400, mismatch.statusCode(), mismatch.body())
            assertContains(mismatch.body(), "absent or ambiguous")

            manifest.set(genericManifest(fixture).replace("\"1.0\"", "\"2.0\""))
            val version = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(400, version.statusCode(), version.body())
            assertContains(version.body(), "Unsupported Harness manifest version")

            manifest.set("{broken-json")
            val malformed = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(400, malformed.statusCode(), malformed.body())
            assertContains(malformed.body(), "Harness manifest must be valid JSON")

            manifest.set(genericManifest(fixture).replace("RUN_TAGGED_STRING", "LITERAL"))
            val unsupportedRecipe = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(400, unsupportedRecipe.statusCode(), unsupportedRecipe.body())
            assertContains(unsupportedRecipe.body(), "Unsupported fixture input source")

            manifest.set(genericManifest(fixture).replace("\"/name\"", "\"/unknown\""))
            val missingField = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(400, missingField.statusCode(), missingField.body())
            assertContains(missingField.body(), "OpenAPI property")

            manifest.set(genericManifest(fixture).replace("\"captures\":{}", "\"captures\":{\"id\":\"/items\"}"))
            val nonScalarCapture = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(400, nonScalarCapture.statusCode(), nonScalarCapture.body())
            assertContains(nonScalarCapture.body(), "scalar OpenAPI response field")

            manifest.set(genericManifest(fixture).replace("creator", "harness"))
            val harnessBusinessRole = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(400, harnessBusinessRole.statusCode(), harnessBusinessRole.body())
            assertContains(harnessBusinessRole.body(), "cannot use the Harness credential")

            manifest.set(genericManifest(fixture))
            val proposed = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(202, proposed.statusCode(), proposed.body())
            val versionId = objectMapper.readTree(proposed.body()).path("id").asString()
            val cookie = proposed.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
            manifest.set(genericManifest(fixture).replace("creator", "otherrole"))
            val activation = post(
                "/api/target-profiles/$versionId/activate",
                "{\"confirmation\":\"ACTIVATE_TARGET_PROFILE_VERSION\"}",
                authorizationHeader() + mapOf("Cookie" to cookie),
            )
            assertEquals(400, activation.statusCode(), activation.body())
            assertContains(activation.body(), "changed since Profile proposal")
            assertContains(get("/api/target-profiles/$versionId").body(), "\"status\":\"DRAFT\"")
        } finally {
            target.stop(0)
        }
    }

    @Test
    fun `generic keyed and asynchronous candidates execute only from the approved manifest`() {
        listOf("KEYED", "ASYNC").forEach { mode ->
            val fixture = GenericFixture("jobs", "POST", "writer", "jobCount")
            val original = genericManifest(fixture)
            val manifest = AtomicReference(
                if (mode == "KEYED") original.replace("\"idempotency\":\"NONE\"", "\"idempotency\":\"KEYED\"")
                else original
                    .replace("\"readinessKinds\":[]", "\"readinessKinds\":[\"jobs\"]")
                    .replace("\"captures\":{}", "\"captures\":{\"id\":\"/id\"}")
                    .replace("\"idempotency\":\"NONE\"", "\"idempotency\":\"NONE\",\"readinessKind\":\"jobs\""),
            )
            val openApi = AtomicReference(
                if (mode == "ASYNC") genericOpenApi(fixture).replace(
                    "\"201\":{\"description\":\"created\"}",
                    "\"201\":{\"description\":\"created\",\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}}}}",
                ) else genericOpenApi(fixture),
            )
            val target = genericTarget(fixture, manifest, openApi)
            target.start()
            try {
                val proposed = post(
                    "/api/target-profiles/proposals",
                    genericProposal("Generic $mode ${UUID.randomUUID()}", target.address.port),
                    authorizationHeader(),
                )
                assertEquals(202, proposed.statusCode(), proposed.body())
                val versionId = objectMapper.readTree(proposed.body()).path("id").asString()
                val targetId = objectMapper.readTree(proposed.body()).path("targetSystemId").asString()
                val cookie = proposed.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
                val activated = post(
                    "/api/target-profiles/$versionId/activate",
                    """{"confirmation":"ACTIVATE_TARGET_PROFILE_VERSION"}""",
                    authorizationHeader() + mapOf("Cookie" to cookie),
                )
                assertEquals(202, activated.statusCode(), activated.body())
                val credentials = put(
                    "/api/targets/$targetId/runtime-credentials",
                    """{"roles":{"writer":"role-test-token"}}""",
                    executorAuthorizationHeader() + mapOf("Cookie" to cookie),
                )
                assertEquals(200, credentials.statusCode(), credentials.body())
                val candidateId = if (mode == "KEYED") "generic-idempotency-1" else "generic-async-1"
                val discovery = get("/api/targets/$targetId/pilot-discovery", mapOf("Cookie" to cookie))
                assertEquals(200, discovery.statusCode(), discovery.body())
                assertTrue(objectMapper.readTree(discovery.body()).path("candidates").any {
                    it.path("id").asString() == candidateId && it.path("readiness").asString() == "READY"
                }, discovery.body())
                val run = post(
                    "/api/targets/$targetId/pilot-template-runs",
                    """{"candidateIds":["$candidateId"],"confirmation":"EXECUTE_PILOT_TEMPLATES"}""",
                    executorAuthorizationHeader() + mapOf(
                        "Cookie" to cookie, "Idempotency-Key" to "generic-${UUID.randomUUID()}",
                    ),
                )
                assertEquals(201, run.statusCode(), run.body())
                assertContains(run.body(), "\"resultOutcome\":\"PASSED\"")
                assertContains(run.body(), "\"cleanupVerified\":true")

                manifest.set(manifest.get().replace("arl-jobs", "changed-jobs"))
                val changed = get("/api/targets/$targetId/pilot-discovery", mapOf("Cookie" to cookie))
                assertEquals(409, changed.statusCode(), changed.body())
                assertContains(changed.body(), "TARGET_CONTRACT_CHANGED")
            } finally {
                target.stop(0)
            }
        }
    }

    @Test
    fun `generic fixture recipe captures a numeric ID into a later request`() {
        val fixture = GenericFixture("tasks", "POST", "writer", "taskCount")
        val manifest = AtomicReference(
            """
            {"version":"1.0","capabilities":{"state":true,"reset":true,"readinessKinds":[],"faultTypes":[]},
            "operations":[
              {"operationId":"createTask","method":"POST","path":"/api/tasks","authProfile":"writer",
               "fixtureRecipe":{"kind":"SYNTHETIC_JSON_V1","inputs":[
                 {"pointer":"/name","source":"RUN_TAGGED_STRING","prefix":"arl-task"}]},
               "captures":{"id":"/id"},"observations":[{"field":"taskCount","expected":1}],"idempotency":"NONE"},
              {"operationId":"updateTask","method":"PUT","path":"/api/tasks","authProfile":"writer",
               "fixtureRecipe":{"kind":"SYNTHETIC_JSON_V1","inputs":[
                 {"pointer":"/parentId","source":"RUN_CAPTURE","operationId":"createTask","capture":"id"}]},
               "captures":{},"observations":[{"field":"taskCount","expected":2}],"idempotency":"NONE"}]}
            """.trimIndent(),
        )
        val openApi = AtomicReference(
            """
            {"openapi":"3.0.1","info":{"title":"Tasks","version":"1"},"paths":{
              "/health":{"get":{"operationId":"health","responses":{"200":{"description":"ok"}}}},
              "/api/tasks":{
                "get":{"operationId":"readTasks","responses":{"200":{"description":"ok"}}},
                "post":{"operationId":"createTask",
                  "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                    "required":["name"],"properties":{"name":{"type":"string"}}}}}},
                  "responses":{"201":{"description":"created","content":{"application/json":{"schema":{
                    "type":"object","properties":{"id":{"type":"integer"}}}}}}}},
                "put":{"operationId":"updateTask",
                  "requestBody":{"content":{"application/json":{"schema":{"type":"object",
                    "required":["parentId"],"properties":{"parentId":{"type":"integer"}}}}}},
                  "responses":{"201":{"description":"updated"}}}}}}
            """.trimIndent(),
        )
        val target = genericTarget(fixture, manifest, openApi)
        target.start()
        try {
            val approvedOpenApi = openApi.get()
            openApi.set(approvedOpenApi.replace("\"id\":{\"type\":\"integer\"}", "\"id\":{\"type\":\"string\"}"))
            val mismatched = post(
                "/api/target-profiles/proposals",
                genericProposal("Mismatched capture ${UUID.randomUUID()}", target.address.port),
                authorizationHeader(),
            )
            assertEquals(400, mismatched.statusCode(), mismatched.body())
            assertContains(mismatched.body(), "Fixture capture type does not match OpenAPI request field")
            openApi.set(approvedOpenApi)
            val proposed = post(
                "/api/target-profiles/proposals",
                genericProposal("Captured ID ${UUID.randomUUID()}", target.address.port),
                authorizationHeader(),
            )
            assertEquals(202, proposed.statusCode(), proposed.body())
            val versionId = objectMapper.readTree(proposed.body()).path("id").asString()
            val targetId = objectMapper.readTree(proposed.body()).path("targetSystemId").asString()
            val cookie = proposed.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
            assertEquals(202, post(
                "/api/target-profiles/$versionId/activate",
                """{"confirmation":"ACTIVATE_TARGET_PROFILE_VERSION"}""",
                authorizationHeader() + mapOf("Cookie" to cookie),
            ).statusCode())
            assertEquals(200, put(
                "/api/targets/$targetId/runtime-credentials",
                """{"roles":{"writer":"role-test-token"}}""",
                executorAuthorizationHeader() + mapOf("Cookie" to cookie),
            ).statusCode())
            val discovery = get("/api/targets/$targetId/pilot-discovery", mapOf("Cookie" to cookie))
            assertEquals(200, discovery.statusCode(), discovery.body())
            assertTrue(objectMapper.readTree(discovery.body()).path("candidates").any {
                it.path("id").asString() == "generic-write-2" &&
                    it.path("readiness").asString() == "READY"
            }, discovery.body())
            val run = post(
                "/api/targets/$targetId/pilot-template-runs",
                """{"candidateIds":["generic-write-2"],"confirmation":"EXECUTE_PILOT_TEMPLATES"}""",
                executorAuthorizationHeader() + mapOf(
                    "Cookie" to cookie, "Idempotency-Key" to "generic-${UUID.randomUUID()}",
                ),
            )
            assertEquals(201, run.statusCode(), run.body())
            assertContains(run.body(), "\"resultOutcome\":\"PASSED\"")
            assertContains(run.body(), "\"cleanupVerified\":true")
        } finally {
            target.stop(0)
        }
    }

    @Test
    fun `generic candidate is not ready when its run tagged fixture violates OpenAPI length`() {
        val fixture = GenericFixture("labels", "POST", "writer", "labelCount")
        val manifest = AtomicReference(genericManifest(fixture))
        val openApi = AtomicReference(
            genericOpenApi(fixture).replace(
                "\"name\":{\"type\":\"string\"}",
                "\"name\":{\"type\":\"string\",\"maxLength\":8}",
            ),
        )
        val target = genericTarget(fixture, manifest, openApi)
        target.start()
        try {
            val proposed = post(
                "/api/target-profiles/proposals",
                genericProposal("Limited fixture ${UUID.randomUUID()}", target.address.port),
                authorizationHeader(),
            )
            assertEquals(202, proposed.statusCode(), proposed.body())
            val versionId = objectMapper.readTree(proposed.body()).path("id").asString()
            val targetId = objectMapper.readTree(proposed.body()).path("targetSystemId").asString()
            val cookie = proposed.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
            assertEquals(202, post(
                "/api/target-profiles/$versionId/activate",
                """{"confirmation":"ACTIVATE_TARGET_PROFILE_VERSION"}""",
                authorizationHeader() + mapOf("Cookie" to cookie),
            ).statusCode())
            val discovery = get("/api/targets/$targetId/pilot-discovery", mapOf("Cookie" to cookie))
            assertEquals(200, discovery.statusCode(), discovery.body())
            assertTrue(objectMapper.readTree(discovery.body()).path("candidates").any {
                it.path("id").asString() == "generic-write-1" &&
                    it.path("readiness").asString() == "NOT_READY" &&
                    it.path("missingOperations").toString().contains("OpenAPI-compatible fixture")
            }, discovery.body())
        } finally {
            target.stop(0)
        }
    }

    @Test
    fun `activation rejects changes to fixture and observation semantics`() {
        val fixture = GenericFixture("drift", "POST", "creator", "recordCount")
        val original = genericManifest(fixture)
        val manifest = AtomicReference(original)
        val target = genericTarget(fixture, manifest)
        target.start()
        try {
            listOf(
                original.replace("arl-drift", "other-drift"),
                original.replace("\"expected\":1", "\"expected\":2"),
                original.replace("\"idempotency\":\"NONE\"", "\"idempotency\":\"KEYED\""),
            ).forEach { changed ->
                manifest.set(original)
                val proposed = post(
                    "/api/target-profiles/proposals",
                    genericProposal("Drift ${UUID.randomUUID()}", target.address.port),
                    authorizationHeader(),
                )
                assertEquals(202, proposed.statusCode(), proposed.body())
                val versionId = objectMapper.readTree(proposed.body()).path("id").asString()
                val cookie = proposed.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
                manifest.set(changed)
                val activation = post(
                    "/api/target-profiles/$versionId/activate",
                    "{\"confirmation\":\"ACTIVATE_TARGET_PROFILE_VERSION\"}",
                    authorizationHeader() + mapOf("Cookie" to cookie),
                )
                assertEquals(400, activation.statusCode(), activation.body())
                assertContains(activation.body(), "changed since Profile proposal")
                assertContains(get("/api/target-profiles/$versionId").body(), "\"status\":\"DRAFT\"")
            }
        } finally {
            target.stop(0)
        }
    }

    @Test
    fun `generic proposal rejects missing nested required fixture field`() {
        val fixture = GenericFixture("nested", "POST", "creator", "recordCount")
        val original = genericManifest(fixture)
        val manifest = AtomicReference(original.replace("\"/name\"", "\"/address/street\""))
        val openApi = AtomicReference(genericOpenApi(fixture).replace(
            "\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\"}}",
            "\"required\":[\"address\"],\"properties\":{\"address\":{\"type\":\"object\"," +
                "\"required\":[\"street\",\"city\"],\"properties\":{\"street\":{\"type\":\"string\"}," +
                "\"city\":{\"type\":\"string\"}}}}",
        ))
        val target = genericTarget(fixture, manifest, openApi)
        target.start()
        try {
            val request = genericProposal("Nested ${UUID.randomUUID()}", target.address.port)
            val missing = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(400, missing.statusCode(), missing.body())
            assertContains(missing.body(), "does not cover required OpenAPI request fields")

            manifest.set(manifest.get().replace(
                "\"prefix\":\"arl-nested\"",
                "\"prefix\":\"arl-nested\"},{\"pointer\":\"/address/city\"," +
                    "\"source\":\"RUN_TAGGED_STRING\",\"prefix\":\"arl-city\"",
            ))
            val complete = post("/api/target-profiles/proposals", request, authorizationHeader())
            assertEquals(202, complete.statusCode(), complete.body())
        } finally {
            target.stop(0)
        }
    }

    @Test
    fun `generic proposal rejects a target with only protected GET operations`() {
        val fixture = GenericFixture("private", "POST", "creator", "recordCount")
        val manifest = AtomicReference(genericManifest(fixture))
        val openApi = AtomicReference(genericOpenApi(fixture).replace(
            "\"/health\":{\"get\":{\"operationId\":\"health\",\"responses\":{\"200\":{\"description\":\"ok\"}}}},",
            "",
        ))
        val target = genericTarget(fixture, manifest, openApi)
        target.start()
        try {
            val response = post(
                "/api/target-profiles/proposals",
                genericProposal("Protected ${UUID.randomUUID()}", target.address.port),
                authorizationHeader(),
            )
            assertEquals(400, response.statusCode(), response.body())
            assertContains(response.body(), "publicly reachable static GET")
        } finally {
            target.stop(0)
        }
    }

    private data class GenericFixture(val resource: String, val method: String, val role: String, val field: String)

    private fun genericProposal(name: String, port: Int): String = objectMapper.writeValueAsString(mapOf(
        "name" to name, "baseUrl" to "http://127.0.0.1:$port", "environment" to "TEST",
        "openApiPaths" to listOf("/openapi.json"), "harnessKey" to "test-harness-key",
    ))

    private fun genericManifest(fixture: GenericFixture): String = """
        {"version":"1.0","capabilities":{"state":true,"reset":true,"readinessKinds":[],"faultTypes":[]},
         "operations":[{"operationId":"write${fixture.resource}","method":"${fixture.method}",
         "path":"/api/${fixture.resource}","authProfile":"${fixture.role}",
         "fixtureRecipe":{"kind":"SYNTHETIC_JSON_V1","inputs":[
           {"pointer":"/name","source":"RUN_TAGGED_STRING","prefix":"arl-${fixture.resource}"}]},
         "captures":{},"observations":[{"field":"${fixture.field}","expected":1}],"idempotency":"NONE"}]}
    """.trimIndent()

    private fun genericOpenApi(fixture: GenericFixture): String = """
        {"openapi":"3.0.1","info":{"title":"Generic","version":"1"},"paths":{
        "/health":{"get":{"operationId":"health","responses":{"200":{"description":"ok"}}}},
        "/api/${fixture.resource}":{
        "get":{"operationId":"read${fixture.resource}",
        "responses":{"200":{"description":"ok"}}},
        "${fixture.method.lowercase()}":{"operationId":"write${fixture.resource}",
        "requestBody":{"content":{"application/json":{"schema":{"type":"object",
        "required":["name"],"properties":{"name":{"type":"string"}}}}}},
        "responses":{"201":{"description":"created"}}}}}}
    """.trimIndent()

    private fun genericTarget(
        fixture: GenericFixture,
        manifest: AtomicReference<String>,
        openApi: AtomicReference<String> = AtomicReference(genericOpenApi(fixture)),
        publicRead: Boolean = false,
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            val runCounts = ConcurrentHashMap<String, AtomicInteger>()
            val idempotencyKeys = ConcurrentHashMap.newKeySet<String>()
            createContext("/openapi.json") { exchange ->
                val document = openApi.get().toByteArray()
                exchange.sendResponseHeaders(200, document.size.toLong())
                exchange.responseBody.use { it.write(document) }
            }
            createContext("/health") { exchange ->
                exchange.sendResponseHeaders(200, -1)
            }
            createContext("/api/harness/manifest") { exchange ->
                val document = manifest.get().toByteArray()
                val valid = exchange.requestHeaders.getFirst("X-ARL-Harness-Key") == "test-harness-key"
                exchange.responseHeaders.add("X-ARL-Harness-Version", "1")
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (valid) 200 else 401, if (valid) document.size.toLong() else -1)
                if (valid) exchange.responseBody.use { it.write(document) }
            }
            createContext("/api/harness/state") { exchange ->
                val runId = exchange.requestHeaders.getFirst("X-ARL-Run-Id")
                val count = runCounts[runId]?.get() ?: 0
                val document = """{"version":"1.0","runId":"$runId","${fixture.field}":$count}""".toByteArray()
                exchange.responseHeaders.add("X-ARL-Harness-Version", "1")
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, document.size.toLong())
                exchange.responseBody.use { it.write(document) }
            }
            createContext("/api/harness/reset") { exchange ->
                val runId = exchange.requestHeaders.getFirst("X-ARL-Run-Id")
                val removed = runCounts.remove(runId)?.get() ?: 0
                val document = """{"version":"1.0","runId":"$runId","clean":true,"removedFixtureCount":$removed,"activeFaultCount":0}""".toByteArray()
                exchange.responseHeaders.add("X-ARL-Harness-Version", "1")
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, document.size.toLong())
                exchange.responseBody.use { it.write(document) }
            }
            createContext("/api/harness/readiness") { exchange ->
                val runId = exchange.requestHeaders.getFirst("X-ARL-Run-Id")
                val document = """{"version":"1.0","runId":"$runId","ready":true,"reason":"fixture visible"}""".toByteArray()
                exchange.responseHeaders.add("X-ARL-Harness-Version", "1")
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, document.size.toLong())
                exchange.responseBody.use { it.write(document) }
            }
            createContext("/api/${fixture.resource}") { exchange ->
                val valid = exchange.requestHeaders.getFirst("Authorization") == "Bearer role-test-token"
                if (valid && exchange.requestMethod in setOf(fixture.method, "PUT")) {
                    if (exchange.requestMethod == "PUT" && manifest.get().contains("updateTask")) {
                        val body = objectMapper.readTree(exchange.requestBody.readAllBytes())
                        if (!body.path("parentId").isNumber) {
                            exchange.sendResponseHeaders(400, -1)
                            return@createContext
                        }
                    }
                    val runId = exchange.requestHeaders.getFirst("X-ARL-Run-Id")
                    val key = exchange.requestHeaders.getFirst("Idempotency-Key")
                    if (key == null || idempotencyKeys.add("$runId:$key")) {
                        runCounts.computeIfAbsent(runId) { AtomicInteger() }.incrementAndGet()
                    }
                    val document = if (fixture.resource == "tasks") """{"id":42}""".toByteArray()
                        else """{"id":"synthetic"}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(201, document.size.toLong())
                    exchange.responseBody.use { it.write(document) }
                } else {
                    exchange.sendResponseHeaders(if (valid || publicRead && exchange.requestMethod == "GET") 200 else 401, -1)
                }
            }
        }

    @Test
    fun `validates imports and explicitly activates a versioned profile`() {
        val targetId = "imported-${UUID.randomUUID().toString().take(8)}"
        val import = post("/api/target-profiles", profileJson(validProfile(targetId)), authorizationHeader())

        assertEquals(202, import.statusCode(), import.body())
        assertContains(import.body(), "\"status\":\"DRAFT\"")
        val versionId = "\"id\":\"([^\"]+)\"".toRegex().find(import.body())?.groupValues?.get(1)
            ?: error("Imported Target Profile version id was absent")

        val draft = get("/api/target-profiles/$versionId")
        assertEquals(200, draft.statusCode())
        assertContains(draft.body(), "\"status\":\"DRAFT\"")

        val inactiveCandidates = get("/api/targets/$targetId/test-candidates")
        assertEquals(404, inactiveCandidates.statusCode())

        val activated = post(
            "/api/target-profiles/$versionId/activate",
            "{\"confirmation\":\"ACTIVATE_TARGET_PROFILE_VERSION\"}",
            authorizationHeader(),
        )
        assertEquals(202, activated.statusCode())
        assertContains(activated.body(), "\"status\":\"ACTIVE\"")

        val executionProfile = testSpecProfiles.requireActive(targetId)
        assertEquals(UUID.fromString(versionId), executionProfile.profileVersionId)
        assertTrue(
            executionProfile.capabilities.allows(
                SpecHttpCall("GET", "/api/read-only", "reader", emptyMap(), null),
            ),
        )
        assertEquals(2, executionProfile.capabilities.maxConcurrency)
        assertEquals(
            "PROMETHEUS",
            executionProfile.capabilities.observationSources.getValue("metrics").kind.name,
        )
        assertEquals(
            "up",
            executionProfile.capabilities.observationSources.getValue("metrics").queries["httpUp"],
        )
        assertEquals(CleanupMethod.NOT_REQUIRED, executionProfile.resetPlan.method)

        val candidates = get("/api/targets/$targetId/test-candidates")
        assertEquals(200, candidates.statusCode())
        assertContains(candidates.body(), "imported-read-only")
        assertFalse(candidates.body().contains("access_token"))

        assertPendingBatchIsCancelledWhenItsProfileIsReplaced(targetId)
    }

    @Test
    fun `quick registration discovers an allowlisted OpenAPI document and activates the standard profile`() {
        val target = openApiTarget()
        target.start()
        try {
            val response = post(
                "/api/target-profiles/quick-register",
                objectMapper.writeValueAsString(
                    mapOf(
                        "name" to "Quick Target ${UUID.randomUUID().toString().take(8)}",
                        "baseUrl" to "http://127.0.0.1:${target.address.port}",
                        "environment" to "TEST",
                    ),
                ),
                authorizationHeader(),
            )

            assertEquals(201, response.statusCode(), response.body())
            assertContains(response.body(), "\"status\":\"ACTIVE\"")
            assertContains(response.body(), "\"openApiPaths\":[\"/v3/api-docs\"]")

            val registered = get("/api/target-profiles?source=USER_IMPORT")
            assertEquals(200, registered.statusCode(), registered.body())
            assertContains(registered.body(), "\"source\":\"USER_IMPORT\"")
            assertContains(registered.body(), "\"baseUrl\":\"http://127.0.0.1:${target.address.port}\"")

            // Nothing quick registration filled in may stay hidden: a default that gates a run must be readable.
            val versionId = Regex("\"id\":\"([0-9a-f-]{36})\"").find(response.body())!!.groupValues[1]
            val effective = get("/api/target-profiles/$versionId/effective-settings")
            assertEquals(200, effective.statusCode(), effective.body())
            assertContains(effective.body(), "\"harnessStatePath\":\"/api/harness/state\"")
            assertContains(effective.body(), "\"harnessResetPath\":\"/api/harness/reset\"")
            assertContains(effective.body(), "\"harnessFaultPath\":\"/api/harness/fault\"")
            assertContains(effective.body(), "\"harnessFaultReleasePath\":\"/api/harness/fault/release\"")
            assertContains(effective.body(), "\"allowedCidrs\":[\"127.0.0.1/32\"]")
            assertContains(effective.body(), "generatedYaml")
            assertContains(effective.body(), "allowed-cidrs")

            val generatedYaml = objectMapper.readTree(effective.body()).path("generatedYaml").asString()
            assertContains(generatedYaml, "source-repository")
            assertContains(generatedYaml, "fault-injection")
            listOf(
                "productCount", "orderCount", "paymentCount", "completedPaymentCount",
                "failedPaymentCount", "activeFaultCount",
            ).forEach { field -> assertContains(generatedYaml, "response.body.$field") }
            val advancedImport = post(
                "/api/target-profiles",
                objectMapper.writeValueAsString(mapOf("yaml" to generatedYaml)),
                authorizationHeader(),
            )
            assertEquals(202, advancedImport.statusCode(), advancedImport.body())
            assertContains(advancedImport.body(), "\"status\":\"DRAFT\"")
        } finally {
            target.stop(0)
        }
    }

    private fun assertPendingBatchIsCancelledWhenItsProfileIsReplaced(targetId: String) {
        val unapprovedCaller = post(
            "/api/test-batches",
            "{\"targetSystemId\":\"$targetId\",\"candidateIds\":[\"imported-read-only\"]}",
            mapOf("Idempotency-Key" to "unauthorized-$targetId"),
        )
        assertEquals(403, unapprovedCaller.statusCode())

        val pendingBatch = post(
            "/api/test-batches",
            "{\"targetSystemId\":\"$targetId\",\"candidateIds\":[\"imported-read-only\"]}",
            executorAuthorizationHeader() + mapOf("Idempotency-Key" to "pending-$targetId"),
        )
        assertEquals(202, pendingBatch.statusCode())
        val batchId = "\"id\":\"([^\"]+)\"".toRegex().find(pendingBatch.body())?.groupValues?.get(1)
            ?: error("Target test batch id was absent")

        val replacement = post(
            "/api/target-profiles",
            profileJson(validProfile(targetId, executionEnabled = false)),
            authorizationHeader(),
        )
        assertEquals(202, replacement.statusCode(), replacement.body())
        val replacementVersionId = "\"id\":\"([^\"]+)\"".toRegex().find(replacement.body())?.groupValues?.get(1)
            ?: error("Replacement Target Profile version id was absent")
        assertEquals(
            202,
            post(
                "/api/target-profiles/$replacementVersionId/activate",
                "{\"confirmation\":\"ACTIVATE_TARGET_PROFILE_VERSION\"}",
                authorizationHeader(),
            ).statusCode(),
        )

        val approval = post(
            "/api/test-batches/$batchId/approve",
            "{\"confirmation\":\"EXECUTE_SAFE_HTTP_BATCH\"}",
            executorAuthorizationHeader(),
        )
        assertEquals(202, approval.statusCode())
        assertContains(approval.body(), "\"status\":\"CANCELLED\"")
    }

    @Test
    fun `rejects unauthorised imports and query strings in Profile paths`() {
        val targetId = "unsafe-${UUID.randomUUID().toString().take(8)}"
        val unauthorised = post("/api/target-profiles", profileJson(validProfile(targetId)))
        assertEquals(403, unauthorised.statusCode())

        val unsafePath = validProfile(targetId).replace("/api/read-only", "/api/read-only?access_token=secret")
        val validation = post("/api/target-profiles/validate", profileJson(unsafePath), authorizationHeader())
        assertEquals(400, validation.statusCode(), validation.body())
        assertContains(validation.body(), "fixed relative HTTP path")

        val unsafeOrigin = validProfile(targetId).replace("http://127.0.0.1:18080", "http://127.0.0.1:18080/api/v1")
        val originValidation = post("/api/target-profiles/validate", profileJson(unsafeOrigin), authorizationHeader())
        assertEquals(400, originValidation.statusCode(), originValidation.body())
        assertContains(originValidation.body(), "only an HTTP(S) origin")

        val unsafeOpenApiPath = validProfile(targetId).replace(
            "health-path: /actuator/health",
            "health-path: /actuator/health\n        openapi-path: https://example.test/v3/api-docs",
        )
        val openApiValidation = post(
            "/api/target-profiles/validate",
            profileJson(unsafeOpenApiPath),
            authorizationHeader(),
        )
        assertEquals(400, openApiValidation.statusCode(), openApiValidation.body())
        assertContains(openApiValidation.body(), "fixed relative HTTP path")

        val mismatchedQuery = validProfile(targetId).replace("httpUp: up", "otherField: up")
        val sourceValidation = post(
            "/api/target-profiles/validate",
            profileJson(mismatchedQuery),
            authorizationHeader(),
        )
        assertEquals(400, sourceValidation.statusCode(), sourceValidation.body())
        assertContains(sourceValidation.body(), "exactly one query for every field")

        val malformedPrometheus = validProfile(targetId)
            .replace("http://127.0.0.1:19090/prometheus", "http://[")
        val malformedSourceValidation = post(
            "/api/target-profiles/validate",
            profileJson(malformedPrometheus),
            authorizationHeader(),
        )
        assertEquals(400, malformedSourceValidation.statusCode(), malformedSourceValidation.body())
        assertContains(malformedSourceValidation.body(), "not a valid URI")

        val oversized = post("/api/target-profiles/validate", profileJson("x".repeat(262_144)))
        assertEquals(413, oversized.statusCode())
    }

    @Test
    fun `bounded OpenAPI parser rejects external references`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            openApiParser.parse(
                """
                {
                  "openapi": "3.0.1",
                  "paths": {
                    "/products": {
                      "${'$'}ref": "https://example.test/paths.json"
                    }
                  }
                }
                """.trimIndent(),
            )
        }

        assertContains(exception.message.orEmpty(), "external references")
    }

    private fun authorizationHeader(): Map<String, String> =
        mapOf("Authorization" to "Bearer profile-editor-test-token")

    private fun executorAuthorizationHeader(): Map<String, String> =
        mapOf("Authorization" to "Bearer executor-test-token")

    private fun openApiTarget(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v3/api-docs") { exchange ->
            val document = """
                {"openapi":"3.0.1","info":{"title":"Quick Target","version":"1"},"paths":{
                  "/api/products":{"get":{"operationId":"getProducts","responses":{"200":{"description":"ok"}}}}
                }}
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, document.size.toLong())
            exchange.responseBody.use { body -> body.write(document) }
        }
        createContext("/") { exchange -> exchange.sendResponseHeaders(404, -1) }
    }

    private fun validProfile(targetId: String, executionEnabled: Boolean = true): String = """
        arl:
          targets:
            registrations:
              - id: $targetId
                name: Imported Test Target
                adapter-type: HTTP_TARGET
                environment: TEST
                base-url: http://127.0.0.1:18080
                allowed-origin: http://127.0.0.1:18080
                allowed-cidrs: [127.0.0.0/8]
                health-path: /actuator/health
                source-repository: imported-test-target
                identity-verification: CONFIGURATION_ONLY
                capabilities: [HEALTH, HTTP_API]
          target-specs:
            registrations:
              - target-system-id: $targetId
                execution-enabled: $executionEnabled
                host-resource-group: imported-test-target
                max-batch-size: 2
                request-timeout: 2s
                read-only-operations:
                  - id: imported-read-only
                    title: Imported read-only operation
                    description: Verifies that an imported Profile supplies a safe read-only candidate.
                    path: /api/read-only
                    expected-status-codes: [200]
          test-spec-execution:
            registrations:
              - target-system-id: $targetId
                execution-enabled: true
                allowed-calls:
                  - method: GET
                    path: /api/read-only
                    auth-profile: reader
                auth-profiles: [reader]
                observation-sources:
                  - name: harness
                    kind: HARNESS_STATE
                    endpoint: /harness/state
                    fields: [dbStock]
                    auth-profile: reader
                  - name: metrics
                    kind: PROMETHEUS
                    endpoint: http://127.0.0.1:19090/prometheus
                    fields: [httpUp]
                    queries:
                      httpUp: up
                max-concurrency: 2
                max-request-count: 20
                max-trials: 5
                state-changing-allowed: false
                reset:
                  method: NOT_REQUIRED
    """.trimIndent()

    private fun profileJson(yaml: String): String = "{\"yaml\":${objectMapper.writeValueAsString(yaml)}}"

    private fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val request = request(path).header("Authorization", "Bearer profile-editor-test-token")
        headers.forEach { (name, value) -> request.header(name, value) }
        return httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        path: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val request = request(path).header("Content-Type", "application/json")
        headers.forEach { (name, value) -> request.header(name, value) }
        return httpClient.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun put(path: String, body: String, headers: Map<String, String>): HttpResponse<String> {
        val request = request(path).header("Content-Type", "application/json")
        headers.forEach { (name, value) -> request.header(name, value) }
        return httpClient.send(
            request.PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI("http://127.0.0.1:$serverPort$path"))
        .timeout(Duration.ofSeconds(3))

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun accessProperties(registry: DynamicPropertyRegistry) {
            registry.add("arl.access.mode") { "SECURED" }
            registry.add("arl.access.profile-editor-token") { "profile-editor-test-token" }
            registry.add("arl.access.executor-token") { "executor-test-token" }
            registry.add("arl.access.viewer-token") { "viewer-test-token" }
        }
    }
}
