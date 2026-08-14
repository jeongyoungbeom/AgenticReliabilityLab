package com.project.agenticreliabilitylab.experiment.infrastructure

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import com.project.agenticreliabilitylab.experiment.infrastructure.sql.StockConcurrencyDefinitionSql
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

@Component
class StockConcurrencyDefinitionInitializer(
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
    private val identifierGenerator: IdentifierGenerator,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val now = clock.instant()
        val updated = jdbcClient.sql(StockConcurrencyDefinitionSql.UPDATE_DEFINITION).params(parameters(now)).update()
        if (updated > 0) {
            return
        }

        try {
            jdbcClient.sql(StockConcurrencyDefinitionSql.INSERT_DEFINITION)
                .params(parameters(now) + ("id" to identifierGenerator.next())).update()
        } catch (_: DuplicateKeyException) {
            jdbcClient.sql(StockConcurrencyDefinitionSql.UPDATE_DEFINITION).params(parameters(now)).update()
        }
    }

    private fun parameters(now: Instant): Map<String, Any> = mapOf(
        "experimentType" to "STOCK_CONCURRENCY",
        "definitionVersion" to DEFINITION_VERSION,
        "definitionJson" to "{\"input\":[\"stock\",\"requestCount\",\"concurrency\",\"quantityPerRequest\"],\"targetContract\":\"TARGET_PROFILE_AND_ADAPTER\",\"requiredEvidence\":[\"successCount\",\"failureCount\",\"oversellCount\",\"finalRedisStock\",\"finalDbStock\"]}",
        "enabled" to true,
        "createdAt" to Timestamp.from(now),
    )

    private companion object {
        const val DEFINITION_VERSION = "stock-concurrency-v1"
    }
}
