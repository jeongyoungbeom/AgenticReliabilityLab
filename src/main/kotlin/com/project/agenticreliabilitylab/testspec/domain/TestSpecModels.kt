package com.project.agenticreliabilitylab.testspec.domain

import java.time.Duration
import java.util.UUID

/** Test areas, reused from the Phase 12 candidate taxonomy so a candidate and its spec agree. */
enum class SpecCategory {
    AVAILABILITY,
    CONTRACT_INPUT,
    WORKFLOW,
    RETRY_RECOVERY,
    IDEMPOTENCY,
    CONCURRENCY,
    CONSISTENCY,
}

/** Risk levels reused from the existing Experiment risk policy. The plan's approval level is its highest item. */
enum class SpecRisk {
    SAFE,
    MODERATE,
    DESTRUCTIVE,
}

/** Who proposed this specification. A model proposal is never trusted more than a rule-generated one. */
enum class SpecSource {
    RULE_GENERATED,
    MODEL_PROPOSED,
    USER_REQUESTED,
}

/** Where an observed value is read from. The invariant does not change when this does. */
enum class ObservationSourceKind {
    /** A read-only call declared in the active Profile. */
    API,

    /** Values computed from the workload's own responses. */
    RESPONSES,

    /** A named observation source the Profile declares (Harness state, Prometheus, traces). */
    DECLARED_SOURCE,
}

/** How the cleanup after a run is performed. */
enum class CleanupMethod {
    /** Reset the whole environment and verify the reset landed. */
    ENVIRONMENT_RESET,

    /** Nothing was changed, so nothing needs undoing. */
    NOT_REQUIRED,
}

/** When cleanup runs relative to trials. */
enum class CleanupTiming {
    /** Once after every trial. Needed when a trial changes global state. */
    EACH_TRIAL,

    /** Once after all trials. Safe when each trial builds its own fixture. */
    AFTER_ALL,
}

/** How several trials combine into one verdict. */
enum class TrialAggregation {
    /** A single violating trial fails the whole specification. Correct for probabilistic defects. */
    ANY_VIOLATION_FAILS,
}

/** Whether to keep running once a trial has already violated. */
enum class TrialStopPolicy {
    STOP_ON_FIRST_VIOLATION,
    RUN_ALL,
}

/** How an observation decides it has read a settled value. */
enum class StabilityRule {
    /** Read once, do not wait. Correct for values the workload already returned. */
    IMMEDIATE,
    TWO_CONSECUTIVE_EQUAL,
    THREE_CONSECUTIVE_EQUAL,
}

/** What to report when an invariant's precondition does not hold. */
enum class UnmetRequirement {
    /** Not a violation. We simply could not judge. */
    NOT_EVALUATED,
}

/**
 * The document position a specification was derived from.
 *
 * Every model-proposed specification must cite one, so a reviewer can check the claim against the source text
 * instead of trusting the proposal.
 */
data class SpecEvidence(
    val sourceType: String,
    val location: String,
    val excerpt: String,
)

/**
 * A value the specification declares rather than hard-codes.
 *
 * [reference] is null when the author supplied a literal. A literal threshold with no document behind it is the
 * single most dangerous thing in a specification, because a wrong invariant produces a confidently wrong verdict.
 */
data class DeclaredValue(
    val literal: String?,
    val reference: String?,
    val evidence: SpecEvidence?,
) {
    /** True when a number was invented rather than read from a document. The approval screen highlights these. */
    val unfounded: Boolean = literal != null && evidence == null

    companion object {
        fun literal(value: String): DeclaredValue = DeclaredValue(value, null, null)
        fun reference(path: String): DeclaredValue = DeclaredValue(null, path, null)
        fun cited(value: String, evidence: SpecEvidence): DeclaredValue = DeclaredValue(value, null, evidence)
    }
}

/** One HTTP call the specification makes. Only paths the active Profile registers may appear here. */
data class SpecHttpCall(
    val method: String,
    val path: String,
    val authProfile: String?,
    val headers: Map<String, String>,
    val bodyJson: String?,
)

/**
 * A fixture the specification creates before the workload.
 *
 * Setup uses the Target's real API rather than a test-only endpoint, so a Target that never adds ARL-specific code
 * can still be exercised. [captures] name the values later steps refer to.
 */
data class SetupStep(
    val name: String,
    val call: SpecHttpCall,
    val captures: Map<String, String>,
)

/** The kinds of step a workload can contain. This build executes CALL and WAIT; the rest are declared but rejected. */
enum class WorkloadStepKind {
    CALL,
    WAIT,
    INJECT_FAULT,
    RELEASE_FAULT,
    INFRA_ACTION,
    INFRA_RESTORE,
}

/**
 * One step of the workload.
 *
 * A flat shape with a discriminator is used instead of a sealed hierarchy because the parser reads untrusted model
 * output: it must reject an unknown kind explicitly rather than let a deserializer choose a subtype.
 */
