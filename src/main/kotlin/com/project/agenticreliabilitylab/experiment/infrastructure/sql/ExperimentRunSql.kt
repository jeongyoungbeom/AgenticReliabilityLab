package com.project.agenticreliabilitylab.experiment.infrastructure.sql

/** SQL owned by the normalized experiment-run JDBC adapter. */
object ExperimentRunSql {
    val FIND_BY_ID = """
        select id, target_system_id, experiment_type, experiment_definition_version, parameters_json,
               planned_run_spec_id, idempotency_key, run_status, system_outcome, invariant_result_json,
               outcome_reason, cleanup_status, cleanup_failure_code, cleanup_failure_message,
               queued_at, started_at, completed_at
        from experiment_run
        where id = :id
    """.trimIndent()

    val FIND_BY_TARGET_AND_IDEMPOTENCY = """
        select id, target_system_id, experiment_type, experiment_definition_version, parameters_json,
               planned_run_spec_id, idempotency_key, run_status, system_outcome, invariant_result_json,
               outcome_reason, cleanup_status, cleanup_failure_code, cleanup_failure_message,
               queued_at, started_at, completed_at
        from experiment_run
        where target_system_id = :targetSystemId and idempotency_key = :idempotencyKey
    """.trimIndent()

    val INSERT_PLANNED_RUN_SPEC = """
        insert into planned_run_spec (
            id, target_system_id, experiment_definition_version, normalized_parameters_json,
            load_profile_json, fixture_plan_json, expected_target_revision, expected_service_count,
            host_resource_group, risk_assessment_id, spec_hash, created_at
        ) values (
            :id, :targetSystemId, :definitionVersion, :parametersJson,
            :loadProfileJson, :fixturePlanJson, null, null,
            :hostResourceGroup, null, :specHash, :createdAt
        )
    """.trimIndent()

    val INSERT_RUN = """
        insert into experiment_run (
            id, target_system_id, campaign_run_id, campaign_step_run_id, experiment_type,
            experiment_definition_version, parameters_json, planned_run_spec_id, pre_run_manifest_id,
            post_run_manifest_id, idempotency_key, run_status, system_outcome, invariant_result_json,
            outcome_reason, evaluated_definition_version, execution_failure_phase,
            execution_failure_owner, execution_failure_code, execution_failure_message, cleanup_status,
            cleanup_failure_code, cleanup_failure_message, cleanup_attempt, queued_at, started_at,
            completed_at, lease_owner, lease_expires_at, last_heartbeat_at, baseline_experiment_id
        ) values (
            :id, :targetSystemId, :campaignRunId, :campaignStepRunId, :experimentType,
            :definitionVersion, :parametersJson, :plannedRunSpecId, null,
            null, :idempotencyKey, :runStatus, :systemOutcome, null,
            null, null, null,
            null, null, null, :cleanupStatus,
            null, null, 0, :queuedAt, null,
            null, null, null, null, null
        )
    """.trimIndent()

    val CLAIM_FOR_EXECUTION = """
        update experiment_run
        set run_status = :validating,
            started_at = coalesce(started_at, :startedAt),
            last_heartbeat_at = :startedAt
        where id = :id
          and run_status = :created
    """.trimIndent()

    val UPDATE_RUN_STATUS = """
        update experiment_run
        set run_status = :status,
            last_heartbeat_at = :now
        where id = :id
    """.trimIndent()

    val INSERT_MANIFEST = """
        insert into run_manifest (id, planned_run_spec_id, phase, payload_json, manifest_hash, observed_at)
        select :manifestId, planned_run_spec_id, :phase, :payloadJson, :checksum, :observedAt
        from experiment_run
        where id = :runId
    """.trimIndent()

    const val UPDATE_PRE_RUN_MANIFEST = "update experiment_run set pre_run_manifest_id = :manifestId where id = :runId"
    val UPDATE_POST_RUN_MANIFEST = """
        update experiment_run
        set post_run_manifest_id = :manifestId
        where id = :runId
    """.trimIndent()

    val INSERT_ACTION = """
        insert into experiment_action (
            id, experiment_run_id, action_id, action_type, request_hash, status,
            target_operation_id, fencing_token, attempt, dispatched_at, confirmed_at, last_error
        ) values (
            :id, :runId, :actionId, :actionType, :requestHash, :status,
            null, :fencingToken, 1, null, null, null
        )
    """.trimIndent()

