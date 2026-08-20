package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.StoredResetResult
import com.project.agenticreliabilitylab.testspec.domain.StoredTestSpecification
import com.project.agenticreliabilitylab.testspec.domain.StoredTrialResult
import com.project.agenticreliabilitylab.testspec.domain.TestSpecRun

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
