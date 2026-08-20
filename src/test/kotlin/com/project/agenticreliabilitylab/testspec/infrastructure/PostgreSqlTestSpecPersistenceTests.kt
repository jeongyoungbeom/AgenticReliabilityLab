package com.project.agenticreliabilitylab.testspec.infrastructure

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Runs the same Phase 17 persistence cases on the production database engine. */
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlTestSpecPersistenceTests : JdbcTestSpecPersistenceTests() {
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
