package com.project.agenticreliabilitylab.targetprofile.infrastructure

internal object TargetProfileYamlSchema {
    val ARL_FIELDS = setOf("targets", "target-specs", "experiment-targets", "test-spec-execution")
    val REGISTRATIONS_FIELD = setOf("registrations")
    val TARGET_FIELDS = setOf(
        "id", "name", "adapter-type", "environment", "base-url", "allowed-origin", "allowed-cidrs",
        "health-path", "source-repository", "identity-verification", "capabilities", "enabled",
    )
    val GENERIC_FIELDS = setOf(
        "target-system-id", "execution-enabled", "host-resource-group", "max-batch-size", "request-timeout",
        "read-only-operations", "failure-injection-planning-enabled", "failure-injection-candidates",
    )
    val OPERATION_FIELDS = setOf("id", "title", "description", "path", "expected-status-codes")
    val FAILURE_CANDIDATE_FIELDS = setOf(
        "id", "type", "risk", "title", "description", "recovery-expectation",
    )
    val EXPERIMENT_FIELDS = setOf(
        "target-system-id", "adapter-id", "execution-enabled", "host-resource-group", "stock-concurrency",
    )
    val STOCK_CONCURRENCY_FIELDS = setOf(
        "endpoint", "capabilities-endpoint", "max-stock", "max-request-count", "max-concurrency",
        "max-quantity-per-request",
        "execution-timeout",
    )
    val TEST_SPEC_EXECUTION_FIELDS = setOf(
        "target-system-id", "execution-enabled", "allowed-calls", "auth-profiles", "observation-sources",
        "supported-faults", "infrastructure-targets", "max-concurrency", "max-request-count", "max-trials",
        "state-changing-allowed", "reset", "fault-injection",
    )
    val SPEC_CALL_FIELDS = setOf("method", "path", "auth-profile")
    val OBSERVATION_SOURCE_FIELDS = setOf("name", "kind", "endpoint", "fields", "queries", "auth-profile")
    val RESET_FIELDS = setOf("method", "hook", "expected-duration", "verifications")
    val FAULT_INJECTION_FIELDS = setOf("inject-endpoint", "release-endpoint", "max-ttl")
    val RESET_VERIFICATION_FIELDS = setOf("id", "call", "expr", "condition", "read-at")
    val READ_TIMING_FIELDS = setOf("rule", "max-wait", "interval")
    val ALL_REGISTRATION_FIELDS =
        TARGET_FIELDS + GENERIC_FIELDS + EXPERIMENT_FIELDS + TEST_SPEC_EXECUTION_FIELDS
}
