package com.project.agenticreliabilitylab.testspec.infrastructure

import com.project.agenticreliabilitylab.testspec.application.port.SpecAuthProvider
import com.project.agenticreliabilitylab.testspec.application.port.SpecAuthUnavailableException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Resolves an auth profile from the Runner's own environment.
 *
 * The credential exists here and nowhere else: it is read at the moment a request is built and returned as a
 * header. It is never stored, never written to evidence and never included in an error message, because an error
 * message is the easiest way for a secret to end up in a report someone shares.
 */
@Component
class EnvironmentSpecAuthProvider(
    private val environment: (String) -> String?,
) : SpecAuthProvider {
    @Autowired
    constructor() : this(System::getenv)

    override fun headersFor(targetSystemId: String, authProfile: String): Map<String, String> {
        val key = variableName(targetSystemId, authProfile)
        val value = environment(key)
            ?: throw SpecAuthUnavailableException(
                "No credential is configured for auth profile '$authProfile' on target '$targetSystemId'",
            )
        val header = environment("${key}_HEADER")?.takeIf(String::isNotBlank) ?: DEFAULT_HEADER
        return mapOf(header to value)
    }

    private fun variableName(targetSystemId: String, authProfile: String): String =
        "${PREFIX}_${targetSystemId.toVariablePart()}_${authProfile.toVariablePart()}"

    private fun String.toVariablePart(): String = uppercase().map { character ->
        if (character.isLetterOrDigit()) character else '_'
    }.joinToString("")

    private companion object {
        const val PREFIX = "ARL_SPEC_AUTH"
        const val DEFAULT_HEADER = "Authorization"
    }
}
