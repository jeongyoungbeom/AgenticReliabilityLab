package com.project.agenticreliabilitylab.targetdiscovery.infrastructure.sql

object PilotTestSessionSql {
    const val SELECT_SESSION = """
        select id, target_system_id, profile_version_id, status, idempotency_key, request_hash,
               created_by, created_correlation_id, created_at, result_outcome, cleanup_verified, completed_at, failure
        from pilot_test_session
    """

    const val FIND_BY_ID = "$SELECT_SESSION where id = :id"

    const val FIND_BY_TARGET_AND_IDEMPOTENCY =
        "$SELECT_SESSION where target_system_id = :targetSystemId " +
            "and idempotency_key = :idempotencyKey"

    const val FIND_BY_TARGET = "$SELECT_SESSION where target_system_id = :targetSystemId " +
        "order by created_at desc limit :limit"

    const val INSERT_SESSION = """
        insert into pilot_test_session (
            id, target_system_id, profile_version_id, status, idempotency_key, request_hash,
            created_by, created_correlation_id, created_at
        ) values (
            :id, :targetSystemId, :profileVersionId, :status, :idempotencyKey, :requestHash,
            :createdBy, :createdCorrelationId, :createdAt
        )
    """

    const val COMPLETE_SESSION = """
        update pilot_test_session
        set status = :status, result_outcome = :resultOutcome, cleanup_verified = :cleanupVerified,
            completed_at = :completedAt, failure = :failure
        where id = :id and status = :running
    """

    const val INSERT_ITEM = """
        insert into pilot_test_session_item (
            session_id, sequence_number, candidate_id, specification_id, test_spec_run_id,
            status, result_outcome, cleanup_verified, failure_code, failure_message, completed_at
        ) values (
            :sessionId, :sequenceNumber, :candidateId, :specificationId, :testSpecRunId,
            :status, :resultOutcome, :cleanupVerified, :failureCode, :failureMessage, :completedAt
        )
    """

    const val FIND_ITEMS = """
        select session_id, sequence_number, candidate_id, specification_id, test_spec_run_id,
               status, result_outcome, cleanup_verified, failure_code, failure_message, completed_at
        from pilot_test_session_item
        where session_id = :sessionId
        order by sequence_number
    """

    const val RECOVER_RUNNING = """
        update pilot_test_session
        set status = :recoveryRequired, result_outcome = :inconclusive, cleanup_verified = false,
            completed_at = :completedAt, failure = :failure
        where status = :running
    """
}