    val MARK_ACTION_DISPATCHED = """
        update experiment_action
        set status = :status, dispatched_at = :dispatchedAt
        where experiment_run_id = :runId and action_id = :actionId and status = :planned
    """.trimIndent()

    val MARK_ACTION_CONFIRMED = """
        update experiment_action
        set status = :status, target_operation_id = :targetOperationId, confirmed_at = :confirmedAt
        where experiment_run_id = :runId and action_id = :actionId
    """.trimIndent()

    val MARK_ACTIONS_UNKNOWN = """
        update experiment_action
        set status = :unknown, last_error = :message
        where experiment_run_id = :runId and status = :dispatched
    """.trimIndent()

    val INSERT_RESOURCE = """
        insert into experiment_resource (
            id, experiment_run_id, action_id, resource_type, resource_id, namespace,
            cleanup_status, cleanup_attempt, last_cleanup_error
        ) values (
            :id, :runId, :actionId, :resourceType, :resourceId, :namespace,
            :cleanupStatus, 1, null
        )
    """.trimIndent()

    val INSERT_EVIDENCE = """
        insert into experiment_evidence (
            id, experiment_run_id, evidence_type, schema_version, source, collector_version,
            observed_at, window_start, window_end, unit, aggregation_method, sample_count,
            completeness, payload_json, artifact_refs_json, checksum, created_at
        ) values (
            :id, :runId, :evidenceType, :schemaVersion, :source, :collectorVersion,
            :observedAt, null, null, null, null, null,
            :completeness, :payloadJson, :artifactRefsJson, :checksum, :createdAt
        )
    """.trimIndent()

    val INSERT_ARTIFACT = """
        insert into evidence_artifact (
            id, experiment_run_id, artifact_type, storage_reference, checksum,
            content_length, retention_until, created_at
        ) values (
            :id, :runId, :artifactType, :storageReference, :checksum,
            null, null, :createdAt
        )
    """.trimIndent()

    val COMPLETE_RUN = """
        update experiment_run
        set run_status = :runStatus,
            system_outcome = :systemOutcome,
            invariant_result_json = :invariantResultJson,
            outcome_reason = :outcomeReason,
            evaluated_definition_version = :definitionVersion,
            execution_failure_phase = :failurePhase,
            execution_failure_owner = :failureOwner,
            execution_failure_code = :failureCode,
            execution_failure_message = :failureMessage,
            cleanup_status = :cleanupStatus,
            cleanup_failure_code = :cleanupFailureCode,
            cleanup_failure_message = :cleanupFailureMessage,
            completed_at = :completedAt,
            last_heartbeat_at = :completedAt
        where id = :id
    """.trimIndent()

    val FIND_EVIDENCE = """
        select id, experiment_run_id, evidence_type, schema_version, source, observed_at,
               completeness, payload_json, artifact_refs_json, checksum, created_at
        from experiment_evidence
        where experiment_run_id = :runId
        order by created_at, id
    """.trimIndent()

    val HAS_BLOCKING_CLEANUP = """
        select count(*)
        from experiment_run
        where target_system_id = :targetSystemId
          and cleanup_status in (:failed, :unknown)
    """.trimIndent()

    val FIND_IN_PROGRESS_IDS = """
        select id
        from experiment_run
        where run_status in (:validating, :preparing, :running, :collecting, :cleaning)
    """.trimIndent()

    val FIND_CREATED_IDS = """
        select id
        from experiment_run
        where run_status = :created
    """.trimIndent()

    val MARK_SCHEDULING_FAILED = """
        update experiment_run
        set run_status = :failed,
            system_outcome = :systemOutcome,
            outcome_reason = :outcomeReason,
            execution_failure_phase = :failurePhase,
            execution_failure_owner = :failureOwner,
            execution_failure_code = :failureCode,
            execution_failure_message = :failureMessage,
            cleanup_status = :cleanupStatus,
            completed_at = :completedAt,
            last_heartbeat_at = :completedAt
        where id = :id and run_status = :created
    """.trimIndent()
}
