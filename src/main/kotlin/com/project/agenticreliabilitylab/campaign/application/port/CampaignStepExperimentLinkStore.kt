package com.project.agenticreliabilitylab.campaign.application.port

import com.project.agenticreliabilitylab.campaign.domain.CampaignStepRunRecord
import java.util.UUID

/** Minimal persistence capability required while atomically starting a campaign step. */
interface CampaignStepExperimentLinkStore {
    fun attachExperimentRun(step: CampaignStepRunRecord, experimentRunId: UUID): Boolean
}
