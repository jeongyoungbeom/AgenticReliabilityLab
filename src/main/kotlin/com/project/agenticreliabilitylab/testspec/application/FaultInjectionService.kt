package com.project.agenticreliabilitylab.testspec.application

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.testspec.domain.FaultInjectionOutcome
import com.project.agenticreliabilitylab.testspec.domain.FaultInjectionPlan
import org.springframework.stereotype.Component

/**
 * Injects and releases a fault through the Profile-declared hooks.
 *
 * This mirrors [EnvironmentResetService]'s perform-and-record shape, but a fault is scoped to one trial rather
 * than the whole environment: an injection returns a handle, and whoever holds that handle - the workload that
 * released it, or the Runner cleaning up after a trial that never got the chance to - is responsible for it.
 *
 * The Target owns TTL expiry itself (see TARGET_REQUIREMENTS.md); this only ever asks for release, it never
 * assumes a fault expired on its own.
 *
 * [scope] is specification-authored free text with no character restriction ([TestSpecValidator] only checks
 * `faultType` and the TTL), so it is JSON-escaped before it goes into the request body template - otherwise a
 * quote in a scope value would let a specification corrupt or extend the JSON sent to the Target, which is
 * exactly the kind of authority a specification must never gain on its own.
 */
@Component
class FaultInjectionService(
    private val caller: SpecHttpCaller,
    private val evaluator: ResponsePathEvaluator,
) {
    fun inject(
        plan: FaultInjectionPlan,
        target: RegisteredTarget,
        runId: String,
        faultType: String,
        scope: String?,
        ttlMs: Long,
    ): FaultInjectionOutcome {
        val bindings = buildMap {
            put("runId", runId.jsonEscaped())
            put("faultType", faultType.jsonEscaped())
            put("ttlMs", ttlMs.toString())
            put("scope", (scope ?: "").jsonEscaped())
        }
        val call = plan.injectHook.copy(bodyJson = INJECT_BODY_TEMPLATE)
        val response = caller.send(target, call, bindings, FIRST_REQUEST, runId)
        if (!response.delivered || response.statusCode !in SUCCESS_STATUS) {
            return FaultInjectionOutcome(null, false, response.failure ?: "HTTP ${response.statusCode}")
        }
        val faultIdResult = runCatching {
            evaluator.evaluate(FAULT_ID_EXPRESSION, evaluator.responseScope(response)).toString()
        }
        val faultId = faultIdResult.getOrNull()?.takeIf(String::isNotBlank)
        return if (faultId == null) {
            val reason = faultIdResult.exceptionOrNull()?.message ?: "the inject hook did not return a faultId"
            FaultInjectionOutcome(null, false, reason)
        } else {
            FaultInjectionOutcome(faultId, true)
        }
    }

    fun release(
        plan: FaultInjectionPlan,
        target: RegisteredTarget,
        runId: String,
        faultId: String,
    ): FaultInjectionOutcome {
        val bindings = mapOf("runId" to runId.jsonEscaped(), "faultId" to faultId.jsonEscaped())
        val call = plan.releaseHook.copy(bodyJson = RELEASE_BODY_TEMPLATE)
        val response = caller.send(target, call, bindings, FIRST_REQUEST, runId)
        return if (response.delivered && response.statusCode in SUCCESS_STATUS) {
            FaultInjectionOutcome(faultId, true)
        } else {
            FaultInjectionOutcome(faultId, false, response.failure ?: "HTTP ${response.statusCode}")
        }
    }

    private companion object {
        const val FIRST_REQUEST = 1
        const val FAULT_ID_EXPRESSION = "response.body.faultId"
        const val MIN_PRINTABLE = 0x20
        const val HEX_RADIX = 16
        const val UNICODE_ESCAPE_DIGITS = 4
        val SUCCESS_STATUS = 200..299

        // {{scope}} is always sent, as an empty string when the specification did not declare one, so the
        // template stays fixed instead of being assembled per call from specification-influenced fragments.
        const val INJECT_BODY_TEMPLATE =
            """{"runId":"{{runId}}","faultType":"{{faultType}}","ttlMs":{{ttlMs}},"scope":"{{scope}}"}"""
        const val RELEASE_BODY_TEMPLATE = """{"runId":"{{runId}}","faultId":"{{faultId}}"}"""

        /**
         * Escapes a value for use inside a JSON string literal in [INJECT_BODY_TEMPLATE] / [RELEASE_BODY_TEMPLATE].
         *
         * These templates are filled by [SpecReferenceResolver.resolve]'s plain text substitution, which does not
         * know it is filling JSON - so every value placed inside a `"..."` slot must already be JSON-safe before
         * substitution, the same way a SQL value must be escaped before it reaches a string-built query.
         */
        private fun String.jsonEscaped(): String = buildString {
            this@jsonEscaped.forEach { character ->
                when (character) {
                    '"' -> append('\\').append('"')
                    '\\' -> append('\\').append('\\')
                    '\n' -> append('\\').append('n')
                    '\r' -> append('\\').append('r')
                    '\t' -> append('\\').append('t')
                    else -> if (character.code < MIN_PRINTABLE) {
                        append("\\u")
                        append(character.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }
}
