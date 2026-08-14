package com.project.agenticreliabilitylab.experiment.application.port

import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters
import java.util.UUID

/** The campaign module's narrow contract for starting and observing experiments. */
interface CampaignExperimentOperations {
    fun validateForCampaign(targetSystemId: String, parameters: StockConcurrencyParameters)

    fun startForCampaign(
        campaignRunId: UUID,
        campaignStepRunId: UUID,
        targetSystem: String,
        parameters: StockConcurrencyParameters,
    ): ExperimentRunRecord

    fun find(runId: UUID): ExperimentRunRecord
}
