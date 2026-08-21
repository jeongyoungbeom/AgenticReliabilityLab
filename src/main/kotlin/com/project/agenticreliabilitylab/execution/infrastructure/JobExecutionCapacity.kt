package com.project.agenticreliabilitylab.execution.infrastructure

import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore

/**
 * Reserves executor capacity before a durable job is claimed. This prevents
 * queue pressure from consuming retries for work that never began execution.
 */
@Component
class JobExecutionCapacity(properties: OutboxJobProperties) {
    private val totalPermits = Semaphore(properties.maxConcurrentJobs, true)
    private val targetPermits = Semaphore(properties.maxConcurrentTargetJobs, true)
    private val analysisPermits = Semaphore(properties.maxConcurrentAnalysisJobs, true)

    fun availableJobTypes(): Set<OutboxJobType> {
        if (totalPermits.availablePermits() == 0) return emptySet()
        return OutboxJobType.entries.filterTo(linkedSetOf()) { permitsFor(it).availablePermits() > 0 }
    }

    @Suppress("ReturnCount") // The two semaphore acquisition failures must release only the permits actually held.
    fun tryAcquire(type: OutboxJobType): Boolean {
        if (!totalPermits.tryAcquire()) return false
        if (permitsFor(type).tryAcquire()) return true
        totalPermits.release()
        return false
    }

    fun release(type: OutboxJobType) {
        permitsFor(type).release()
        totalPermits.release()
    }

    private fun permitsFor(type: OutboxJobType): Semaphore = when (type) {
        OutboxJobType.EXPERIMENT_EXECUTION,
        OutboxJobType.CAMPAIGN_EXECUTION,
        OutboxJobType.TARGET_TEST_BATCH_EXECUTION,
        -> targetPermits

        OutboxJobType.SINGLE_ANALYSIS,
        OutboxJobType.MULTI_ANALYSIS,
        OutboxJobType.FOLLOW_UP_SUGGESTION,
        OutboxJobType.ROOT_CAUSE_REPORT,
        OutboxJobType.TEST_SPEC_GENERATION,
        -> analysisPermits
    }
}
