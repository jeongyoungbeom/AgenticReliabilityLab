package com.project.agenticreliabilitylab.target.infrastructure.sql

/** SQL owned by the Target System JDBC adapter. */
object TargetSystemSql {
    val SELECT_BASE = """
        select id,
               name,
               adapter_type,
               environment,
               base_url,
               allowed_origin,
               allowed_cidrs,
               health_path,
               source_repository,
               identity_verification,
               capabilities,
               enabled,
               created_at,
               updated_at
        from target_system
    """.trimIndent()

    val FIND_ALL = "$SELECT_BASE order by id"
    val FIND_BY_ID = "$SELECT_BASE where id = :id"
    const val LOCK_FOR_PROFILE_ACTIVATION = "select id from target_system where id = :id for update"

    val UPSERT_POSTGRES = """
        insert into target_system (
            id, name, adapter_type, environment, base_url, allowed_origin,
            allowed_cidrs, health_path, source_repository, identity_verification,
            capabilities, enabled, created_at, updated_at
        ) values (
            :id, :name, :adapterType, :environment, :baseUrl, :allowedOrigin,
            :allowedCidrs, :healthPath, :sourceRepository, :identityVerification,
            :capabilities, :enabled, :createdAt, :updatedAt
        )
        on conflict (id) do update
        set name = excluded.name,
            adapter_type = excluded.adapter_type,
            environment = excluded.environment,
            base_url = excluded.base_url,
            allowed_origin = excluded.allowed_origin,
            allowed_cidrs = excluded.allowed_cidrs,
            health_path = excluded.health_path,
            source_repository = excluded.source_repository,
            identity_verification = excluded.identity_verification,
            capabilities = excluded.capabilities,
            enabled = excluded.enabled,
            updated_at = excluded.updated_at
    """.trimIndent()

    val INSERT = """
        insert into target_system (
            id, name, adapter_type, environment, base_url, allowed_origin,
            allowed_cidrs, health_path, source_repository, identity_verification,
            capabilities, enabled, created_at, updated_at
        ) values (
            :id, :name, :adapterType, :environment, :baseUrl, :allowedOrigin,
            :allowedCidrs, :healthPath, :sourceRepository, :identityVerification,
            :capabilities, :enabled, :createdAt, :updatedAt
        )
    """.trimIndent()

    val UPDATE = """
        update target_system
        set name = :name,
            adapter_type = :adapterType,
            environment = :environment,
            base_url = :baseUrl,
            allowed_origin = :allowedOrigin,
            allowed_cidrs = :allowedCidrs,
            health_path = :healthPath,
            source_repository = :sourceRepository,
            identity_verification = :identityVerification,
            capabilities = :capabilities,
            enabled = :enabled,
            updated_at = :updatedAt
        where id = :id
    """.trimIndent()
}
