package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.targetintelligence.application.sha256Hex
import com.project.agenticreliabilitylab.testspec.domain.TestSpecification

/** Ignores presentation and step names while retaining call order and the conditions being judged. */
internal object TestSpecProposalFingerprint {
    private val placeholder = Regex("\\{\\{[^}]+}}|\\{[^}]+}")
    private val whitespace = Regex("\\s+")
    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")

    fun of(specification: TestSpecification): String {
        val operations = specification.setup.map { step ->
            "SETUP:${call(step.call.method, step.call.path)}"
        } + specification.workload.map { step ->
            if (step.call == null) step.kind.name else
                "${step.kind}:${call(step.call.method, step.call.path)}:${step.requestCount}:${step.concurrency}"
        }
        val stepNames = (specification.setup.map { it.name } + specification.workload.map { it.name })
            .mapIndexed { index, name -> name.lowercase() to "step$index" }.toMap()
        val observations = specification.observations.map { observation ->
            observation.id to
                "${observation.sourceKind}:${observation.sourceName.orEmpty()}:" +
                normalize(observation.expression, stepNames)
        }.sortedBy { it.second }
        val observationNames = observations.mapIndexed { index, (id, _) ->
            id.lowercase() to "observation$index"
        }.toMap()
        val purpose = specification.invariants.map { invariant ->
            normalize(invariant.condition, observationNames)
        }.sorted()
        val parts = operations + specification.category.name + purpose + observations.map { it.second }
        return sha256Hex(parts.joinToString("\u0000"))
    }

    private fun call(method: String, path: String): String =
        "${method.uppercase()} ${placeholder.replace(path, "{}")}"

    private fun normalize(value: String, names: Map<String, String>): String {
        val normalized = identifier.replace(value.lowercase()) { match ->
            names[match.value] ?: match.value
        }
        return whitespace.replace(normalized, "")
    }
}
