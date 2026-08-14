package com.project.agenticreliabilitylab.experiment.infrastructure

import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyExperimentService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

@Configuration
class ExperimentExecutionConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun experimentRecoveryRunner(service: StockConcurrencyExperimentService): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> service.recoverIncompleteRuns() }
}
