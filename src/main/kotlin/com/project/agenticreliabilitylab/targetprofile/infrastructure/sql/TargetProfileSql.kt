package com.project.agenticreliabilitylab.targetprofile.infrastructure.sql

/** SQL owned by the versioned Target Profile JDBC adapter. */
object TargetProfileSql {
    private val SELECT_VERSION = """
        select version.id, version.target_system_id, version.source, version.status, version.checksum, version.config_json,
               version.created_by, version.created_at, version.activated_by, version.activated_at
        from target_profile_version version
    """.trimIndent()

    val FIND_VERSION_BY_ID = "$SELECT_VERSION where version.id = :id"
    val FIND_VERSION_BY_TARGET_AND_CHECKSUM =
        "$SELECT_VERSION where version.target_system_id = :targetSystemId and version.checksum = :checksum"
    val FIND_ACTIVE_BY_TARGET = """
        $SELECT_VERSION
        join target_profile_active active on active.profile_version_id = version.id
        where active.target_system_id = :targetSystemId
    """.trimIndent()
    val FIND_ALL_ACTIVE = """
        $SELECT_VERSION
        join target_profile_active active on active.profile_version_id = version.id
        order by version.target_system_id
    """.trimIndent()

    val INSERT_VERSION_ON_CONFLICT = """
        insert into target_profile_version (
            id, target_system_id, source, status, checksum, config_json, created_by, created_at, activated_by, activated_at
        ) values (
            :id, :targetSystemId, :source, :status, :checksum, :configJson, :createdBy, :createdAt, null, null
        )
        on conflict (target_system_id, checksum) do nothing
    """.trimIndent()

    val INSERT_VERSION = """
        insert into target_profile_version (
            id, target_system_id, source, status, checksum, config_json, created_by, created_at, activated_by, activated_at
        ) values (
            :id, :targetSystemId, :source, :status, :checksum, :configJson, :createdBy, :createdAt, null, null
        )
    """.trimIndent()

    val SUPERSEDE_ACTIVE_VERSIONS = """
        update target_profile_version
        set status = :superseded
        where target_system_id = :targetSystemId and status = :active and id <> :versionId
    """.trimIndent()

    val MARK_VERSION_ACTIVE = """
        update target_profile_version
        set status = :active, activated_by = :actor, activated_at = :activatedAt
        where id = :versionId and target_system_id = :targetSystemId
    """.trimIndent()

    val UPSERT_ACTIVE_POINTER_POSTGRES = """
        insert into target_profile_active (target_system_id, profile_version_id, activated_by, activated_at)
        values (:targetSystemId, :versionId, :actor, :activatedAt)
        on conflict (target_system_id) do update
        set profile_version_id = excluded.profile_version_id,
            activated_by = excluded.activated_by,
            activated_at = excluded.activated_at
    """.trimIndent()

    val UPDATE_ACTIVE_POINTER = """
        update target_profile_active
        set profile_version_id = :versionId, activated_by = :actor, activated_at = :activatedAt
        where target_system_id = :targetSystemId
    """.trimIndent()

    val INSERT_ACTIVE_POINTER = """
        insert into target_profile_active (target_system_id, profile_version_id, activated_by, activated_at)
        values (:targetSystemId, :versionId, :actor, :activatedAt)
    """.trimIndent()

    val INSERT_AUDIT_EVENT = """
        insert into target_profile_audit_event (
            id, target_system_id, profile_version_id, event_type, actor, correlation_id, occurred_at
        ) values (
            :id, :targetSystemId, :profileVersionId, :eventType, :actor, :correlationId, :occurredAt
        )
    """.trimIndent()
}
