package com.project.agenticreliabilitylab.infrastructure

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlMigrationIntegrationTests {
    @Test
    fun `all Flyway migrations apply cleanly on PostgreSQL`() {
        val flyway = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()

        val migrationResult = flyway.migrate()

        assertTrue(migrationResult.migrationsExecuted > 0)
        assertEquals(0, flyway.info().pending().size)
    }

    private class ArlPostgreSqlContainer : PostgreSQLContainer<ArlPostgreSqlContainer>("postgres:17-alpine")

    companion object {
        @Container
        @JvmStatic
        private val postgres = ArlPostgreSqlContainer()
    }
}
