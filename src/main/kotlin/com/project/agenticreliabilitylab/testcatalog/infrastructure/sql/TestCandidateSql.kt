package com.project.agenticreliabilitylab.testcatalog.infrastructure.sql

/** SQL owned by the test-candidate JDBC adapter. */
object TestCandidateSql {
    private val SELECT_GENERATION = """
        select id, target_system_id, knowledge_snapshot_id, profile_version_id, source, generator_version, checksum,
               created_by, created_correlation_id, created_at
        from test_candidate_generation
    """.trimIndent()

    val INSERT_GENERATION = """
        insert into test_candidate_generation (
            id, target_system_id, knowledge_snapshot_id, profile_version_id, source, generator_version, checksum,
            created_by, created_correlation_id, created_at
        ) values (
            :id, :targetSystemId, :knowledgeSnapshotId, :profileVersionId, :source, :generatorVersion, :checksum,
            :createdBy, :createdCorrelationId, :createdAt
        )
    """.trimIndent()

    /** PostgreSQL variant that keeps a duplicate generation from aborting the surrounding transaction. */
    val INSERT_GENERATION_ON_CONFLICT = """
        $INSERT_GENERATION
        on conflict (knowledge_snapshot_id, checksum) do nothing
    """.trimIndent()

    val INSERT_CANDIDATE = """
        insert into test_candidate (
            id, generation_id, sequence_number, category, title, description, risk, confidence, binding_kind,
            detail_json
        ) values (
            :id, :generationId, :sequenceNumber, :category, :title, :description, :risk, :confidence, :bindingKind,
            :detailJson
        )
    """.trimIndent()

    val FIND_GENERATION_BY_ID = "$SELECT_GENERATION where id = :id"

    val FIND_GENERATION_BY_SNAPSHOT_AND_CHECKSUM =
        "$SELECT_GENERATION where knowledge_snapshot_id = :knowledgeSnapshotId and checksum = :checksum"

    val FIND_GENERATIONS_BY_TARGET = """
        $SELECT_GENERATION
        where target_system_id = :targetSystemId
        order by created_at desc
        limit :limit
    """.trimIndent()

    val FIND_CANDIDATES_BY_GENERATION = """
        select id, generation_id, sequence_number, category, title, description, risk, confidence, binding_kind,
               detail_json
        from test_candidate
        where generation_id = :generationId
        order by sequence_number
    """.trimIndent()
}
