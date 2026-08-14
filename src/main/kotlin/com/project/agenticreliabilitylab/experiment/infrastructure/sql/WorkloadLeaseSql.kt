package com.project.agenticreliabilitylab.experiment.infrastructure.sql

/** SQL owned by the workload-lease JDBC adapter. */
object WorkloadLeaseSql {
    val ACQUIRE_EXPIRED = """
        update workload_lease
        set mode = :mode,
            owner_type = :ownerType,
            owner_id = :ownerId,
            lease_owner = :leaseOwner,
            lease_expires_at = :expiresAt,
            fencing_token = fencing_token + 1,
            last_heartbeat_at = :now
        where host_resource_group = :hostResourceGroup
          and lease_expires_at <= :now
    """.trimIndent()

    val FIND_FENCING_TOKEN = """
        select fencing_token
        from workload_lease
        where host_resource_group = :hostResourceGroup
          and owner_id = :ownerId
          and lease_owner = :leaseOwner
    """.trimIndent()

    val INSERT = """
        insert into workload_lease (
            host_resource_group, mode, owner_type, owner_id, lease_owner,
            lease_expires_at, fencing_token, last_heartbeat_at
        ) values (
            :hostResourceGroup, :mode, :ownerType, :ownerId, :leaseOwner,
            :expiresAt, 1, :now
        )
    """.trimIndent()

    val DELETE = """
        delete from workload_lease
        where host_resource_group = :hostResourceGroup
          and owner_id = :ownerId
          and lease_owner = :leaseOwner
          and fencing_token = :fencingToken
    """.trimIndent()
}
