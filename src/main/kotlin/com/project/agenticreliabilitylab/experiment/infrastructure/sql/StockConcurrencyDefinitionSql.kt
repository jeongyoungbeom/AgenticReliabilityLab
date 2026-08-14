package com.project.agenticreliabilitylab.experiment.infrastructure.sql

/** SQL owned by the stock-concurrency definition initializer. */
object StockConcurrencyDefinitionSql {
    val UPDATE_DEFINITION = """
        update experiment_definition
        set definition_json = :definitionJson,
            enabled = :enabled
        where experiment_type = :experimentType
          and definition_version = :definitionVersion
    """.trimIndent()

    val INSERT_DEFINITION = """
        insert into experiment_definition (
            id, experiment_type, definition_version, definition_json, enabled, created_at
        ) values (
            :id, :experimentType, :definitionVersion, :definitionJson, :enabled, :createdAt
        )
    """.trimIndent()
}
