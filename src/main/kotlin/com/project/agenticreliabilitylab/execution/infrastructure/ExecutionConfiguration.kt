package com.project.agenticreliabilitylab.execution.infrastructure

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ExecutionConfiguration {
    @Bean("arlJobTaskExecutor")
    fun arlJobTaskExecutor(properties: OutboxJobProperties): TaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = properties.maxConcurrentJobs
        maxPoolSize = properties.maxConcurrentJobs
        setQueueCapacity(NO_QUEUE)
        setThreadNamePrefix("arl-job-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS)
        initialize()
    }

    private companion object {
        const val NO_QUEUE = 0
        const val SHUTDOWN_WAIT_SECONDS = 30
    }
}
