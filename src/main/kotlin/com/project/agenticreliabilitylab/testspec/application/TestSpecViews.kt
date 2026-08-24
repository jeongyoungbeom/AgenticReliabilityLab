package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.StoredResetResult
import com.project.agenticreliabilitylab.testspec.domain.StoredTestSpecification
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun
import java.util.UUID

data class TestSpecificationView(
    val specification: StoredTestSpecification,
    val profileVersionActive: Boolean,
    val requiredConfirmation: String,
    val unfoundedThresholds: List<String>,
)

data class TestSpecRunView(
    val run: TestSpecRun,
    val trials: List<StoredTrialResult>,
    val resets: List<StoredResetResult>,
)

/**
 * The outcome of triggering one specification's regression run as part of a target-wide batch (Phase 22-D).
 *
 * A batch never lets one specification's failure lose the results already gathered for the others - [run] is set
 * on success, [failureCode]/[failureMessage] are set when [com.project.agenticreliabilitylab.testspec.application
 * .TestSpecificationService.execute] rejected this specification (for example a blocking recovery-required run on
 * the same Target), and exactly one of the two is non-null.
 */
data class TestSpecRegressionRunOutcome(
    val specificationId: UUID,
    val specKey: String,
    val version: Int,
    val run: TestSpecRunView?,
    val failureCode: String?,
    val failureMessage: String?,
)
