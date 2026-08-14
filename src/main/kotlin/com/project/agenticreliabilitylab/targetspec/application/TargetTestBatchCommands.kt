package com.project.agenticreliabilitylab.targetspec.application

data class CreateTargetTestBatch(
    val targetSystemId: String,
    val candidateIds: List<String>,
)

class TargetTestBatchNotFoundException(batchId: Any) :
    com.project.agenticreliabilitylab.common.ResourceNotFoundException("Target test batch", batchId)

class TargetTestBatchRequestException(
    override val code: String,
    override val message: String,
    cause: Throwable? = null,
) : com.project.agenticreliabilitylab.common.ClientRequestException(code, message, cause)
