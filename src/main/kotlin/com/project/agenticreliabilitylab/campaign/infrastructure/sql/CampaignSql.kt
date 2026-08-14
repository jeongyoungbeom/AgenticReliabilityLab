package com.project.agenticreliabilitylab.campaign.infrastructure.sql

/** SQL owned by the campaign-run JDBC adapter. */
object CampaignSql {
    val FIND_RUN_BY_ID = """
        select id, target_system_id, idempotency_key, parameters_json, repeat_count,
               status, created_at, started_at, completed_at
        from campaign_run
        where id = :id
    """.trimIndent()

    val FIND_RUN_BY_TARGET_AND_IDEMPOTENCY = """
        select id, target_system_id, idempotency_key, parameters_json, repeat_count,
               status, created_at, started_at, completed_at
        from campaign_run
        where target_system_id = :targetSystemId and idempotency_key = :idempotencyKey
    """.trimIndent()

    val FIND_RUNNING_RUNS = """
        select id, target_system_id, idempotency_key, parameters_json, repeat_count,
               status, created_at, started_at, completed_at
        from campaign_run
        where status = :status
    """.trimIndent()

    val FIND_STEPS = """
        select id, campaign_run_id, sequence_number, status, experiment_run_id,
               lease_owner, lease_expires_at, fencing_token,
               queued_at, started_at, completed_at, failure_code, failure_message
        from campaign_step_run
        where campaign_run_id = :campaignRunId
        order by sequence_number
    """.trimIndent()

    val INSERT_RUN = """
        insert into campaign_run (
            id, campaign_definition_id, campaign_definition_version, target_system_id,
            idempotency_key, parameters_json, repeat_count, status, failure_budget, created_at, started_at, completed_at
        ) values (
            :id, :definitionId, 1, :targetSystemId,
            :idempotencyKey, :parametersJson, :repeatCount, :status, 0, :createdAt, :startedAt, null
        )
    """.trimIndent()

    val INSERT_STEP = """
        insert into campaign_step_run (
            id, campaign_run_id, step_key, step_definition_version, sequence_number,
            logical_attempt, status, experiment_run_id, dependency_result, cooldown_until,
            lease_owner, lease_expires_at, fencing_token, queued_at, started_at, completed_at,
            failure_code, failure_message
        ) values (
            :id, :campaignRunId, :stepKey, :definitionVersion, :sequenceNumber,
            1, :status, null, null, null,
            null, null, 0, :queuedAt, null, null,
            null, null
        )
    """.trimIndent()

    const val LOCK_RUN = "select id from campaign_run where id = :campaignRunId for update"

    val FIND_NEXT_QUEUED_STEP = """
        select id, campaign_run_id, sequence_number, status, experiment_run_id,
               lease_owner, lease_expires_at, fencing_token,
               queued_at, started_at, completed_at, failure_code, failure_message
        from campaign_step_run
        where campaign_run_id = :campaignRunId and status = :status
        order by sequence_number
        fetch first 1 rows only
    """.trimIndent()

    val CLAIM_NEXT_QUEUED_STEP = """
        update campaign_step_run
        set status = :running,
            started_at = :startedAt,
            lease_owner = :workerId,
            lease_expires_at = :leaseExpiresAt,
            fencing_token = fencing_token + 1
        where id = :id
          and status = :queued
          and not exists (
              select 1
              from campaign_step_run active_step
              where active_step.campaign_run_id = :campaignRunId
                and active_step.status = :running
          )
    """.trimIndent()

    val ATTACH_EXPERIMENT = """
        update campaign_step_run
        set experiment_run_id = :experimentRunId
        where id = :stepId
          and campaign_run_id = :campaignRunId
          and status = :running
          and lease_owner = :leaseOwner
          and experiment_run_id is null
    """.trimIndent()

    val FIND_EXPIRED_STEP = """
        select id
        from campaign_step_run
        where campaign_run_id = :campaignRunId
          and status = :running
          and lease_expires_at <= :now
        order by sequence_number
        fetch first 1 rows only
    """.trimIndent()

    val TAKE_OVER_EXPIRED_STEP = """
        update campaign_step_run
        set lease_owner = :workerId,
            lease_expires_at = :leaseExpiresAt,
            fencing_token = fencing_token + 1
        where id = :id
          and status = :running
          and lease_expires_at <= :now
    """.trimIndent()

    val RENEW_STEP_LEASE = """
        update campaign_step_run
        set lease_expires_at = :leaseExpiresAt
        where id = :id
          and status = :running
          and lease_owner = :leaseOwner
          and fencing_token = :fencingToken
          and lease_expires_at > :now
    """.trimIndent()

    val COMPLETE_STEP = """
        update campaign_step_run
        set status = :status, completed_at = :completedAt,
            failure_code = :failureCode, failure_message = :failureMessage
        where id = :id
          and status = :running
          and lease_owner = :leaseOwner
          and fencing_token = :fencingToken
          and lease_expires_at > :completedAt
    """.trimIndent()

    val COMPLETE_RUN = """
        update campaign_run
        set status = :status, completed_at = :completedAt
        where id = :id and status = :running
    """.trimIndent()

    val COMPLETE_RUN_IF_ALL_STEPS_COMPLETED = """
        update campaign_run
        set status = :completed, completed_at = :completedAt
        where id = :id
          and status = :running
          and not exists (
              select 1
              from campaign_step_run
              where campaign_run_id = :id
                and status <> :stepCompleted
          )
    """.trimIndent()
}
