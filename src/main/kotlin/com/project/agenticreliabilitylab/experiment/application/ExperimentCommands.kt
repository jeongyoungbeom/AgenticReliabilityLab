package com.project.agenticreliabilitylab.experiment.application

import com.project.agenticreliabilitylab.experiment.domain.StockConcurrencyParameters

data class StartStockConcurrencyExperiment(
    val targetSystem: String,
    val parameters: StockConcurrencyParametersInput,
)

data class StockConcurrencyParametersInput(
    val stock: Int?,
    val requestCount: Int?,
    val concurrency: Int?,
    val quantityPerRequest: Int?,
) {
    fun toDomain(): StockConcurrencyParameters = StockConcurrencyParameters(
        stock = stock ?: throw IllegalArgumentException("stock is required"),
        requestCount = requestCount ?: throw IllegalArgumentException("requestCount is required"),
        concurrency = concurrency ?: throw IllegalArgumentException("concurrency is required"),
        quantityPerRequest = quantityPerRequest ?: throw IllegalArgumentException("quantityPerRequest is required"),
    )

    companion object {
        fun from(parameters: StockConcurrencyParameters): StockConcurrencyParametersInput =
            StockConcurrencyParametersInput(
                stock = parameters.stock,
                requestCount = parameters.requestCount,
                concurrency = parameters.concurrency,
                quantityPerRequest = parameters.quantityPerRequest,
            )
    }
}

class ExperimentNotFoundException(runId: Any) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Experiment run", runId)

class ExperimentRequestException(
    override val code: String,
    override val message: String,
    cause: Throwable? = null,
) : com.project.agenticreliabilitylab.common.ClientRequestException(code, message, cause)
