package com.project.agenticreliabilitylab.campaign.infrastructure

import com.project.agenticreliabilitylab.campaign.application.CampaignExecutionService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

@Configuration
class CampaignExecutionConfiguration {
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 100)
    fun campaignRecoveryRunner(service: CampaignExecutionService): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> service.recoverActiveCampaigns() }
}
