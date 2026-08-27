package com.project.agenticreliabilitylab.targetdiscovery.infrastructure

import com.project.agenticreliabilitylab.targetdiscovery.application.PilotTemplateExecutionService
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A restart can interrupt an aggregate after a child Test Spec Run has begun. Mark the aggregate as requiring
 * recovery so the recorded result can never be mistaken for a completed Pilot selection.
 */
@Configuration
class PilotTestSessionRecoveryConfiguration {
    @Bean
    fun pilotTestSessionRecoveryRunner(service: PilotTemplateExecutionService): ApplicationRunner =
        ApplicationRunner { service.recoverIncompleteSessions() }
}
