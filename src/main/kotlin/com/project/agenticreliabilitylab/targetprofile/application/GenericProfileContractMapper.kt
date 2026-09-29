package com.project.agenticreliabilitylab.targetprofile.application

import com.project.agenticreliabilitylab.targetprofile.domain.GenericHttpProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileFaultInjectionDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileHttpCallDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileObservationSourceKind
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileReadTimingDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ProfileResetVerificationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.ReadOnlyOperationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TargetRegistrationDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.TestSpecExecutionProfileDefinition
import com.project.agenticreliabilitylab.targetprofile.domain.toRegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.target.domain.TargetReadTransportException
import com.project.agenticreliabilitylab.targetprofiledraft.application.BoundedOpenApiDocumentParser
import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Intersects bounded OpenAPI documents with a V1 manifest before proposing executable authority. */
@Component
@Suppress("TooManyFunctions") // Contract validation, read selection and fingerprinting share one authority boundary.
class GenericProfileContractMapper(
    private val openApiParser: BoundedOpenApiDocumentParser,
    private val objectMapper: ObjectMapper,
    private val transport: TargetReadTransport,
) {
    // One bounded contract-to-Profile mapping keeps authority checks together.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    fun map(
        target: TargetRegistrationDefinition,
        openApiDocuments: List<String>,
        manifestDocument: String,
        stateDocument: Pair<String, String>?,
    ): TargetProfileDefinition {
        val openApi = openApiDocuments.flatMap(::operations)
        require(openApi.isNotEmpty()) { "OpenAPI contains no supported operations" }
        val manifest = try {
            objectMapper.readValue(manifestDocument, MAP_TYPE)
        } catch (exception: JacksonException) {
            throw IllegalArgumentException("Harness manifest must be valid JSON", exception)
        }
        require(manifest.text("version") == "1.0") { "Unsupported Harness manifest version" }
        val capabilities = manifest.objectValue("capabilities")
        val hasState = capabilities["state"] == true
        val hasReset = capabilities["reset"] == true
        val readinessKinds = capabilities.stringList("readinessKinds")
        require(readinessKinds.all(READINESS_KIND::matches)) { "Invalid readiness kind" }
        val faults = capabilities.stringList("faultTypes").toSet()
        val declared = manifest.listValue("operations")
        require(declared.size <= MAX_OPERATIONS) { "Harness manifest declares too many operations" }
        require(declared.isEmpty() || hasState && hasReset) { "Write mappings require Harness state and reset" }
        val writes = declared.mapIndexed { index, value ->
            val operation = value.asObject("manifest operation $index")
            val method = operation.text("method")
            val path = operation.text("path")
            require(operation.text("authProfile") != "harness") {
                "Business operations cannot use the Harness credential"
            }
            require(method in WRITE_METHODS) { "Unsupported manifest method '$method'" }
            val matched = openApi.singleOrNull { it.method == method && it.path == path }
                ?: throw IllegalArgumentException("Manifest operation $method $path is absent or ambiguous in OpenAPI")
            require(matched.successCodes.isNotEmpty()) { "OpenAPI operation $method $path has no success response" }
            operation.optionalText("operationId")?.let { id ->
                require(id == matched.operationId) { "Manifest operationId for $method $path differs from OpenAPI" }
            }
            require(matched.requestSchema != null) {
                "Manifest operation $method $path has no supported JSON request body"
            }
            validateRecipe(operation.objectValue("fixtureRecipe"), matched.requestSchema, declared.take(index), openApi)
            operation.objectValue("captures").forEach { (_, pointerValue) ->
                val pointer = pointerValue as? String ?: throw IllegalArgumentException("Invalid capture pointer")
                require(POINTER.matches(pointer) && matched.responseSchema != null &&
                    matched.responseSchema.property(pointer)["type"] in SCALAR_TYPES) {
                    "Manifest capture must name a scalar OpenAPI response field"
                }
            }
            val observations = operation.listValue("observations")
            require(observations.isNotEmpty()) { "Manifest operation $method $path has no observations" }
            val observationFields = observations.map { it.asObject("observation").text("field") }
            require(observationFields.distinct().size == observations.size) {
                "Manifest operation $method $path has duplicate observation fields"
            }
            observations.forEach { entry ->
                val observation = entry.asObject("observation")
                require(FIELD.matches(observation.text("field"))) { "Invalid observation field" }
                require(observation["expected"] is String || observation["expected"] is Number ||
                    observation["expected"] is Boolean) { "Observation expected value must be scalar" }
            }
            require(operation.text("idempotency") in setOf("NONE", "KEYED")) { "Invalid idempotency semantics" }
            operation.optionalText("readinessKind")?.let { kind ->
                require(kind in readinessKinds) { "Readiness kind '$kind' is not declared" }
            }
            ProfileHttpCallDefinition(method, path, operation.text("authProfile"), matched.operationId)
        }
        require(writes.distinctBy { it.method to it.path }.size == writes.size) {
            "Manifest contains duplicate write operations"
        }
        val readOperations = openApi.filter {
            it.method == "GET" && it.requestSchema == null && it.staticPath && it.public
        }
            .filter { it.successCodes.isNotEmpty() }
            .distinctBy { it.path }
            .take(MAX_OPERATIONS)
            .filter { operation -> publiclyReachable(target, operation) }
            .mapIndexed { index, operation ->
                ReadOnlyOperationDefinition(
                    id = "read-${index + 1}", title = "GET ${operation.path}",
                    description = "OpenAPI declared read operation", path = operation.path,
                    expectedStatusCodes = operation.successCodes, operationId = operation.operationId,
                )
            }
        require(readOperations.isNotEmpty()) {
            "OpenAPI needs a publicly reachable static GET for the Target health path"
        }
        val fields = declared.flatMap { value ->
            value.asObject("manifest operation").listValue("observations")
                .map { entry -> entry.asObject("observation").text("field") }
        }.toSet()
        if (writes.isNotEmpty()) validateBaseline(stateDocument, fields)
        val state = ProfileHttpCallDefinition("GET", STATE_PATH, "harness")
        val reset = ProfileHttpCallDefinition("POST", RESET_PATH, "harness")
        val readCalls = readOperations.map { read ->
            ProfileHttpCallDefinition("GET", read.path, null, read.operationId)
        }
        val roleReadCalls = openApi.filter { it.method == "GET" && it.staticPath && it.successCodes.isNotEmpty() }
            .mapNotNull { read ->
                val role = writes.filter { it.path == read.path }
                    .mapNotNull { it.authProfile }.distinct().singleOrNull()
                role?.let { ProfileHttpCallDefinition("GET", read.path, it, read.operationId) }
            }
        val readinessCalls = declared.mapNotNull { value ->
            value.asObject("manifest operation").optionalText("readinessKind")?.let { kind ->
                ProfileHttpCallDefinition("GET", "/api/harness/readiness/$kind/{id}", "harness")
            }
        }
        val calls = (readCalls + roleReadCalls + writes + readinessCalls +
            if (writes.isNotEmpty()) listOf(state) else emptyList()).distinctBy {
            Triple(it.method, it.path, it.authProfile)
        }
        val execution = TestSpecExecutionProfileDefinition(
            executionEnabled = true,
            allowedCalls = calls,
            authProfiles = writes.mapTo(linkedSetOf()) { it.authProfile!! } +
                if (writes.isNotEmpty()) setOf("harness") else emptySet(),
            observationSources = if (writes.isNotEmpty()) listOf(
                ProfileObservationSourceDefinition(
                    "harness", ProfileObservationSourceKind.HARNESS_STATE, STATE_PATH, fields, authProfile = "harness",
                ),
            ) else emptyList(),
            supportedFaults = faults.takeIf { writes.isNotEmpty() }.orEmpty(),
            infrastructureTargets = emptySet(),
            maxConcurrency = MAX_CONCURRENCY, maxRequestCount = MAX_REQUEST_COUNT,
            maxTrials = MAX_TRIALS,
            stateChangingAllowed = writes.isNotEmpty(),
            reset = if (writes.isNotEmpty()) ProfileResetDefinition(
                CleanupMethod.ENVIRONMENT_RESET, reset, Duration.ofSeconds(30),
                fields.map { field ->
                    ProfileResetVerificationDefinition(
                        field, state, "response.body.$field", "$field == 0",
                        ProfileReadTimingDefinition(StabilityRule.IMMEDIATE, Duration.ZERO, Duration.ZERO),
                    )
                },
            ) else ProfileResetDefinition(CleanupMethod.NOT_REQUIRED, null, Duration.ZERO, emptyList()),
            faultInjection = if (faults.isNotEmpty() && writes.isNotEmpty()) ProfileFaultInjectionDefinition(
                ProfileHttpCallDefinition("POST", "/api/harness/fault", "harness"),
                ProfileHttpCallDefinition("POST", "/api/harness/fault/release", "harness"),
                Duration.ofSeconds(120),
            ) else null,
        )
        return TargetProfileDefinition(
            target = target.copy(
                healthPath = readOperations.first().path,
                contractSha256 = contractSha256(openApiDocuments, manifestDocument),
                openApiSha256 = sha256(openApiDocuments),
            ),
            genericHttp = GenericHttpProfileDefinition(
                true, target.id, MAX_BATCH_SIZE, Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
                readOperations, false, emptyList(),
            ),
            testSpecExecution = execution,
        )
    }

    private fun validateBaseline(stateDocument: Pair<String, String>?, fields: Set<String>) {
        require(stateDocument != null) { "Harness state is required for write mappings" }
        val state = try {
            objectMapper.readValue(stateDocument.second, MAP_TYPE)
        } catch (exception: JacksonException) {
            throw IllegalArgumentException("Harness state must be valid JSON", exception)
        }
        require(state.text("version") == "1.0" && state.text("runId") == stateDocument.first) {
            "Harness state has an invalid version or run ID"
        }
        require(fields.all { state[it] is Number && (state[it] as Number).toDouble() == 0.0 }) {
            "Harness state must show a zero baseline for each mapped observation"
        }
    }

    private fun validateRecipe(
        recipe: Map<String, Any?>, schema: Map<String, Any?>, previous: List<Any?>, openApi: List<OpenApiOperation>,
    ) {
        require(recipe.text("kind") == "SYNTHETIC_JSON_V1") { "Unsupported fixture recipe" }
        val inputs = recipe.listValue("inputs")
        require(inputs.isNotEmpty()) { "Fixture recipe needs inputs" }
        val pointers = inputs.map { value ->
            val input = value.asObject("fixture input")
            val pointer = input.text("pointer")
            require(POINTER.matches(pointer)) { "Invalid fixture input pointer" }
            val property = schema.property(pointer)
            when (input.text("source")) {
                "RUN_TAGGED_STRING" -> {
                    require(property["type"] == "string") { "Fixture string does not match OpenAPI type" }
                    require(PREFIX.matches(input.text("prefix"))) { "Invalid fixture prefix" }
                }
                "BOUNDED_INTEGER" -> {
                    val number = (input["value"] as? Number)?.toLong()
                    require(property["type"] == "integer" && number in 0L..10_000L &&
                        (property["minimum"] as? Number)?.toLong()?.let { number!! >= it } != false &&
                        (property["maximum"] as? Number)?.toLong()?.let { number!! <= it } != false) {
                        "Fixture integer is outside the OpenAPI range"
                    }
                }
                "RUN_CAPTURE" -> {
                    require(property["type"] in SCALAR_TYPES) { "Capture input must target a scalar field" }
                    val producer = previous.map { it.asObject("manifest operation") }.firstOrNull {
                        it.optionalText("operationId") == input.text("operationId")
                    }
                    require(producer != null && input.text("capture") in producer.objectValue("captures")) {
                        "Fixture capture does not refer to an earlier operation"
                    }
                    validateCaptureType(producer, input.text("capture"), property["type"], openApi)
                }
                else -> throw IllegalArgumentException("Unsupported fixture input source")
            }
            pointer
        }
        require(pointers.size == pointers.distinct().size) { "Fixture recipe has duplicate pointers" }
        val required = requiredPointers(schema)
        require(required.all { needed -> pointers.any { it == needed || it.startsWith("$needed/") } }) {
            "Fixture recipe does not cover required OpenAPI request fields"
        }
    }

    private fun validateCaptureType(
        producer: Map<String, Any?>, capture: String, consumedType: Any?, openApi: List<OpenApiOperation>,
    ) {
        val producerOperation = openApi.singleOrNull {
            it.method == producer.text("method") && it.path == producer.text("path")
        } ?: throw IllegalArgumentException("Fixture capture producer is absent or ambiguous in OpenAPI")
        val capturePointer = producer.objectValue("captures").getValue(capture) as String
        val producedType = producerOperation.responseSchema?.property(capturePointer)?.get("type")
        require(producedType == consumedType || producedType == "integer" && consumedType == "number") {
            "Fixture capture type does not match OpenAPI request field"
        }
    }

    private fun requiredPointers(schema: Map<String, Any?>, parent: String = ""): List<String> =
        (schema["required"] as? List<*>)?.flatMap { raw ->
            val name = raw as? String ?: throw IllegalArgumentException("OpenAPI required field is invalid")
            require(FIELD.matches(name)) { "OpenAPI required field is invalid" }
            val pointer = "$parent/$name"
            val property = schema.objectValue("properties")[name].asObject("OpenAPI property")
            if (property["type"] == "object") requiredPointers(property, pointer).ifEmpty { listOf(pointer) }
            else listOf(pointer)
        }.orEmpty()

    private fun publiclyReachable(target: TargetRegistrationDefinition, operation: OpenApiOperation): Boolean {
        val registered = target.toRegisteredTarget(Instant.EPOCH, Instant.EPOCH)
        return try {
            transport.send(
                registered, registered.baseUri.resolve(operation.path), "GET", emptyMap(), ByteArray(0),
                Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
            ).statusCode in operation.successCodes
        } catch (_: TargetReadTransportException) {
            false
        }
    }

    private fun contractSha256(documents: List<String>, manifest: String): String =
        sha256(documents + manifest)

    private fun sha256(documents: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(documents.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun operations(document: String): List<OpenApiOperation> {
        val root = openApiParser.parse(document)
        require((root["openapi"] as? String)?.startsWith("3.") == true) { "Unsupported OpenAPI version" }
        return root.objectValue("paths").flatMap { (path, rawPath) ->
            val pathItem = rawPath.asObject("OpenAPI path")
            METHODS.mapNotNull { method ->
                val operation = (pathItem[method.lowercase()] as? Map<*, *>)?.asObject("OpenAPI operation")
                    ?: return@mapNotNull null
                val responseCodes = operation.objectValue("responses").keys.mapNotNull(String::toIntOrNull)
                    .filterTo(linkedSetOf()) { it in 200..299 }
                val schema = (operation["requestBody"] as? Map<*, *>)?.asObject("request body")
                    ?.objectValue("content")?.get("application/json")?.asObject("JSON body")
                    ?.objectValue("schema")
                val responseSchema = operation.objectValue("responses").entries
                    .firstOrNull { (status, _) -> status.toIntOrNull() in 200..299 }
                    ?.value?.asObject("OpenAPI response")
                    ?.let { response -> (response["content"] as? Map<*, *>)?.asObject("response content") }
                    ?.get("application/json")?.asObject("JSON response")
                    ?.objectValue("schema")
                require(schema == null || schema["type"] == "object" && "\$ref" !in schema) {
                    "Only inline JSON object request schemas are supported"
                }
                val security = operation["security"] ?: root["security"]
                val options = security as? List<*>
                val isPublic = security == null || options?.isEmpty() == true ||
                    options?.any { it is Map<*, *> && it.isEmpty() } == true
                OpenApiOperation(
                    method, path, operation.optionalText("operationId"), schema, responseSchema, responseCodes,
                    !path.contains('{') && !pathItem.containsKey("parameters") &&
                        !operation.containsKey("parameters") && !operation.containsKey("requestBody"),
                    isPublic,
                )
            }
        }
    }

    private data class OpenApiOperation(
        val method: String, val path: String, val operationId: String?,
        val requestSchema: Map<String, Any?>?, val responseSchema: Map<String, Any?>?,
        val successCodes: Set<Int>, val staticPath: Boolean, val public: Boolean,
    )

    private fun Map<String, Any?>.property(pointer: String): Map<String, Any?> =
        pointer.removePrefix("/").split('/').fold(this) { current, segment ->
            current.objectValue("properties")[segment].asObject("OpenAPI property")
        }

    private fun Any?.asObject(label: String): Map<String, Any?> {
        val source = this as? Map<*, *> ?: throw IllegalArgumentException("$label must be an object")
        require(source.keys.all { it is String }) { "$label has an invalid field" }
        return source.entries.associate { (key, value) -> key as String to value }
    }

    private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?> = get(key).asObject(key)
    private fun Map<String, Any?>.listValue(key: String): List<Any?> =
        get(key) as? List<*> ?: throw IllegalArgumentException("$key must be an array")
    private fun Map<String, Any?>.stringList(key: String): List<String> =
        listValue(key).map { it as? String ?: throw IllegalArgumentException("$key must contain strings") }
    private fun Map<String, Any?>.text(key: String): String =
        (get(key) as? String)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("$key is required")
    private fun Map<String, Any?>.optionalText(key: String): String? = get(key) as? String

    private companion object {
        const val STATE_PATH = "/api/harness/state"
        const val RESET_PATH = "/api/harness/reset"
        const val MAX_OPERATIONS = 20
        const val MAX_CONCURRENCY = 20
        const val MAX_REQUEST_COUNT = 100
        const val MAX_TRIALS = 20
        const val MAX_BATCH_SIZE = 5
        const val REQUEST_TIMEOUT_SECONDS = 5L
        val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
        val WRITE_METHODS = METHODS - "GET"
        val FIELD = Regex("[A-Za-z][A-Za-z0-9_-]{0,99}")
        val POINTER = Regex("/(?:[A-Za-z][A-Za-z0-9_-]*/)*[A-Za-z][A-Za-z0-9_-]*")
        val PREFIX = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
        val READINESS_KIND = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
        val SCALAR_TYPES = setOf("string", "integer", "number", "boolean")
        val MAP_TYPE = object : TypeReference<Map<String, Any?>>() {}
    }
}
