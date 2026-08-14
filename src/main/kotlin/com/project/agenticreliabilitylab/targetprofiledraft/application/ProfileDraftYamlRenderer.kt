package com.project.agenticreliabilitylab.targetprofiledraft.application

import com.project.agenticreliabilitylab.targetprofiledraft.domain.DraftReadOnlyOperation
import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraft
import com.project.agenticreliabilitylab.targetprofiledraft.domain.TargetProfileDraftSource
import org.springframework.stereotype.Component

/** Renders a conservative, disabled YAML Draft that must still pass normal Profile validation and activation. */
@Component
class ProfileDraftYamlRenderer {
    fun render(
        source: TargetProfileDraftSource,
        targetId: String,
        targetName: String,
        baseUrl: String?,
        operations: List<DraftReadOnlyOperation>,
        warnings: List<String>,
    ): TargetProfileDraft {
        val origin = baseUrl ?: LOOPBACK_PLACEHOLDER_ORIGIN
        return TargetProfileDraft(
            source = source,
            suggestedTargetId = targetId,
            suggestedTargetName = targetName,
            suggestedBaseUrl = baseUrl,
            readOnlyOperations = operations,
            yaml = yaml(targetId, targetName, origin, operations),
            warnings = warnings,
        )
    }

    private fun yaml(
        targetId: String,
        targetName: String,
        origin: String,
        operations: List<DraftReadOnlyOperation>,
    ): String = buildString {
        appendLine("arl:")
        appendLine("  targets:")
        appendLine("    registrations:")
        appendLine("      - id: ${quote(targetId)}")
        appendLine("        name: ${quote(targetName)}")
        appendLine("        adapter-type: HTTP_TARGET")
        appendLine("        environment: TEST")
        appendLine("        base-url: ${quote(origin)}")
        appendLine("        allowed-origin: ${quote(origin)}")
        appendLine("        allowed-cidrs: [127.0.0.0/8]")
        appendLine("        health-path: /actuator/health")
        appendLine("        source-repository: replace-before-activation")
        appendLine("        identity-verification: CONFIGURATION_ONLY")
        appendLine("        capabilities: [HEALTH, HTTP_API]")
        appendLine("        enabled: false")
        appendLine("  target-specs:")
        appendLine("    registrations:")
        appendLine("      - target-system-id: ${quote(targetId)}")
        appendLine("        execution-enabled: false")
        appendLine("        host-resource-group: ${quote(targetId)}")
        appendLine("        max-batch-size: 10")
        appendLine("        request-timeout: 5s")
        appendLine("        read-only-operations:")
        operations.forEach { operation -> appendOperation(operation) }
    }

    private fun StringBuilder.appendOperation(operation: DraftReadOnlyOperation) {
        appendLine("          - id: ${quote(operation.id)}")
        appendLine("            title: ${quote(operation.title)}")
        appendLine("            description: ${quote(operation.description)}")
        appendLine("            path: ${quote(operation.path)}")
        appendLine("            expected-status-codes: [${operation.expectedStatusCodes.sorted().joinToString()}]")
    }

    private fun quote(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\""

    private companion object {
        const val LOOPBACK_PLACEHOLDER_ORIGIN = "http://127.0.0.1:8080"
    }
}
