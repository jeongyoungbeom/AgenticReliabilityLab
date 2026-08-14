package com.project.agenticreliabilitylab.execution.infrastructure

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.execution.application.OutboxJobExecutionResult
import com.project.agenticreliabilitylab.execution.application.OutboxJobHandlerRegistry
import com.project.agenticreliabilitylab.execution.domain.OutboxJob
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Gauge
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Claims one durable job at a time. The aggregate state machines remain the
 * final idempotency guard; a reclaimed job is therefore safe after a restart.
 */
@Component
class OutboxJobWorker(
    private val repository: JdbcOutboxJobRepository,
    private val properties: OutboxJobProperties,
    private val capacity: JobExecutionCapacity,
    private val handlerRegistry: OutboxJobHandlerRegistry,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    identifierGenerator: IdentifierGenerator,
    @Qualifier("arlJobTaskExecutor") private val taskExecutor: TaskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "arl-job-worker:${identifierGenerator.next()}"
    private val applicationReady = AtomicBoolean(false)

    init {
        Gauge.builder("arl.jobs.pending", repository) { it.pendingCount().toDouble() }
            .description("ARL durable jobs waiting for a worker")
            .register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${arl.jobs.poll-delay:200ms}")
    fun dispatchAvailableJobs() {
        var canDispatch = properties.enabled && applicationReady.get()
        repeat(properties.maxJobsPerPoll) {
            if (canDispatch) {
                canDispatch = dispatchOne()
            }
        }
    }

    @Suppress("ReturnCount") // Capacity, empty queue, and rejected submission each have an explicit safe exit.
    private fun dispatchOne(): Boolean {
        val eligibleTypes = capacity.availableJobTypes()
        if (eligibleTypes.isEmpty()) return false
        val now = clock.instant()
        val job = repository.claimNext(workerId, now, now.plus(properties.leaseDuration), eligibleTypes)
        if (job == null) {
            return false
        }
        if (!capacity.tryAcquire(job.type)) {
            repository.releaseClaim(job.id, workerId, now)
            return false
        }
        return try {
            taskExecutor.execute {
                try {
                    execute(job)
                } finally {
                    capacity.release(job.type)
                }
            }
            true
        } catch (_: TaskRejectedException) {
            capacity.release(job.type)
            repository.releaseClaim(job.id, workerId, clock.instant())
            false
        }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun enableDispatchAfterRecovery() {
        applicationReady.set(true)
    }

    @Suppress("TooGenericExceptionCaught") // One worker boundary must safely retry any application runtime failure.
    private fun execute(job: OutboxJob) {
        val sample = io.micrometer.core.instrument.Timer.start(meterRegistry)
        val stillRunning = AtomicBoolean(true)
        val heartbeat = startLeaseHeartbeat(job, stillRunning)
        try {
            when (val result = handlerRegistry.execute(job)) {
                OutboxJobExecutionResult.Completed -> {
                    repository.complete(job.id, workerId, clock.instant())
                    meterRegistry.counter("arl.jobs.completed", "type", job.type.name).increment()
                }
                is OutboxJobExecutionResult.Deferred -> {
                    repository.defer(job.id, workerId, clock.instant().plus(result.delay))
                    meterRegistry.counter("arl.jobs.deferred", "type", job.type.name).increment()
                }
            }
        } catch (exception: RuntimeException) {
            log.error(
                "ARL outbox job failed: type={}, aggregateId={}, attempt={}",
                job.type,
                job.aggregateId,
                job.attemptCount,
                exception,
            )
            repository.retryOrFail(
                job,
                workerId,
                clock.instant(),
                properties.maxAttempts,
                exception.message ?: exception.javaClass.simpleName,
            )
            meterRegistry.counter("arl.jobs.failed", "type", job.type.name).increment()
        } finally {
            stillRunning.set(false)
            heartbeat.interrupt()
            sample.stop(meterRegistry.timer("arl.jobs.duration", "type", job.type.name))
        }
    }

    // Lease renewal must not terminate the running job on a transient runtime failure.
    @Suppress("TooGenericExceptionCaught")
    private fun startLeaseHeartbeat(job: OutboxJob, stillRunning: AtomicBoolean): Thread {
        val intervalMillis = (properties.leaseDuration.toMillis() / LEASE_HEARTBEAT_DIVISOR)
            .coerceAtLeast(MIN_HEARTBEAT_INTERVAL_MILLIS)
        return Thread.ofVirtual().name("arl-job-lease-heartbeat-").start {
            try {
                while (stillRunning.get()) {
                    Thread.sleep(intervalMillis)
                    renewLeaseIfRunning(job, stillRunning)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // A renewal failure is transient and must not stop subsequent heartbeats.
    private fun renewLeaseIfRunning(job: OutboxJob, stillRunning: AtomicBoolean) {
        if (!stillRunning.get()) return
        try {
            val renewed = repository.renewLease(
                id = job.id,
                workerId = workerId,
                leaseExpiresAt = clock.instant().plus(properties.leaseDuration),
            )
            if (!renewed) log.warn("ARL job {} no longer owns its lease", job.id)
        } catch (exception: RuntimeException) {
            log.warn("Could not renew lease for ARL job {}; will retry", job.id, exception)
        }
    }

    private companion object {
        const val MIN_HEARTBEAT_INTERVAL_MILLIS = 1_000L
        const val LEASE_HEARTBEAT_DIVISOR = 3
    }
}
