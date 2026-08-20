package com.project.agenticreliabilitylab.testplan.api

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Runs the Test Plan cases against PostgreSQL instead of H2 compatibility mode.
 *
 * Approval and dispatch are guarded by conditional updates, so their correctness under concurrency rests on how the
 * engine locks a contended row and on what a blocked update sees once the lock is released. H2 answers those questions
 * its own way, so the same cases are replayed here on the engine that actually runs in production.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlTestPlanApiIntegrationTests : TestPlanApiIntegrationTests() {
    private class ArlPostgreSqlContainer : PostgreSQLContainer<ArlPostgreSqlContainer>("postgres:17-alpine")

    companion object {
        @Container
        @JvmStatic
        private val postgres = ArlPostgreSqlContainer()

        @JvmStatic
        @DynamicPropertySource
        fun postgresDataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.datasource.driver-class-name") { postgres.driverClassName }
        }
    }
}
