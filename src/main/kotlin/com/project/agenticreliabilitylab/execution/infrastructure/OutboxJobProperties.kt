package com.project.agenticreliabilitylab.execution.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("arl.jobs")
data class OutboxJobProperties(
    val enabled: Boolean = true,
    val pollDelay: Duration = Duration.ofMillis(DEFAULT_POLL_DELAY_MILLIS),
    val maxJobsPerPoll: Int = DEFAULT_MAX_JOBS_PER_POLL,
    val maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS,
    val maxConcurrentTargetJobs: Int = DEFAULT_MAX_CONCURRENT_TARGET_JOBS,
    val maxConcurrentAnalysisJobs: Int = DEFAULT_MAX_CONCURRENT_ANALYSIS_JOBS,
    val leaseDuration: Duration = Duration.ofMinutes(DEFAULT_LEASE_DURATION_MINUTES),
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(!pollDelay.isNegative && !pollDelay.isZero) { "arl.jobs.poll-delay must be positive" }
        require(maxJobsPerPoll in MIN_JOBS_PER_POLL..MAX_JOBS_PER_POLL) {
            "arl.jobs.max-jobs-per-poll must be between $MIN_JOBS_PER_POLL and $MAX_JOBS_PER_POLL"
        }
        require(maxConcurrentJobs in MIN_CONCURRENT_JOBS..MAX_CONCURRENT_JOBS) {
            "arl.jobs.max-concurrent-jobs must be between $MIN_CONCURRENT_JOBS and $MAX_CONCURRENT_JOBS"
        }
        require(maxConcurrentTargetJobs in MIN_CONCURRENT_JOBS..maxConcurrentJobs) {
            "arl.jobs.max-concurrent-target-jobs must be between $MIN_CONCURRENT_JOBS and max-concurrent-jobs"
        }
        require(maxConcurrentAnalysisJobs in MIN_CONCURRENT_JOBS..maxConcurrentJobs) {
            "arl.jobs.max-concurrent-analysis-jobs must be between $MIN_CONCURRENT_JOBS and max-concurrent-jobs"
        }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "arl.jobs.lease-duration must be positive" }
        require(maxAttempts in MIN_ATTEMPTS..MAX_ATTEMPTS) {
            "arl.jobs.max-attempts must be between $MIN_ATTEMPTS and $MAX_ATTEMPTS"
        }
    }

    private companion object {
        const val DEFAULT_POLL_DELAY_MILLIS = 200L
        const val DEFAULT_MAX_JOBS_PER_POLL = 4
        const val DEFAULT_MAX_CONCURRENT_JOBS = 4
        const val DEFAULT_MAX_CONCURRENT_TARGET_JOBS = 2
        const val DEFAULT_MAX_CONCURRENT_ANALYSIS_JOBS = 2
        const val DEFAULT_LEASE_DURATION_MINUTES = 30L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val MIN_JOBS_PER_POLL = 1
        const val MAX_JOBS_PER_POLL = 100
        const val MIN_CONCURRENT_JOBS = 1
        const val MAX_CONCURRENT_JOBS = 64
        const val MIN_ATTEMPTS = 1
        const val MAX_ATTEMPTS = 10
    }
}
