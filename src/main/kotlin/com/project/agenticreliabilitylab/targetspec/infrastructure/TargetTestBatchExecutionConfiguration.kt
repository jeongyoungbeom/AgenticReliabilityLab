package com.project.agenticreliabilitylab.targetspec.infrastructure

import com.project.agenticreliabilitylab.targetspec.application.TargetTestBatchService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

@Configuration
class TargetTestBatchExecutionConfiguration {
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun targetTestBatchRecoveryRunner(service: TargetTestBatchService): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> service.recoverIncompleteBatches() }
}
