package com.project.agenticreliabilitylab.testspec.infrastructure.sql

object TestSpecRunSql {
    private val SELECT_RUN = """
        select id, specification_id, target_system_id, profile_version_id, status, idempotency_key, request_hash,
               requested_trials, result_outcome, trials_run, trials_violated, trials_inconclusive, cleanup_verified,
               created_by, created_correlation_id, created_at, started_at, completed_at, failure
        from test_spec_run
    """.trimIndent()

    val INSERT_RUN = """
        insert into test_spec_run (
            id, specification_id, target_system_id, profile_version_id, status, idempotency_key, request_hash,
            requested_trials, result_outcome, trials_run, trials_violated, trials_inconclusive, cleanup_verified,
            created_by, created_correlation_id, created_at, started_at, completed_at, failure, active_slot
        ) values (
            :id, :specificationId, :targetSystemId, :profileVersionId, :status, :idempotencyKey, :requestHash,
            :requestedTrials, null, null, null, null, null,
            :createdBy, :createdCorrelationId, :createdAt, null, null, null, :activeSlot
        )
    """.trimIndent()

    val FIND_BY_ID = "$SELECT_RUN where id = :id"

    val FIND_BY_TARGET_AND_IDEMPOTENCY_KEY =
        "$SELECT_RUN where target_system_id = :targetSystemId and idempotency_key = :idempotencyKey"

    val COUNT_BLOCKING = """
        select count(*)
        from test_spec_run
        where target_system_id = :targetSystemId and active_slot is not null
    """.trimIndent()

    val MARK_RUNNING = """
        update test_spec_run
        set status = :running, started_at = :startedAt
        where id = :id and status = :pending
    """.trimIndent()

    val MARK_COMPLETED = """
        update test_spec_run
        set status = :status, result_outcome = :resultOutcome, trials_run = :trialsRun,
            trials_violated = :trialsViolated, trials_inconclusive = :trialsInconclusive,
            cleanup_verified = :cleanupVerified, completed_at = :completedAt, active_slot = :activeSlot
        where id = :id and status = :running
    """.trimIndent()

    val MARK_FAILED = """
        update test_spec_run
        set status = :status, cleanup_verified = :cleanupVerified, completed_at = :completedAt, failure = :failure,
            active_slot = :activeSlot
        where id = :id and status in (:pending, :running)
    """.trimIndent()

    val RECOVER_ORPHANED_RUNNING = """
        update test_spec_run
        set status = :recoveryRequired, cleanup_verified = false, completed_at = :completedAt,
            failure = :failure, active_slot = :activeSlot
        where status = :running
    """.trimIndent()

    val FAIL_ORPHANED_PENDING = """
        update test_spec_run
        set status = :failed, cleanup_verified = true, completed_at = :completedAt,
            failure = :failure, active_slot = null
        where status = :pending
    """.trimIndent()

    val INSERT_TRIAL = """
        insert into test_spec_trial_result (
            run_id, trial_number, outcome, state_changed, completed, failure, verdicts_json, timings_json,
            observations_json
        ) values (
            :runId, :trialNumber, :outcome, :stateChanged, :completed, :failure, :verdictsJson, :timingsJson,
            :observationsJson
        )
    """.trimIndent()

    val INSERT_RESET = """
        insert into test_spec_reset_result (
            run_id, sequence_number, performed, verified, checks_json, failure
        ) values (
            :runId, :sequenceNumber, :performed, :verified, :checksJson, :failure
        )
    """.trimIndent()

    val FIND_TRIALS = """
        select run_id, trial_number, outcome, state_changed, completed, failure, verdicts_json, timings_json,
               observations_json
        from test_spec_trial_result
        where run_id = :runId
        order by trial_number
    """.trimIndent()

    val FIND_RESETS = """
        select run_id, sequence_number, performed, verified, checks_json, failure
        from test_spec_reset_result
        where run_id = :runId
        order by sequence_number
    """.trimIndent()
}
