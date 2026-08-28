package com.project.agenticreliabilitylab.diagnosis

import com.project.agenticreliabilitylab.api.common.ApiExceptionHandler
import com.project.agenticreliabilitylab.common.ClientRequestException
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FailureDiagnosisTests {
    @Test
    fun `maps Target connection failure to a Korean preflight action`() {
        val diagnosis = FailureDiagnosisFactory.fromCode("TARGET_UNREACHABLE")

        assertEquals(FailureStage.PREFLIGHT, diagnosis.stage)
        assertContains(diagnosis.summary, "연결")
        assertContains(diagnosis.nextAction, "health")
        assertEquals("code=TARGET_UNREACHABLE", diagnosis.technicalDetail)
    }

    @Test
    fun `maps authentication preflight and execution failures to actionable stages`() {
        val authentication = FailureDiagnosisFactory.fromCode("TARGET_CREDENTIAL_EXPIRED", 401)
        val preflight = FailureDiagnosisFactory.fromCode("TARGET_PREFLIGHT_FAILED", 502)
        val execution = FailureDiagnosisFactory.fromCode("TEST_SPEC_RUN_FAILED")

        assertEquals(FailureStage.CREDENTIALS, authentication.stage)
        assertContains(authentication.summary, "인증")
        assertContains(authentication.technicalDetail, "HTTP 401")
        assertEquals(FailureStage.PREFLIGHT, preflight.stage)
        assertContains(preflight.nextAction, "Profile")
        assertEquals(FailureStage.EXECUTION, execution.stage)
        assertContains(execution.nextAction, "새 세션")
    }

    @Test
    fun `redacts credentials headers and response body from diagnostic text`() {
        val raw = "Authorization: Bearer seller-secret X-ARL-Harness-Key: harness-secret " +
            "access_token=buyer-secret response body={\"token\":\"response-secret\"}"

        val redacted = SensitiveDiagnosticRedactor.redact(raw).orEmpty()

        listOf("seller-secret", "harness-secret", "buyer-secret", "response-secret", "Authorization", "body=")
            .forEach { secret -> assertFalse(redacted.contains(secret, ignoreCase = true)) }
        assertContains(redacted, "[REDACTED]")
    }

    @Test
    fun `separates ARL operator access from Target credential guidance`() {
        val arlAccess = FailureDiagnosisFactory.fromCode("ACCESS_DENIED", 403)
        val targetCredential = FailureDiagnosisFactory.fromCode("TARGET_CREDENTIAL_EXPIRED", 401)

        assertContains(arlAccess.summary, "ARL")
        assertContains(arlAccess.nextAction, "ARL 접근 토큰")
        // The two credentials are separate (D006): ARL access must not send the user to a Target preflight retry.
        assertFalse(arlAccess.nextAction.contains("preflight"))
        assertFalse(arlAccess.likelyCause == targetCredential.likelyCause)
        assertContains(targetCredential.nextAction, "preflight")
    }

    @Test
    fun `keeps safe validation text that only names a credential field`() {
        listOf(
            "Viewer authorization is required",
            "Profile editor authorization is required",
            "Executor authorization is required",
            "spec.steps[2].accessToken must not be blank",
        ).forEach { safeText -> assertEquals(safeText, SensitiveDiagnosticRedactor.redact(safeText)) }
    }

    @Test
    fun `redacts an unlabelled response body that carries no known key`() {
        val raw = "HTTP 500 from POST /orders: {\"message\":\"unexpected\",\"customerEmail\":\"a@b.c\"}"

        val redacted = SensitiveDiagnosticRedactor.redact(raw).orEmpty()

        assertFalse(redacted.contains("customerEmail"))
        assertFalse(redacted.contains("a@b.c"))
        // The safe prefix survives: only the body itself is dropped.
        assertEquals("HTTP 500 from POST /orders: [REDACTED]", redacted)
    }

    @Test
    fun `falls back to the diagnosis summary instead of throwing on a blank source message`() {
        val response = ApiExceptionHandler().conflict(ClientRequestException("TARGET_UNREACHABLE", "   ")).body!!
        val diagnosis = response.diagnosis!!

        assertEquals(diagnosis.summary, response.message)
        assertEquals(FailureStage.PREFLIGHT, diagnosis.stage)
    }

    @Test
    fun `api errors redact a Target exception message and attach a safe diagnosis`() {
        val response = ApiExceptionHandler().conflict(
            ClientRequestException(
                "TARGET_UNREACHABLE",
                "Authorization: Bearer leaked-token response body={\"password\":\"leaked-password\"}",
            ),
        ).body!!

        assertEquals("[REDACTED]", response.message)
        assertEquals(FailureStage.PREFLIGHT, response.diagnosis?.stage)
        assertFalse(response.message.contains("leaked", ignoreCase = true))
        assertFalse(response.diagnosis!!.technicalDetail.contains("leaked", ignoreCase = true))
    }
}
