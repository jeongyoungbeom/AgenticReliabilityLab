package com.project.agenticreliabilitylab.targetintelligence.infrastructure.sql

/** SQL owned by the Knowledge Snapshot JDBC adapter. */
object TargetKnowledgeSnapshotSql {
    private val SELECT_SNAPSHOT = """
        select id, target_system_id, profile_version_id, checksum, extraction_version, content_json, created_by,
               created_correlation_id, created_at, confirmed_by, confirmed_correlation_id, confirmed_at
        from target_knowledge_snapshot
    """.trimIndent()

    val INSERT_SNAPSHOT = """
        insert into target_knowledge_snapshot (
            id, target_system_id, profile_version_id, checksum, extraction_version, content_json, created_by,
            created_correlation_id, created_at, confirmed_by, confirmed_correlation_id, confirmed_at
        ) values (
            :id, :targetSystemId, :profileVersionId, :checksum, :extractionVersion, :contentJson, :createdBy,
            :createdCorrelationId, :createdAt, null, null, null
        )
    """.trimIndent()

    /** PostgreSQL variant that keeps a duplicate intake from aborting the surrounding transaction. */
    val INSERT_SNAPSHOT_ON_CONFLICT = """
        $INSERT_SNAPSHOT
        on conflict (profile_version_id, checksum) do nothing
    """.trimIndent()

    val FIND_BY_ID = "$SELECT_SNAPSHOT where id = :id"

    val FIND_BY_PROFILE_VERSION_AND_CHECKSUM =
        "$SELECT_SNAPSHOT where profile_version_id = :profileVersionId and checksum = :checksum"

    val FIND_BY_TARGET = """
        $SELECT_SNAPSHOT
        where target_system_id = :targetSystemId
        order by created_at desc
        limit :limit
    """.trimIndent()

    val CONFIRM = """
        update target_knowledge_snapshot
        set confirmed_by = :confirmedBy,
            confirmed_correlation_id = :confirmedCorrelationId,
            confirmed_at = :confirmedAt
        where id = :id and confirmed_at is null
    """.trimIndent()
}
