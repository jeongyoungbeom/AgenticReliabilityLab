package com.project.agenticreliabilitylab.targetprofile.infrastructure

internal object TargetProfileYamlSchema {
    val ARL_FIELDS = setOf("targets", "target-specs", "experiment-targets")
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
        "endpoint", "max-stock", "max-request-count", "max-concurrency", "max-quantity-per-request",
        "execution-timeout",
    )
    val ALL_REGISTRATION_FIELDS = TARGET_FIELDS + GENERIC_FIELDS + EXPERIMENT_FIELDS
}
