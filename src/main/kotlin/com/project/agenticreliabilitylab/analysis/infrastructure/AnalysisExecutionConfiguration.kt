package com.project.agenticreliabilitylab.analysis.infrastructure

import com.project.agenticreliabilitylab.analysis.application.MultiReliabilityAgent
import com.project.agenticreliabilitylab.analysis.application.FollowUpSuggestionService
import com.project.agenticreliabilitylab.analysis.application.RootCauseReportService
import com.project.agenticreliabilitylab.analysis.application.SingleReliabilityAgent
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

@Configuration(proxyBeanMethods = false)
class AnalysisExecutionConfiguration {
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun singleAgentRecoveryRunner(agent: SingleReliabilityAgent): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> agent.recoverIncompleteRuns() }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun multiAgentRecoveryRunner(agent: MultiReliabilityAgent): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> agent.recoverIncompleteRuns() }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun followUpSuggestionRecoveryRunner(service: FollowUpSuggestionService): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> service.recoverIncompleteRuns() }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun rootCauseReportRecoveryRunner(service: RootCauseReportService): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> service.recoverIncompleteRuns() }
}
