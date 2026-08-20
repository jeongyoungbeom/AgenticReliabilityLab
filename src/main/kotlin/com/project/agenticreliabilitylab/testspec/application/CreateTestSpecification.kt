package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.testspec.domain.SpecSource

data class CreateTestSpecification(
    val targetSystemId: String,
    val source: SpecSource,
    val documentJson: String,
)
