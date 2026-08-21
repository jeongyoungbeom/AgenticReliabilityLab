package com.project.agenticreliabilitylab.testspec.application.port

/**
 * A deliberately narrow model port for proposing test specifications.
 *
 * It sees only the supplied input bundle and has no tools, Target access, or side effects. Everything it proposes
 * still has to pass the same validator every other specification passes before it can be stored or approved.
 */
interface TestSpecProposalModel {
    fun propose(request: TestSpecProposalModelRequest): TestSpecProposalModelResponse
}

data class TestSpecProposalModelRequest(
    val modelId: String,
    val systemInstruction: String,
    val inputBundleJson: String,
)

data class TestSpecProposalModelResponse(
    val content: String,
    val promptTokenCount: Int? = null,
    val completionTokenCount: Int? = null,
    val durationMillis: Long? = null,
)

class TestSpecProposalModelUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