data class WorkloadStep(
    val kind: WorkloadStepKind,
    val name: String,
    val call: SpecHttpCall? = null,
    /** Total number of requests this step sends. */
    val requestCount: Int = 1,
    /** How many of those requests are in flight at once. */
    val concurrency: Int = 1,
    val captureAs: String? = null,
    /** Response fields a single-request CALL makes available to later workload steps. */
    val captures: Map<String, String> = emptyMap(),
    val wait: Duration? = null,
    val faultType: String? = null,
    val faultScope: String? = null,
    val faultTtl: Duration? = null,
    val infraAction: String? = null,
    val infraTarget: String? = null,
    val infraMaxHold: Duration? = null,
    val handleReference: String? = null,
)

/**
 * When an observation reads its value.
 *
 * Asynchronous propagation means an immediate read is always wrong, so a value that has to settle declares how long
 * it may take. Whether the settled value is correct is a separate question the invariants answer.
 */
data class ReadTiming(
    val rule: StabilityRule,
    val maxWait: Duration,
    val interval: Duration,
    val evidence: SpecEvidence?,
) {
    val unfoundedDeadline: Boolean = rule != StabilityRule.IMMEDIATE && evidence == null

    companion object {
        val IMMEDIATE = ReadTiming(StabilityRule.IMMEDIATE, Duration.ZERO, Duration.ZERO, null)
    }
}

/**
 * One value the run observes.
 *
 * [id] is the identifier invariant expressions use and must be ASCII, because expressions are compiled by CEL whose
 * identifiers cannot contain non-ASCII characters. [label] carries the human wording.
 */
data class Observation(
    val id: String,
    val label: String,
    val sourceKind: ObservationSourceKind,
    val sourceName: String?,
    val call: SpecHttpCall?,
    val expression: String,
    val readTiming: ReadTiming,
) {
    init {
        require(ASCII_IDENTIFIER.matches(id)) { "Observation id '$id' must be an ASCII identifier" }
    }

    private companion object {
        val ASCII_IDENTIFIER = Regex("[_a-zA-Z][_a-zA-Z0-9]*")
    }
}

/**
 * A case the specification accepts as normal even though the invariant would call it a violation.
 *
 * This is how a misjudgement stops repeating: the reviewer records why the behaviour is correct and the exception
 * is kept with the specification. It must stay narrow - an exception that always holds would delete the invariant.
 */
data class InvariantException(
    val condition: String,
    val description: String,
    val evidence: SpecEvidence?,
    val approvedBy: String?,
)

/**
 * One thing that must be true.
 *
 * [requires] names another invariant that has to pass first. When it does not, this one is reported as
 * [UnmetRequirement.NOT_EVALUATED] rather than violated, because a value we could not read is not a defect.
 */
data class Invariant(
    val id: String,
    val description: String,
    val condition: String,
    val requires: String?,
    val unmet: UnmetRequirement,
    val exceptions: List<InvariantException>,
    val threshold: DeclaredValue?,
) {
    init {
        require(ASCII_IDENTIFIER.matches(id)) { "Invariant id '$id' must be an ASCII identifier" }
    }

    private companion object {
        val ASCII_IDENTIFIER = Regex("[_a-zA-Z][_a-zA-Z0-9-]*")
    }
}

/** How many times the whole specification runs and how the trials combine. */
data class ExecutionPolicy(
    val trials: Int,
    val aggregation: TrialAggregation,
    val stopPolicy: TrialStopPolicy,
    val cleanupTiming: CleanupTiming,
    val trialInterval: Duration,
)

/**
 * One executable test, expressed as data rather than code.
 *
 * The point of this type is that adding a test does not mean writing a judge. A model proposes the specification,
 * a validator rejects anything outside what the Profile allows, a reviewer approves the judging rules, and the
 * engine evaluates them the same way every time.
 */
data class TestSpecification(
    val id: UUID,
    val specKey: String,
    val version: Int,
    val title: String,
    val category: SpecCategory,
    val risk: SpecRisk,
    val source: SpecSource,
    val targetSystemId: String,
    val profileVersionId: UUID,
    val evidence: List<SpecEvidence>,
    val setup: List<SetupStep>,
    val workload: List<WorkloadStep>,
    val observations: List<Observation>,
    val invariants: List<Invariant>,
    val policy: ExecutionPolicy,
    val cleanup: CleanupMethod,
) {
    /** Thresholds nobody could trace to a document. The approval screen must show these before anything runs. */
    fun unfoundedThresholds(): List<String> = buildList {
        invariants.filter { it.threshold?.unfounded == true }.forEach { add(it.id) }
        observations.filter { it.readTiming.unfoundedDeadline }.forEach { add(it.id) }
    }

    /** Steps the schema declares but this build cannot run yet. Kept visible instead of silently dropped. */
    fun unsupportedSteps(): List<WorkloadStepKind> =
        workload.map(WorkloadStep::kind).filterNot { it in SUPPORTED_STEPS }.distinct()

    private companion object {
        // Phase 21 added fault injection/release. Infrastructure control (INFRA_ACTION/INFRA_RESTORE) stays
        // unsupported: it is a separate, higher-risk decision (direct Docker/K8s control) deferred past this phase.
        val SUPPORTED_STEPS = setOf(
            WorkloadStepKind.CALL,
            WorkloadStepKind.WAIT,
            WorkloadStepKind.INJECT_FAULT,
            WorkloadStepKind.RELEASE_FAULT,
        )
    }
}
