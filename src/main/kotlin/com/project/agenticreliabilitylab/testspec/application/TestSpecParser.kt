package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.CleanupMethod
import com.project.agenticreliabilitylab.testspec.domain.CleanupTiming
import com.project.agenticreliabilitylab.testspec.domain.DeclaredValue
import com.project.agenticreliabilitylab.testspec.domain.ExecutionPolicy
import com.project.agenticreliabilitylab.testspec.domain.Invariant
import com.project.agenticreliabilitylab.testspec.domain.InvariantException
import com.project.agenticreliabilitylab.testspec.domain.Observation
import com.project.agenticreliabilitylab.testspec.domain.ObservationSourceKind
import com.project.agenticreliabilitylab.testspec.domain.ReadTiming
import com.project.agenticreliabilitylab.testspec.domain.SetupStep
import com.project.agenticreliabilitylab.testspec.domain.SpecCategory
import com.project.agenticreliabilitylab.testspec.domain.SpecEvidence
import com.project.agenticreliabilitylab.testspec.domain.SpecHttpCall
import com.project.agenticreliabilitylab.testspec.domain.SpecRisk
import com.project.agenticreliabilitylab.testspec.domain.SpecSource
import com.project.agenticreliabilitylab.testspec.domain.StabilityRule
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification
import com.project.agenticreliabilitylab.testspec.domain.TrialAggregation
import com.project.agenticreliabilitylab.testspec.domain.TrialStopPolicy
import com.project.agenticreliabilitylab.testspec.domain.UnmetRequirement
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStep
import com.project.agenticreliabilitylab.testspec.domain.WorkloadStepKind
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID

/**
 * Reads a specification document into the domain model.
 *
 * The input is a model's output, so the parser is deliberately unforgiving. An unknown enum value, an unknown step
 * kind or an unexpected field is an error rather than something to skip: silently dropping part of a specification
 * would produce a test that runs less than the reviewer approved, which is the one outcome an approval must rule out.
 */
