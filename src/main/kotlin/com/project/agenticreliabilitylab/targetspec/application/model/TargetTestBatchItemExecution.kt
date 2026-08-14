package com.project.agenticreliabilitylab.targetspec.application.model

import com.project.agenticreliabilitylab.targetspec.domain.TargetTestBatchItemStatus

internal data class TargetTestBatchItemExecution(
    val status: TargetTestBatchItemStatus,
    val httpStatus: Int?,
    val latencyMs: Long?,
    val resultJson: String?,
    val failureMessage: String?,
)
