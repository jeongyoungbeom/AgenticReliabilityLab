package com.project.agenticreliabilitylab.execution.infrastructure

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.execution.domain.OutboxJobType
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the leases and compare-and-set logic on PostgreSQL rather than H2 compatibility mode. */
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlOutboxJobIntegrationTests {
    private lateinit var repository: JdbcOutboxJobRepository
    private lateinit var transactionTemplate: TransactionTemplate
    private val idSequence = AtomicInteger()

    @BeforeEach
    fun resetDatabase() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load()
            .also {
                it.clean()
                it.migrate()
            }
        repository = JdbcOutboxJobRepository(
            JdbcClient.create(dataSource),
            IdentifierGenerator { UUID(0, idSequence.incrementAndGet().toLong()) },
        )
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    fun `only one PostgreSQL transaction claims the same pending durable job`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        repository.enqueue(OutboxJobType.EXPERIMENT_EXECUTION, UUID.randomUUID(), now)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val claimed = (1..2).map { index ->
                executor.submit<com.project.agenticreliabilitylab.execution.domain.OutboxJob?> {
                    start.await()
                    transactionTemplate.execute {
                        repository.claimNext(
                            workerId = "worker-$index",
                            now = now,
                            leaseExpiresAt = now.plus(Duration.ofMinutes(1)),
                            eligibleTypes = OutboxJobType.entries.toSet(),
                        )
                    }
                }
            }
            start.countDown()
            val results = claimed.map { it.get() }

            assertEquals(1, results.count { it != null })
            assertEquals(1, results.filterNotNull().single().attemptCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `deferred PostgreSQL job remains retryable without spending its failure budget`() {
        val now = Instant.parse("2026-08-13T00:00:00Z")
        repository.enqueue(OutboxJobType.CAMPAIGN_EXECUTION, UUID.randomUUID(), now)
        val claimed = transactionTemplate.execute {
            repository.claimNext("worker-1", now, now.plus(Duration.ofMinutes(1)), OutboxJobType.entries.toSet())
        }
        assertNotNull(claimed)
        assertTrue(repository.defer(claimed.id, "worker-1", now.plusSeconds(5)))

        val tooEarly = transactionTemplate.execute {
            repository.claimNext("worker-2", now.plusSeconds(4), now.plusSeconds(64), OutboxJobType.entries.toSet())
        }
        assertNull(tooEarly)
        val reclaimed = transactionTemplate.execute {
            repository.claimNext("worker-2", now.plusSeconds(5), now.plusSeconds(65), OutboxJobType.entries.toSet())
        }
        assertNotNull(reclaimed)
        assertEquals(1, reclaimed.attemptCount)
    }

    private class ArlPostgreSqlContainer : PostgreSQLContainer<ArlPostgreSqlContainer>("postgres:17-alpine")

    companion object {
        @Container
        @JvmStatic
        private val postgres = ArlPostgreSqlContainer()
    }
}
