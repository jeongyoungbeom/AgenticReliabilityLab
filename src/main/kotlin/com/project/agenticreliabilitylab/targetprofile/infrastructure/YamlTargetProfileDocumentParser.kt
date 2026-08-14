package com.project.agenticreliabilitylab.targetprofile.infrastructure

import com.project.agenticreliabilitylab.targetprofile.application.port.TargetProfileDocumentParser
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDefinition
import org.springframework.stereotype.Component

/** Strict, offline-only parser for a single untrusted Target Profile YAML document. */
@Component
class YamlTargetProfileDocumentParser(
    private val loader: SafeYamlTargetProfileLoader,
    private val mapper: TargetProfileYamlDefinitionMapper,
) : TargetProfileDocumentParser {
    override fun parse(document: String): TargetProfileDefinition = mapper.map(loader.load(document))
}
