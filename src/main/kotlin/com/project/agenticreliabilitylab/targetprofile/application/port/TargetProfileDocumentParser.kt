package com.project.agenticreliabilitylab.targetprofile.application.port

import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition

/** Converts one untrusted Profile document into the application Profile model without performing network I/O. */
interface TargetProfileDocumentParser {
    fun parse(document: String): TargetProfileDefinition
}
