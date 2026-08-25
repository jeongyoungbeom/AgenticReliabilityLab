package com.project.agenticreliabilitylab.testspec.infrastructure.sql

object TestSpecificationSql {
    private val SELECT_SPECIFICATION = """
        select id, target_system_id, spec_key, version, title, profile_version_id, source, category, risk, status,
               document_json, checksum, created_by, created_correlation_id, created_at,
               approved_by, approved_correlation_id, approved_at, terminal_reason
        from test_specification
    """.trimIndent()

    val INSERT = """
        insert into test_specification (
            id, target_system_id, spec_key, version, title, profile_version_id, source, category, risk, status,
            document_json, checksum, created_by, created_correlation_id, created_at,
            approved_by, approved_correlation_id, approved_at, terminal_reason
        ) values (
            :id, :targetSystemId, :specKey, :version, :title, :profileVersionId, :source, :category, :risk, :status,
            :documentJson, :checksum, :createdBy, :createdCorrelationId, :createdAt,
            null, null, null, null
        )
    """.trimIndent()

    val FIND_BY_ID = "$SELECT_SPECIFICATION where id = :id"

    val FIND_BY_TARGET_AND_KEY = """
        $SELECT_SPECIFICATION
        where target_system_id = :targetSystemId and spec_key = :specKey
        order by version desc
    """.trimIndent()

    val FIND_APPROVED_BY_TARGET = """
        $SELECT_SPECIFICATION
        where target_system_id = :targetSystemId and status = :approved
        order by spec_key, version
    """.trimIndent()

    val FIND_BY_TARGET = """
        $SELECT_SPECIFICATION
        where target_system_id = :targetSystemId
        order by created_at desc
        limit :limit
    """.trimIndent()

    val APPROVE = """
        update test_specification
        set status = :approved, approved_by = :actor, approved_correlation_id = :correlationId,
            approved_at = :approvedAt
        where id = :id and status = :pending
    """.trimIndent()

    val REVISE_PROFILE_VERSION = """
        update test_specification
        set profile_version_id = :profileVersionId
        where id = :id and profile_version_id = :expectedProfileVersionId
          and status in (:draft, :pending, :approved)
    """.trimIndent()

    val SUPERSEDE = """
        update test_specification
        set status = :superseded, terminal_reason = :reason
        where id = :id and status in (:draft, :pending, :approved)
    """.trimIndent()
}
