package com.project.agenticreliabilitylab.analysis.infrastructure.sql

/** SQL owned by the analysis-comparison and evaluation JDBC adapter. */
object AnalysisEvaluationSql {
    private val SELECT_COMPARISON = """
        select id, experiment_run_id, target_test_batch_id, analysis_dataset_id, idempotency_key, model_keys_json,
               configuration_json, configuration_hash, requested_at
        from analysis_comparison
    """.trimIndent()

    val INSERT_COMPARISON = """
        insert into analysis_comparison (
            id, experiment_run_id, target_test_batch_id, analysis_dataset_id, idempotency_key, model_keys_json,
            configuration_json, configuration_hash, requested_at
        ) values (
            :id, :experimentRunId, :targetTestBatchId, :analysisDatasetId, :idempotencyKey, :modelKeysJson,
            :configurationJson, :configurationHash, :requestedAt
        )
    """.trimIndent()

    val FIND_COMPARISON_BY_ID = "$SELECT_COMPARISON where id = :id"
    val FIND_COMPARISON_BY_EXPERIMENT_AND_IDEMPOTENCY_KEY =
        "$SELECT_COMPARISON where experiment_run_id = :experimentRunId and idempotency_key = :idempotencyKey"
    val FIND_COMPARISON_BY_BATCH_AND_IDEMPOTENCY_KEY =
        "$SELECT_COMPARISON where target_test_batch_id = :targetTestBatchId and idempotency_key = :idempotencyKey"

    val INSERT_COMPARISON_RUN = """
        insert into analysis_comparison_run (analysis_comparison_id, model_key, analysis_run_id)
        values (:comparisonId, :modelKey, :analysisRunId)
    """.trimIndent()

    val FIND_COMPARISON_RUNS = """
        select analysis_comparison_id, model_key, analysis_run_id
        from analysis_comparison_run
        where analysis_comparison_id = :comparisonId
        order by model_key
    """.trimIndent()

    val INSERT_EVALUATION = """
        insert into analysis_evaluation (
            id, analysis_run_id, analysis_ground_truth_id, evaluation_version, verdict_match,
            cited_required_evidence_count, required_evidence_count, citation_recall, score, evaluated_at
        ) values (
            :id, :analysisRunId, :analysisGroundTruthId, :evaluationVersion, :verdictMatch,
            :citedRequiredEvidenceCount, :requiredEvidenceCount, :citationRecall, :score, :evaluatedAt
        )
    """.trimIndent()

    val FIND_EVALUATION = """
        select id, analysis_run_id, analysis_ground_truth_id, evaluation_version, verdict_match,
               cited_required_evidence_count, required_evidence_count, citation_recall, score, evaluated_at
        from analysis_evaluation
        where analysis_run_id = :analysisRunId
          and analysis_ground_truth_id = :groundTruthId
          and evaluation_version = :evaluationVersion
    """.trimIndent()
}
