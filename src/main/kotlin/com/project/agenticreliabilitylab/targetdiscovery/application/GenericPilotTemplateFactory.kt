package com.project.agenticreliabilitylab.targetdiscovery.application

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.targetcredential.application.RuntimeTargetCredentialStore
import com.project.agenticreliabilitylab.targetdiscovery.application.PilotCandidateReadiness.NOT_READY
import com.project.agenticreliabilitylab.targetdiscovery.application.PilotCandidateReadiness.READY
import com.project.agenticreliabilitylab.targetprofile.application.HarnessManifestFetcher
import com.project.agenticreliabilitylab.targetprofile.application.TargetProfileService
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileVersion
import com.project.agenticreliabilitylab.targetprofile.domain.declaredOpenApiPaths
import com.project.agenticreliabilitylab.testspec.application.TestSpecExecutionProfileMapper
import com.project.agenticreliabilitylab.testspec.application.TestSpecParser
import com.project.agenticreliabilitylab.testspec.application.TestSpecValidator
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Builds the small, deterministic V1 catalogue from a fresh copy of the exact contract approved in the Profile.
 * Manifest data supplies fixture values and verdicts, never execution authority.
 */
@Component
@Suppress(
    "TooManyFunctions",
    "CyclomaticComplexMethod", // Each candidate gate reports its own missing capability.
    "MaxLineLength", // Manifest and Test Spec field mappings stay visible beside the generated calls.
    "ThrowsCount", // Unknown, inactive and unready candidates have separate safe client errors.
)
class GenericPilotTemplateFactory(
    private val profiles: TargetProfileService,
    private val openApi: TargetOpenApiDocumentFetcher,
    private val manifests: HarnessManifestFetcher,
    private val credentials: RuntimeTargetCredentialStore,
    private val mapper: ObjectMapper,
    private val parser: TestSpecParser,
    private val validator: TestSpecValidator,
    private val executionProfiles: TestSpecExecutionProfileMapper,
) {
    fun candidates(profile: TargetProfileVersion, credentialSessionId: String?): List<PilotTestCandidate> =
        plans(profile, credentialSessionId).map(Plan::candidate)

    fun readyDocuments(targetSystemId: String, credentialSessionId: String?): List<String> {
        val profile = profiles.findActive(targetSystemId)
            ?: throw ClientRequestException("TARGET_PROFILE_NOT_ACTIVE", "Target has no active Profile")
        return plans(profile, credentialSessionId)
            .filter { it.candidate.readiness == READY }
            .map { mapper.writeValueAsString(it.document(1)) }
    }

    fun document(
        targetSystemId: String,
        candidateId: String,
        version: Int,
        credentialSessionId: String?,
    ): String {
        val profile = profiles.findActive(targetSystemId)
            ?: throw ClientRequestException("TARGET_PROFILE_NOT_ACTIVE", "Target has no active Profile")
        val plan = plans(profile, credentialSessionId).firstOrNull { it.candidate.id == candidateId }
            ?: throw ClientRequestException("PILOT_CANDIDATE_NOT_READY", "Unknown generic candidate '$candidateId'")
        if (plan.candidate.readiness != READY) {
            throw ClientRequestException("PILOT_CANDIDATE_NOT_READY", "Generic candidate '$candidateId' is not ready")
        }
        return mapper.writeValueAsString(plan.document(version))
    }

    private fun plans(profile: TargetProfileVersion, credentialSessionId: String?): List<Plan> {
        val target = profile.definition.target
        val reads = readPlans(profile)
        val documents = target.declaredOpenApiPaths().map { path -> openApi.fetch(profile, path) }
        if (sha256(documents) != target.openApiSha256) {
            throw ClientRequestException("TARGET_CONTRACT_CHANGED", "OpenAPI differs from the active Profile")
        }
        val key = credentials.headersFor(profile.targetSystemId, credentialSessionId, "harness")
            ?.get("X-ARL-Harness-Key")
            ?: return reads.map { plan -> validatePlan(profile, plan) }
        val manifest = manifests.fetch(target, key)
        val digest = sha256(documents + manifest)
        if (digest != target.contractSha256) {
            throw ClientRequestException("TARGET_CONTRACT_CHANGED", "OpenAPI or Harness manifest differs from the active Profile")
        }
        val root = mapper.readTree(manifest)
        val operations = root.path("operations").mapIndexed { index, node ->
            operation(index + 1, node, documents)
        }
        val writes = operations.flatMap { operation ->
            val basic = writePlan(profile, operations, operation, false)
            val idempotency = if (operation.idempotency == "KEYED") {
                listOf(writePlan(profile, operations, operation, true))
            } else emptyList()
            listOf(basic) + idempotency
        }
        return (reads + writes).map { plan -> validatePlan(profile, plan) }
    }

    private fun sha256(documents: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(documents.joinToString("\u0000").toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun readPlans(profile: TargetProfileVersion): List<Plan> =
        profile.definition.genericHttp?.readOnlyOperations.orEmpty().mapIndexed { index, read ->
            val call = profile.definition.testSpecExecution?.allowedCalls?.firstOrNull {
                it.method == "GET" && it.path == read.path && it.authProfile == null
            }
            val missing = if (call == null) listOf("approved public GET ${read.path}") else emptyList()
            val candidate = PilotTestCandidate(
                "generic-read-${index + 1}", read.title, read.description,
                if (missing.isEmpty()) READY else NOT_READY,
                listOf(PilotDiscoveredOperation("GET", read.path, read.path, read.operationId, null, read.title)),
                missing,
            )
            Plan(candidate) { version -> readDocument(candidate, read.path, version) }
        }

    private fun validatePlan(profile: TargetProfileVersion, plan: Plan): Plan {
        if (plan.candidate.readiness != READY) return plan
        val valid = runCatching {
            val specification = parser.parse(
                mapper.writeValueAsString(plan.document(1)), UUID(0, 1),
                profile.targetSystemId, profile.id, SpecSource.RULE_GENERATED,
            )
            validator.validate(specification, executionProfiles.map(profile).capabilities)
        }
        return if (valid.isSuccess) plan else plan.copy(
            candidate = plan.candidate.copy(
                readiness = NOT_READY,
                missingOperations = listOf("generated Test Spec is not executable under the active Profile"),
            ),
        )
    }

    private fun writePlan(
        profile: TargetProfileVersion,
        operations: List<ManifestOperation>,
        operation: ManifestOperation,
        repeated: Boolean,
    ): Plan {
        val dependencies = dependencies(operations, operation)
        val required = dependencies + operation
        val execution = profile.definition.testSpecExecution
        val missing = buildList {
            if (profile.definition.target.environment.name !in setOf("LOCAL", "TEST")) add("LOCAL/TEST environment")
            if (execution?.executionEnabled != true || execution.stateChangingAllowed != true) add("enabled write Profile")
            if (execution?.reset?.hook?.path != "/api/harness/reset") add("Harness reset")
            val source = execution?.observationSources?.firstOrNull { it.name == "harness" }
            if (source?.endpoint != "/api/harness/state") add("Harness state")
            required.forEach { step ->
                if (execution?.allowedCalls?.none {
                    it.method == step.method && it.path == step.path && it.authProfile == step.role
                } != false) add("approved ${step.method} ${step.path} as ${step.role}")
                step.observations.forEach { (field, _) ->
                    if (field !in source?.fields.orEmpty()) add("Harness field '$field'")
                }
                if (step.unsupportedCapture) add("string-compatible capture for ${step.path}")
                if (step.unsupportedInput) add("OpenAPI-compatible fixture input for ${step.path}")
                step.readinessKind?.let { kind ->
                    val capture = step.captures.keys.singleOrNull()
                    val path = "/api/harness/readiness/$kind/{id}"
                    if (capture == null) add("single response ID capture for readiness")
                    if (execution?.allowedCalls?.none {
                        it.method == "GET" && it.path == path && it.authProfile == "harness"
                    } != false) add("Harness GET readiness $path")
                }
            }
            if (repeated && operation.readinessKind != null) add("idempotency retry requires synchronous operation")
        }.distinct()
        val candidateId = "generic-${if (repeated) "idempotency" else if (operation.readinessKind != null) "async" else "write"}-${operation.index}"
        val candidate = PilotTestCandidate(
            id = candidateId,
            title = "${if (repeated) "Idempotency" else if (operation.readinessKind != null) "Async write" else "Write"} ${operation.method} ${operation.path}",
            description = "Approved Harness V1 operation with run-scoped fixtures and observations",
            readiness = if (missing.isEmpty()) READY else NOT_READY,
            operations = required.map { step ->
                PilotDiscoveredOperation(step.method, step.path, step.path, step.operationId, step.role, null)
            },
            missingOperations = missing,
        )
        return Plan(candidate) { version -> writeDocument(candidate, required, operation, repeated, version) }
    }

    private fun dependencies(operations: List<ManifestOperation>, operation: ManifestOperation): List<ManifestOperation> {
        val needed = linkedSetOf<ManifestOperation>()
        fun visit(current: ManifestOperation) {
            current.inputs.filter { it.source == "RUN_CAPTURE" }.forEach { input ->
                val producer = operations.first { it.operationId == input.operationId }
                visit(producer)
                needed.add(producer)
            }
        }
        visit(operation)
        return needed.sortedBy(ManifestOperation::index)
    }

    private fun readDocument(candidate: PilotTestCandidate, path: String, version: Int): Map<String, Any> = linkedMapOf(
        "specKey" to "pilot-${candidate.id}", "version" to version, "title" to candidate.title,
        "category" to "AVAILABILITY", "risk" to "SAFE", "evidence" to evidence(path),
        "setup" to emptyList<Any>(),
        "workload" to listOf(mapOf("kind" to "CALL", "name" to "read", "call" to call("GET", path, null))),
        "observations" to listOf(mapOf("id" to "status", "source" to "RESPONSES", "expr" to "read[*].status")),
        "invariants" to listOf(invariant("available", "status >= 200 && status < 300")),
        "policy" to policy(), "cleanup" to mapOf("method" to "NOT_REQUIRED"),
    )

    private fun writeDocument(
        candidate: PilotTestCandidate,
        required: List<ManifestOperation>,
        operation: ManifestOperation,
        repeated: Boolean,
        version: Int,
    ): Map<String, Any> {
        val setup = required.dropLast(1).map { step ->
            linkedMapOf<String, Any>(
                "name" to "fixture${step.index}",
                "call" to call(step.method, step.path, step.role, body(step, required)),
                "captures" to step.captures.mapValues { (_, pointer) -> "response.body.${pointer.removePrefix("/").replace('/', '.')}" },
            ).apply {
                if (step.readinessKind != null) {
                    val capture = step.captures.keys.single()
                    put("readiness", call(
                        "GET",
                        "/api/harness/readiness/${step.readinessKind}/{{setup.fixture${step.index}.$capture}}",
                        "harness",
                    ))
                }
            }
        }
        val callName = "mutation"
        val workload = linkedMapOf<String, Any>(
            "kind" to "CALL", "name" to callName,
            "call" to call(
                operation.method, operation.path, operation.role, body(operation, required),
                if (repeated) mapOf("Idempotency-Key" to "arl-${operation.index}-{{runId}}") else emptyMap(),
            ),
        )
        if (repeated) workload["requestCount"] = 2
        if (operation.readinessKind != null && !repeated) {
            val capture = operation.captures.keys.single()
            workload["captures"] = operation.captures.mapValues { (_, pointer) ->
                "response.body.${pointer.removePrefix("/").replace('/', '.')}"
            }
            workload["readiness"] = call(
                "GET", "/api/harness/readiness/${operation.readinessKind}/{{workload.$callName.$capture}}", "harness",
            )
        }
        val observations = mutableListOf<Map<String, Any>>(
            mapOf("id" to "responseStatus", "source" to "RESPONSES", "expr" to
                if (repeated) "count($callName[*].status)" else "$callName[*].status"),
        )
        val invariants = mutableListOf<Map<String, Any>>(
            invariant("accepted", if (repeated) "responseStatus == 2" else
                "responseStatus >= 200 && responseStatus < 300"),
        )
        operation.observations.entries.forEachIndexed { index, (field, expected) ->
            val id = "observed${index + 1}"
            observations += mapOf("id" to id, "source" to "DECLARED_SOURCE", "sourceName" to "harness", "expr" to field)
            invariants += invariant("expected${index + 1}", "$id == ${literal(expected)}")
        }
        return linkedMapOf(
            "specKey" to "pilot-${candidate.id}", "version" to version, "title" to candidate.title,
            "category" to if (repeated) "IDEMPOTENCY" else "WORKFLOW", "risk" to "MODERATE",
            "evidence" to evidence(operation.path), "setup" to setup, "workload" to listOf(workload),
            "observations" to observations, "invariants" to invariants,
            "policy" to policy(), "cleanup" to mapOf("method" to "ENVIRONMENT_RESET"),
        )
    }

    private fun body(operation: ManifestOperation, operations: List<ManifestOperation>): Map<String, Any> {
        val root = linkedMapOf<String, Any>()
        operation.inputs.forEach { input ->
            val value: Any = when (input.source) {
                "RUN_TAGGED_STRING" -> "${input.prefix}-{{runId}}-{{trialNumber}}"
                "BOUNDED_INTEGER" -> requireNotNull(input.value)
                "RUN_CAPTURE" -> {
                    val producer = operations.first { it.operationId == input.operationId }
                    val reference = "setup.fixture${producer.index}.${input.capture}"
                    when (input.type) {
                        "integer", "number" -> "{{arl-number:$reference}}"
                        "boolean" -> "{{arl-boolean:$reference}}"
                        else -> "{{$reference}}"
                    }
                }
                else -> error("Unsupported fixture source")
            }
            var current: MutableMap<String, Any> = root
            val segments = input.pointer.removePrefix("/").split('/')
            segments.dropLast(1).forEach { segment ->
                @Suppress("UNCHECKED_CAST")
                val child = current.getOrPut(segment) { linkedMapOf<String, Any>() } as MutableMap<String, Any>
                current = child
            }
            current[segments.last()] = value
        }
        return root
    }

    private fun operation(index: Int, node: JsonNode, documents: List<String>): ManifestOperation {
        val captures = node.path("captures").propertyNames().associateWith { name ->
            node.path("captures").path(name).asString()
        }
        val requestSchema = documents.asSequence()
            .map(mapper::readTree)
            .map { document ->
                document.path("paths").path(node.path("path").asString())
                    .path(node.path("method").asString().lowercase())
                    .path("requestBody").path("content").path("application/json").path("schema")
            }
            .first { it.isObject }
        val inputs = node.path("fixtureRecipe").path("inputs").toList().map { input ->
            val pointer = input.path("pointer").asString()
            val property = pointer.removePrefix("/").split('/').fold(requestSchema) { schema, segment ->
                schema.path("properties").path(segment)
            }
            FixtureInput(
                pointer, input.path("source").asString(),
                input.path("prefix").asString(), input.path("value").takeIf { it.isNumber }?.asInt(),
                input.path("operationId").asString(), input.path("capture").asString(),
                property.path("type").asString(), unsupportedSchema(input, property),
            )
        }
        return ManifestOperation(
            index, node.path("method").asString(), node.path("path").asString(),
            node.path("operationId").asString(), node.path("authProfile").asString(),
            inputs, captures,
            node.path("observations").associate { entry ->
                entry.path("field").asString() to entry.path("expected")
            },
            node.path("idempotency").asString(),
            node.path("readinessKind").asString().takeIf(String::isNotBlank),
            captures.values.any { pointer -> !SAFE_CAPTURE_POINTER.matches(pointer) },
            inputs.any(FixtureInput::unsupported),
        )
    }

    private fun unsupportedSchema(input: JsonNode, property: JsonNode): Boolean {
        if (listOf("enum", "pattern", "format", "oneOf", "anyOf", "allOf", "not", "\$ref").any(property::has)) {
            return true
        }
        return when (input.path("source").asString()) {
            "RUN_TAGGED_STRING" -> {
                val maximum = property.path("maxLength").takeIf { it.isNumber }?.asInt()
                val minimum = property.path("minLength").takeIf { it.isNumber }?.asInt()
                val length = input.path("prefix").asString().length + 1 + 36 + 1 + 1
                property.path("type").asString() != "string" ||
                    maximum != null && maximum < length || minimum != null && minimum > length
            }
            "BOUNDED_INTEGER" -> listOf("multipleOf", "exclusiveMinimum", "exclusiveMaximum").any(property::has)
            "RUN_CAPTURE" -> property.path("type").asString() !in setOf(
                "string", "integer", "number", "boolean",
            ) || listOf("minLength", "maxLength", "minimum", "maximum", "multipleOf").any(property::has)
            else -> true
        }
    }

    private fun literal(node: JsonNode): String = if (node.isString) {
        mapper.writeValueAsString(node.asString())
    } else node.toString()

    private fun call(method: String, path: String, role: String?, body: Map<String, Any>? = null, headers: Map<String, String> = emptyMap()) =
        linkedMapOf<String, Any>("method" to method, "path" to path).apply {
            if (role != null) put("authProfile", role)
            if (body != null) put("body", body)
            if (headers.isNotEmpty()) put("headers", headers)
        }

    private fun evidence(path: String) = listOf(
        mapOf("sourceType" to "HARNESS_MANIFEST", "location" to path, "excerpt" to "Approved Harness V1 mapping"),
    )

    private fun invariant(id: String, condition: String) =
        mapOf("id" to id, "description" to id, "condition" to condition)

    private fun policy() = mapOf("trials" to 1, "cleanupTiming" to "EACH_TRIAL")

    private data class Plan(val candidate: PilotTestCandidate, val document: (Int) -> Map<String, Any>)
    private data class FixtureInput(
        val pointer: String, val source: String, val prefix: String, val value: Int?,
        val operationId: String, val capture: String, val type: String, val unsupported: Boolean,
    )
    private data class ManifestOperation(
        val index: Int, val method: String, val path: String, val operationId: String, val role: String,
        val inputs: List<FixtureInput>, val captures: Map<String, String>,
        val observations: Map<String, JsonNode>, val idempotency: String, val readinessKind: String?,
        val unsupportedCapture: Boolean,
        val unsupportedInput: Boolean,
    )

    private companion object {
        val SAFE_CAPTURE_POINTER = Regex("/[A-Za-z][A-Za-z0-9_]*(?:/[A-Za-z][A-Za-z0-9_]*)*")
    }
}
