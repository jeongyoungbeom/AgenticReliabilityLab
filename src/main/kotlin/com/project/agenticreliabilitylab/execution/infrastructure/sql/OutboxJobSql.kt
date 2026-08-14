package com.project.agenticreliabilitylab.execution.infrastructure.sql

/** SQL owned by the durable-outbox adapter; binding and row mapping stay in the repository. */
object OutboxJobSql {
    val ENQUEUE = """
        insert into arl_outbox_job (
            id, job_type, aggregate_id, status, attempt_count, available_at,
            lease_owner, lease_expires_at, last_error, created_at, completed_at
        ) values (
            :id, :jobType, :aggregateId, :status, 0, :availableAt,
            null, null, null, :createdAt, null
        )
        on conflict (job_type, aggregate_id) do update
        set status = case
                when arl_outbox_job.status in (:completed, :failed) then :status
                else arl_outbox_job.status
            end,
            attempt_count = case
                when arl_outbox_job.status in (:completed, :failed) then 0
                else arl_outbox_job.attempt_count
            end,
            available_at = case
                when arl_outbox_job.status in (:completed, :failed) then :availableAt
                else arl_outbox_job.available_at
            end,
            lease_owner = case
                when arl_outbox_job.status in (:completed, :failed) then null
                else arl_outbox_job.lease_owner
            end,
            lease_expires_at = case
                when arl_outbox_job.status in (:completed, :failed) then null
                else arl_outbox_job.lease_expires_at
            end,
            last_error = case
                when arl_outbox_job.status in (:completed, :failed) then null
                else arl_outbox_job.last_error
            end,
            completed_at = case
                when arl_outbox_job.status in (:completed, :failed) then null
                else arl_outbox_job.completed_at
            end
    """.trimIndent()

    val ENQUEUE_H2 = """
        insert into arl_outbox_job (
            id, job_type, aggregate_id, status, attempt_count, available_at,
            lease_owner, lease_expires_at, last_error, created_at, completed_at
        ) values (
            :id, :jobType, :aggregateId, :status, 0, :availableAt,
            null, null, null, :createdAt, null
        )
    """.trimIndent()

    val REENQUEUE_H2 = """
        update arl_outbox_job
        set status = :pending,
            attempt_count = 0,
            available_at = :availableAt,
            lease_owner = null,
            lease_expires_at = null,
            last_error = null,
            completed_at = null
        where job_type = :jobType and aggregate_id = :aggregateId
          and status in (:completed, :failed)
    """.trimIndent()

    fun nextEligibleJob(typePredicate: String): String = """
        select id, job_type, aggregate_id, status, attempt_count, available_at,
               lease_owner, lease_expires_at, last_error, created_at, completed_at
        from arl_outbox_job
        where job_type in ($typePredicate)
          and (
                (status = :pending and available_at <= :now)
             or (status = :running and lease_expires_at <= :now)
          )
        order by available_at, created_at, id
        fetch first 1 rows only
    """.trimIndent()

    val CLAIM = """
        update arl_outbox_job
        set status = :running,
            attempt_count = attempt_count + 1,
            lease_owner = :leaseOwner,
            lease_expires_at = :leaseExpiresAt,
            last_error = null
        where id = :id
          and (
                (status = :pending and available_at <= :now)
             or (status = :running and lease_expires_at <= :now)
          )
    """.trimIndent()

    val COMPLETE = """
        update arl_outbox_job
        set status = :completed,
            completed_at = :completedAt,
            lease_owner = null,
            lease_expires_at = null
        where id = :id and status = :running and lease_owner = :leaseOwner
    """.trimIndent()

    val RENEW_LEASE = """
        update arl_outbox_job
        set lease_expires_at = :leaseExpiresAt
        where id = :id and status = :running and lease_owner = :leaseOwner
    """.trimIndent()

    val RELEASE_CLAIM = """
        update arl_outbox_job
        set status = :pending,
            attempt_count = case when attempt_count > 0 then attempt_count - 1 else 0 end,
            available_at = :availableAt,
            lease_owner = null,
            lease_expires_at = null
        where id = :id and status = :running and lease_owner = :leaseOwner
    """.trimIndent()

    val DEFER = """
        update arl_outbox_job
        set status = :pending,
            attempt_count = case when attempt_count > 0 then attempt_count - 1 else 0 end,
            available_at = :availableAt,
            lease_owner = null,
            lease_expires_at = null,
            last_error = null
        where id = :id and status = :running and lease_owner = :leaseOwner
    """.trimIndent()

    val RETRY_OR_FAIL = """
        update arl_outbox_job
        set status = :status,
            available_at = :availableAt,
            lease_owner = null,
            lease_expires_at = null,
            last_error = :lastError,
            completed_at = :completedAt
        where id = :id and status = :running and lease_owner = :leaseOwner
    """.trimIndent()

    const val PENDING_COUNT = "select count(*) from arl_outbox_job where status = :status"
}
