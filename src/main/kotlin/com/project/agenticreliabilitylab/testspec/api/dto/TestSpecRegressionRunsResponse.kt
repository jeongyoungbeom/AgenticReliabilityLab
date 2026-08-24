package com.project.agenticreliabilitylab.testspec.api.dto

import com.project.agenticreliabilitylab.testspec.application.TestSpecRegressionRunOutcome
import java.util.UUID

data class TestSpecRegressionRunOutcomeResponse(
    val specificationId: UUID,
    val specKey: String,
    val version: Int,
    val run: TestSpecRunResponse?,
    val failureCode: String?,
    val failureMessage: String?,
) {
    companion object {
        fun from(outcome: TestSpecRegressionRunOutcome) = TestSpecRegressionRunOutcomeResponse(
            specificationId = outcome.specificationId,
            specKey = outcome.specKey,
            version = outcome.version,
            run = outcome.run?.let(TestSpecRunResponse::from),
            failureCode = outcome.failureCode,
            failureMessage = outcome.failureMessage,
        )
    }
}

data class TestSpecRegressionRunsResponse(
    val targetSystemId: String,
    val runs: List<TestSpecRegressionRunOutcomeResponse>,
) {
    companion object {
        fun from(targetSystemId: String, outcomes: List<TestSpecRegressionRunOutcome>) =
            TestSpecRegressionRunsResponse(
                targetSystemId = targetSystemId,
                runs = outcomes.map(TestSpecRegressionRunOutcomeResponse::from),
            )
    }
}
