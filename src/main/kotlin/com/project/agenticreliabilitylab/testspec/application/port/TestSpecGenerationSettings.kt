package com.project.agenticreliabilitylab.testspec.application.port

/** Configuration boundary for LLM-proposed specification generation, kept separate from execution limits. */
interface TestSpecGenerationSettings {
    val enabled: Boolean
    val promptVersion: String
    val maxOutputBytes: Int
    val maxCandidates: Int
    val maxOpenApiDocumentBytes: Int
    val maxInputBytes: Int
}
