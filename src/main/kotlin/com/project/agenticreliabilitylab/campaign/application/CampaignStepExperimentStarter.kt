package com.project.agenticreliabilitylab.campaign.application

import com.project.agenticreliabilitylab.campaign.domain.CampaignRunRecord
import com.project.agenticreliabilitylab.campaign.domain.CampaignStepRunRecord
import com.project.agenticreliabilitylab.campaign.application.port.CampaignStepExperimentLinkStore
import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyParametersCodec
import com.project.agenticreliabilitylab.experiment.application.port.CampaignExperimentOperations
import com.project.agenticreliabilitylab.experiment.domain.ExperimentRunRecord
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Creates an experiment and persists the owning campaign-step link atomically. */
@Service
class CampaignStepExperimentStarter(
    private val campaignRepository: CampaignStepExperimentLinkStore,
    private val experimentOperations: CampaignExperimentOperations,
    private val parametersCodec: StockConcurrencyParametersCodec,
) {
    @Transactional
    fun start(campaign: CampaignRunRecord, step: CampaignStepRunRecord): ExperimentRunRecord {
        val experiment = experimentOperations.startForCampaign(
            campaignRunId = campaign.id,
            campaignStepRunId = step.id,
            targetSystem = campaign.targetSystemId,
            parameters = campaign.parameters(),
        )
        check(campaignRepository.attachExperimentRun(step, experiment.id)) {
            "Could not link experiment '${experiment.id}' to campaign step '${step.id}'"
        }
        return experiment
    }

    private fun CampaignRunRecord.parameters() = parametersCodec.decode(parametersJson)
}
