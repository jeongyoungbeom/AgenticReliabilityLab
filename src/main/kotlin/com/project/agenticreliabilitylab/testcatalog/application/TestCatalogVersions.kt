package com.project.agenticreliabilitylab.testcatalog.application

/**
 * Identifies the deterministic candidate rules.
 *
 * Bump this whenever a rule change would recommend different tests for the same Knowledge Snapshot, so that
 * regenerating produces a new candidate set instead of returning the previous one.
 */
internal const val TEST_CANDIDATE_GENERATOR_VERSION = "test-candidate-rules-v1"
