package com.project.agenticreliabilitylab.experiment.application

import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The sole persistence representation of STOCK_CONCURRENCY parameters.
 * Canonical field order keeps request hashes and idempotency comparisons stable.
 */
@Component
class StockConcurrencyParametersCodec(
    private val objectMapper: ObjectMapper,
) {
    fun encode(parameters: StockConcurrencyParameters): String = objectMapper.writeValueAsString(
        linkedMapOf(
            "stock" to parameters.stock,
            "requestCount" to parameters.requestCount,
            "concurrency" to parameters.concurrency,
            "quantityPerRequest" to parameters.quantityPerRequest,
        ),
    )

    fun decode(payload: String): StockConcurrencyParameters {
        val root = try {
            objectMapper.readTree(payload)
        } catch (exception: JacksonException) {
            throw IllegalArgumentException("Stored stock-concurrency parameters are not valid JSON", exception)
        }
        require(root.isObject) { "Stored stock-concurrency parameters must be a JSON object" }
        return StockConcurrencyParameters(
            stock = root.requiredPositiveInt("stock"),
            requestCount = root.requiredPositiveInt("requestCount"),
            concurrency = root.requiredPositiveInt("concurrency"),
            quantityPerRequest = root.requiredPositiveInt("quantityPerRequest"),
        )
    }

    private fun JsonNode.requiredPositiveInt(field: String): Int {
        val value = path(field)
        require(value.isIntegralNumber && value.canConvertToInt()) {
            "Stored stock-concurrency parameter '$field' must be a 32-bit integer"
        }
        return value.intValue().also {
            require(it > 0) { "Stored stock-concurrency parameter '$field' must be positive" }
        }
    }
}
