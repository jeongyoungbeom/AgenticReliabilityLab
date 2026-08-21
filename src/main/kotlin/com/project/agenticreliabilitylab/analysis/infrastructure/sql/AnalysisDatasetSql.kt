package com.project.agenticreliabilitylab.analysis.infrastructure.sql

/** SQL owned by the immutable analysis-dataset JDBC adapter. */
object AnalysisDatasetSql {
    val INSERT_DATASET = """
        insert into analysis_dataset (
            id, experiment_run_id, target_test_batch_id, test_spec_run_id, contract_version,
            evidence_bundle_json, evidence_ids_json, checksum, evidence_count, created_at
        ) values (
            :id, :experimentRunId, :targetTestBatchId, :testSpecRunId, :contractVersion,
            :evidenceBundleJson, :evidenceIdsJson, :checksum, :evidenceCount, :createdAt
        )
    """.trimIndent()

    val SELECT_DATASET_BASE = """
        select id, experiment_run_id, target_test_batch_id, test_spec_run_id, contract_version,
               evidence_bundle_json, evidence_ids_json, checksum, evidence_count, created_at
        from analysis_dataset
    """.trimIndent()

    val FIND_DATASET_BY_ID = "$SELECT_DATASET_BASE where id = :id"

    val INSERT_GROUND_TRUTH = """
        insert into analysis_ground_truth (
            id, analysis_dataset_id, ground_truth_version, expected_verdict,
            required_evidence_ids_json, notes, created_at
        ) values (
            :id, :analysisDatasetId, :version, :expectedVerdict,
            :requiredEvidenceIdsJson, :notes, :createdAt
        )
    """.trimIndent()

    val SELECT_GROUND_TRUTH_BASE = """
        select id, analysis_dataset_id, ground_truth_version, expected_verdict,
               required_evidence_ids_json, notes, created_at
        from analysis_ground_truth
    """.trimIndent()

    val FIND_GROUND_TRUTH_BY_ID = "$SELECT_GROUND_TRUTH_BASE where id = :id"
}
