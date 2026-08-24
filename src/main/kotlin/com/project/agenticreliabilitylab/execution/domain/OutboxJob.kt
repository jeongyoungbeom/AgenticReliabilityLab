package com.project.agenticreliabilitylab.execution.domain

import java.time.Instant
import java.util.UUID

/** A durable, idempotent command for ARL-owned background work. */
enum class OutboxJobType {
    EXPERIMENT_EXECUTION,
    CAMPAIGN_EXECUTION,
    TARGET_TEST_BATCH_EXECUTION,
    SINGLE_ANALYSIS,
    MULTI_ANALYSIS,
    FOLLOW_UP_SUGGESTION,
    ROOT_CAUSE_REPORT,
    TEST_SPEC_GENERATION,
    MISJUDGMENT_EXCEPTION_DRAFT,
}

enum class OutboxJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}

data class OutboxJob(
    val id: UUID,
    val type: OutboxJobType,
    val aggregateId: UUID,
    val status: OutboxJobStatus,
    val attemptCount: Int,
    val availableAt: Instant,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val completedAt: Instant?,
)