@Component
@Suppress("TooManyFunctions") // Each JSON section has one explicit mapper so review stays mechanical.
class TestSpecParser(
    private val objectMapper: ObjectMapper,
    private val schema: TestSpecSchemaValidator,
) {
    fun parse(
        document: String,
        id: UUID,
        targetSystemId: String,
        profileVersionId: UUID,
        source: SpecSource,
    ): TestSpecification {
        val root = readRoot(document)
        schema.validate(root)
        return TestSpecification(
            id = id,
            specKey = root.requiredText("specKey"),
            version = root.optionalInt("version") ?: 1,
            title = root.requiredText("title"),
            category = root.requiredEnum("category", SpecCategory.entries),
            risk = root.requiredEnum("risk", SpecRisk.entries),
            source = source,
            targetSystemId = targetSystemId,
            profileVersionId = profileVersionId,
            evidence = root.path("evidence").mapNodes(::toEvidence),
            setup = root.path("setup").mapNodes(::toSetupStep),
            workload = root.path("workload").mapNodes(::toWorkloadStep),
            observations = root.path("observations").mapNodes(::toObservation),
            invariants = root.path("invariants").mapNodes(::toInvariant),
            policy = toPolicy(root.path("policy")),
            cleanup = root.path("cleanup").optionalEnum("method", CleanupMethod.entries)
                ?: CleanupMethod.ENVIRONMENT_RESET,
        )
    }

    @Suppress("TooGenericExceptionCaught") // Any read failure means the same thing: this document is unusable.
    private fun readRoot(document: String): JsonNode {
        val root = try {
            objectMapper.readTree(document)
        } catch (exception: Exception) {
            throw SpecParseException(
                "Specification is not valid JSON: ${exception.javaClass.simpleName}",
                exception,
            )
        }
        if (!root.isObject) throw SpecParseException("Specification must be a JSON object")
        return root
    }

    private fun toEvidence(node: JsonNode) = SpecEvidence(
        sourceType = node.requiredText("sourceType"),
        location = node.requiredText("location"),
        excerpt = node.requiredText("excerpt"),
    )

    private fun toSetupStep(node: JsonNode) = SetupStep(
        name = node.requiredText("name"),
        call = toCall(node.path("call")),
        captures = node.path("captures").toStringMap(),
    )

    private fun toCall(node: JsonNode): SpecHttpCall {
        if (!node.isObject) throw SpecParseException("A call must be an object")
        return SpecHttpCall(
            method = node.requiredText("method").uppercase(),
            path = node.requiredText("path"),
            authProfile = node.optionalText("authProfile"),
            headers = node.path("headers").toStringMap(),
            bodyJson = node.path("body").takeIf { !it.isMissingNode && !it.isNull }?.toString(),
        )
    }

    /**
     * Reads one workload step.
     *
     * Every kind is handled explicitly. A kind this build cannot run is still parsed rather than rejected, so the
     * approval screen can tell a reviewer the specification declares something that will not execute yet.
     */
    private fun toWorkloadStep(node: JsonNode): WorkloadStep {
        val kind = node.requiredEnum("kind", WorkloadStepKind.entries)
        val name = node.requiredText("name")
        return when (kind) {
            WorkloadStepKind.CALL -> WorkloadStep(
                kind = kind, name = name, call = toCall(node.path("call")),
                requestCount = node.optionalInt("requestCount") ?: 1,
                concurrency = node.optionalInt("concurrency") ?: 1,
                captureAs = node.optionalText("captureAs"),
                captures = node.path("captures").toStringMap(),
            )
            WorkloadStepKind.WAIT -> WorkloadStep(
                kind = kind, name = name, wait = node.requiredDuration("duration"),
            )
            WorkloadStepKind.INJECT_FAULT -> WorkloadStep(
                kind = kind, name = name,
                faultType = node.requiredText("faultType"),
                faultScope = node.optionalText("scope"),
                faultTtl = node.optionalDuration("ttl"),
            )
            WorkloadStepKind.RELEASE_FAULT -> WorkloadStep(
                kind = kind, name = name, handleReference = node.requiredText("handle"),
            )
            WorkloadStepKind.INFRA_ACTION -> WorkloadStep(
                kind = kind, name = name,
                infraAction = node.requiredText("action"),
                infraTarget = node.requiredText("target"),
                infraMaxHold = node.optionalDuration("maxHold"),
            )
            WorkloadStepKind.INFRA_RESTORE -> WorkloadStep(
                kind = kind, name = name, handleReference = node.requiredText("handle"),
            )
        }
    }

    private fun toObservation(node: JsonNode): Observation {
        val kind = node.requiredEnum("source", ObservationSourceKind.entries)
        return Observation(
            id = node.requiredText("id"),
            label = node.optionalText("label") ?: node.requiredText("id"),
            sourceKind = kind,
            sourceName = node.optionalText("sourceName"),
            call = node.path("call").takeIf { it.isObject }?.let(::toCall),
            expression = node.requiredText("expr"),
            readTiming = toReadTiming(node.path("readAt")),
        )
    }

    /** A value that has to settle must say how long it may take; an immediate read is the explicit default. */
    private fun toReadTiming(node: JsonNode): ReadTiming {
        if (!node.isObject) return ReadTiming.IMMEDIATE
        val rule = node.requiredEnum("rule", StabilityRule.entries)
        return if (rule == StabilityRule.IMMEDIATE) {
            ReadTiming.IMMEDIATE
        } else {
            ReadTiming(
                rule = rule,
                maxWait = node.requiredDuration("maxWait"),
                interval = node.optionalDuration("interval") ?: Duration.ofMillis(DEFAULT_POLL_MILLIS),
                evidence = node.path("evidence").takeIf { it.isObject }?.let(::toEvidence),
            )
        }
    }

    private fun toInvariant(node: JsonNode) = Invariant(
        id = node.requiredText("id"),
        description = node.requiredText("description"),
        condition = node.requiredText("condition"),
        requires = node.optionalText("requires"),
        unmet = node.optionalEnum("unmet", UnmetRequirement.entries) ?: UnmetRequirement.NOT_EVALUATED,
        exceptions = node.path("exceptions").mapNodes(::toException),
        threshold = node.path("threshold").takeIf { it.isObject }?.let(::toDeclaredValue),
    )

    private fun toException(node: JsonNode) = InvariantException(
        condition = node.requiredText("condition"),
        description = node.requiredText("description"),
        evidence = node.path("evidence").takeIf { it.isObject }?.let(::toEvidence),
        approvedBy = node.optionalText("approvedBy"),
    )

    private fun toDeclaredValue(node: JsonNode) = DeclaredValue(
        literal = node.optionalText("literal"),
        reference = node.optionalText("reference"),
        evidence = node.path("evidence").takeIf { it.isObject }?.let(::toEvidence),
    )

    private fun toPolicy(node: JsonNode) = ExecutionPolicy(
        trials = node.optionalInt("trials") ?: 1,
        aggregation = node.optionalEnum("aggregation", TrialAggregation.entries)
            ?: TrialAggregation.ANY_VIOLATION_FAILS,
        stopPolicy = node.optionalEnum("stopPolicy", TrialStopPolicy.entries)
            ?: TrialStopPolicy.STOP_ON_FIRST_VIOLATION,
        cleanupTiming = node.optionalEnum("cleanupTiming", CleanupTiming.entries) ?: CleanupTiming.AFTER_ALL,
        trialInterval = node.optionalDuration("interval") ?: Duration.ZERO,
    )

    private companion object {
        const val DEFAULT_POLL_MILLIS = 500L
    }
}

class SpecParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun JsonNode.requiredText(field: String): String =
    path(field).asString().takeIf(String::isNotBlank)
        ?: throw SpecParseException("Field '$field' is required")

private fun JsonNode.optionalText(field: String): String? =
    path(field).asString().takeIf(String::isNotBlank)

private fun JsonNode.optionalInt(field: String): Int? =
    path(field).asString().toIntOrNull()

/** An unknown enum value names the allowed set, because the reader of this error is usually a model retrying. */
private fun <T : Enum<T>> JsonNode.requiredEnum(field: String, values: List<T>): T {
    val raw = requiredText(field)
    return values.firstOrNull { it.name == raw }
        ?: throw SpecParseException("Field '$field' must be one of ${values.joinToString { it.name }} but was '$raw'")
}

private fun <T : Enum<T>> JsonNode.optionalEnum(field: String, values: List<T>): T? {
    val raw = optionalText(field) ?: return null
    return values.firstOrNull { it.name == raw }
        ?: throw SpecParseException("Field '$field' must be one of ${values.joinToString { it.name }} but was '$raw'")
}

private fun JsonNode.requiredDuration(field: String): Duration =
    optionalDuration(field) ?: throw SpecParseException("Field '$field' is required")

/** Durations are milliseconds. A bare number avoids a model inventing its own unit suffix. */
private fun JsonNode.optionalDuration(field: String): Duration? =
    path(field).asString().toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofMillis)

private fun <T> JsonNode.mapNodes(transform: (JsonNode) -> T): List<T> =
    if (!isArray) emptyList() else values().map(transform)

private fun JsonNode.toStringMap(): Map<String, String> =
    if (!isObject) emptyMap() else propertyNames().associateWith { name -> path(name).asString() }
