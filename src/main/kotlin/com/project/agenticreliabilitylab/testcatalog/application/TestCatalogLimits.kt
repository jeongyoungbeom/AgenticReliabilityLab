package com.project.agenticreliabilitylab.testcatalog.application

/**
 * Upper bound on one candidate set, shared by the generator and the validator.
 *
 * Both need the same number: the generator must not build more than may be stored, and the validator must reject a
 * proposal that exceeds it. Keeping one constant means the two cannot drift apart silently.
 */
internal const val MAX_TEST_CANDIDATES = 50
