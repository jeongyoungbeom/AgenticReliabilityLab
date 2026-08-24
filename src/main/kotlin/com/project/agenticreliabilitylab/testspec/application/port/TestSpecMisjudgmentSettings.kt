package com.project.agenticreliabilitylab.testspec.application.port

/** Configuration boundary for misjudgment-triggered exception drafting, kept separate from generation limits. */
interface TestSpecMisjudgmentSettings {
    val enabled: Boolean
    val promptVersion: String
    val maxOutputBytes: Int
}
