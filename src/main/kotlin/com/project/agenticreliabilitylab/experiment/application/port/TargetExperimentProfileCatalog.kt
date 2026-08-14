package com.project.agenticreliabilitylab.experiment.application.port

import com.project.agenticreliabilitylab.experiment.domain.TargetExperimentProfile

interface TargetExperimentProfileCatalog {
    fun requireStockConcurrency(targetSystemId: String): TargetExperimentProfile
}
