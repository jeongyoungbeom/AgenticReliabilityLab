package com.project.agenticreliabilitylab.execution.infrastructure

import com.project.agenticreliabilitylab.analysis.application.FollowUpSuggestionService
import com.project.agenticreliabilitylab.analysis.application.MultiReliabilityAgent
import com.project.agenticreliabilitylab.analysis.application.RootCauseReportService
import com.project.agenticreliabilitylab.analysis.application.SingleReliabilityAgent
import com.project.agenticreliabilitylab.campaign.application.CampaignExecutionService
import com.project.agenticreliabilitylab.execution.application.OutboxJobExecutionResult
import com.project.agenticreliabilitylab.execution.application.OutboxJobHandler
import com.project.agenticreliabilitylab.execution.application.OutboxJobHandlerRegistry
import com.project.agenticreliabilitylab.execution.application.TypedOutboxJobHandler
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import com.project.agenticreliabilitylab.experiment.application.StockConcurrencyExperimentService
import com.project.agenticreliabilitylab.targetspec.application.TargetTestBatchService
import com.project.agenticreliabilitylab.testspec.application.TestSpecGenerationService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Keeps feature-to-job routing outside the generic durable-job worker. */
@Configuration(proxyBeanMethods = false)
class OutboxJobHandlerConfiguration {
    @Bean
    fun experimentOutboxJobHandler(experimentService: StockConcurrencyExperimentService): OutboxJobHandler =
        TypedOutboxJobHandler(OutboxJobType.EXPERIMENT_EXECUTION) {
            experimentService.executeOutboxJob(it)
            OutboxJobExecutionResult.Completed
        }

    @Bean
    fun campaignOutboxJobHandler(campaignService: CampaignExecutionService): OutboxJobHandler =
        TypedOutboxJobHandler(OutboxJobType.CAMPAIGN_EXECUTION, campaignService::executeOutboxJob)

    @Bean
    fun targetTestBatchOutboxJobHandler(batchService: TargetTestBatchService): OutboxJobHandler =
        TypedOutboxJobHandler(OutboxJobType.TARGET_TEST_BATCH_EXECUTION) {
            batchService.executeOutboxJob(it)
            OutboxJobExecutionResult.Completed
        }

    @Bean
    fun singleAnalysisOutboxJobHandler(singleAgent: SingleReliabilityAgent): OutboxJobHandler =
        TypedOutboxJobHandler(OutboxJobType.SINGLE_ANALYSIS) {
            singleAgent.executeOutboxJob(it)
            OutboxJobExecutionResult.Completed
        }

    @Bean
    fun multiAnalysisOutboxJobHandler(multiAgent: MultiReliabilityAgent): OutboxJobHandler =
        TypedOutboxJobHandler(OutboxJobType.MULTI_ANALYSIS) {
            multiAgent.executeOutboxJob(it)
            OutboxJobExecutionResult.Completed
        }

    @Bean
    fun followUpSuggestionOutboxJobHandler(followUpSuggestionService: FollowUpSuggestionService): OutboxJobHandler =
        TypedOutboxJobHandler(OutboxJobType.FOLLOW_UP_SUGGESTION) {
            followUpSuggestionService.executeOutboxJob(it)
            OutboxJobExecutionResult.Completed
        }

    @Bean
    fun rootCauseReportOutboxJobHandler(rootCauseReportService: RootCauseReportService): OutboxJobHandler =
        TypedOutboxJobHandler(OutboxJobType.ROOT_CAUSE_REPORT) {
            rootCauseReportService.executeOutboxJob(it)
            OutboxJobExecutionResult.Completed
        }

    @Bean
    fun testSpecGenerationOutboxJobHandler(generationService: TestSpecGenerationService): OutboxJobHandler =
        TypedOutboxJobHandler(OutboxJobType.TEST_SPEC_GENERATION) {
            generationService.executeOutboxJob(it)
            OutboxJobExecutionResult.Completed
        }

    @Bean
    fun outboxJobHandlerRegistry(handlers: List<OutboxJobHandler>) = OutboxJobHandlerRegistry(handlers)
}
