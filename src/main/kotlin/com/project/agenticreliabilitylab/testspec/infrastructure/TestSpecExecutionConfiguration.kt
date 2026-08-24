package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.TestSpecGenerationService
import com.project.agenticreliabilitylab.testspec.application.TestSpecMisjudgmentReportService
import com.project.agenticreliabilitylab.testspec.application.port.TestSpecRunStore
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import java.time.Clock

/** Reconciles synchronous Runner state left behind by an earlier process before new requests are accepted. */
@Configuration
class TestSpecExecutionConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun testSpecRecoveryRunner(runStore: TestSpecRunStore, clock: Clock): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> runStore.recoverIncompleteRuns(clock.instant()) }

    @Bean
    fun testSpecGenerationRecoveryRunner(generationService: TestSpecGenerationService): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> generationService.recoverIncompleteRuns() }

    @Bean
    fun testSpecMisjudgmentRecoveryRunner(misjudgmentService: TestSpecMisjudgmentReportService): ApplicationRunner =
        ApplicationRunner { _: ApplicationArguments -> misjudgmentService.recoverIncompleteRuns() }
}
