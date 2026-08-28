package com.project.agenticreliabilitylab.diagnosis

/** The point in a safe test flow at which the user needs to act. */
enum class FailureStage {
    CREDENTIALS,
    PREFLIGHT,
    EXECUTION,
    CLEANUP,
    RECOVERY,
    CONFIGURATION,
    SYSTEM,
}

/**
 * A user-actionable diagnosis. Technical information is deliberately a short, redacted identifier rather than an
 * exception message, HTTP body, header, or credential value.
 */
data class FailureDiagnosis(
    val stage: FailureStage,
    val summary: String,
    val likelyCause: String,
    val nextAction: String,
    val technicalDetail: String,
)

/** Maps stable ARL failure codes to Korean operator guidance without retaining a Target response. */
object FailureDiagnosisFactory {
    fun fromCode(code: String, httpStatus: Int? = null): FailureDiagnosis {
        val template = templateFor(code)
        return FailureDiagnosis(
            stage = template.stage,
            summary = template.summary,
            likelyCause = template.likelyCause,
            nextAction = template.nextAction,
            technicalDetail = listOfNotNull(
                "code=${SensitiveDiagnosticRedactor.redact(code)}",
                httpStatus?.let { status -> "HTTP $status" },
            ).joinToString(" · "),
        )
    }

    private fun templateFor(code: String): DiagnosisTemplate =
        credentialTemplate(code)
            ?: preflightTemplate(code)
            ?: cleanupTemplate(code)
            ?: executionTemplate(code)
            ?: configurationTemplate(code)
            ?: systemTemplate()

    private fun credentialTemplate(code: String): DiagnosisTemplate? = when (code) {
        "TARGET_CREDENTIAL_MISSING" -> template(
            FailureStage.CREDENTIALS,
            "Target 자격증명이 없습니다.",
            "필요한 역할의 런타임 자격증명이 아직 저장되지 않았습니다.",
            "seller, buyer, harness 자격증명을 런타임에 적용한 뒤 preflight를 다시 실행하세요.",
        )
        "TARGET_CREDENTIAL_EXPIRED" -> template(
            FailureStage.CREDENTIALS,
            "Target 인증 또는 권한을 확인하지 못했습니다.",
            "자격증명이 만료되었거나 이 역할에 필요한 권한이 없습니다.",
            "해당 역할의 테스트 자격증명을 갱신한 뒤 preflight를 다시 실행하세요.",
        )
        // ARL's own operator role token, not a Target test credential: D006 keeps the two separate, so the
        // guidance has to send the user to the ARL access token rather than to a Target preflight retry.
        "ACCESS_DENIED" -> template(
            FailureStage.CREDENTIALS,
            "ARL 접근 권한을 확인하지 못했습니다.",
            "이 요청에 필요한 ARL 운영자 역할 토큰이 없거나 값이 맞지 않습니다. " +
                "Target의 seller/buyer/harness 테스트 자격증명과는 다른 값입니다.",
            "요청에 필요한 역할(viewer / profileEditor / executor)의 ARL 접근 토큰을 입력한 뒤 다시 시도하세요.",
        )
        else -> null
    }

    private fun preflightTemplate(code: String): DiagnosisTemplate? = when (code) {
        "TARGET_UNREACHABLE" -> template(
            FailureStage.PREFLIGHT,
            "Target에 연결하지 못했습니다.",
            "Target이 실행 중이 아니거나 Profile의 URL·네트워크 allowlist가 맞지 않습니다.",
            "Target health와 등록 URL을 확인한 뒤 preflight를 다시 실행하세요.",
        )
        "TARGET_PREFLIGHT_FAILED" -> template(
            FailureStage.PREFLIGHT,
            "Target preflight가 준비 상태를 확인하지 못했습니다.",
            "안전한 GET 경로가 예상하지 않은 응답을 반환했습니다.",
            "HTTP 상태와 Profile 경로를 확인한 뒤 자격증명 또는 Target 상태를 바로잡으세요.",
        )
        else -> null
    }

    private fun executionTemplate(code: String): DiagnosisTemplate? = when (code) {
        "TEST_SPEC_RUN_RECOVERY_REQUIRED", "TEST_SPECIFICATION_RECOVERY_REQUIRED" -> template(
            FailureStage.RECOVERY,
            "이전 실행의 정리 상태를 확인해야 합니다.",
            "상태 변경 실행이 중단됐거나 reset·fault 해제 검증이 완료되지 않았습니다.",
            "연결된 실행 기록에서 정리 상태를 확인한 뒤 복구가 검증된 경우에만 다음 실행을 시작하세요.",
        )
        "TEST_SPEC_RUN_FAILED", "PILOT_TEMPLATE_EXECUTION_FAILED" -> template(
            FailureStage.EXECUTION,
            "파일럿 실행을 완료하지 못했습니다.",
            "Target 호출 또는 실행 중 안전 검증이 실패했습니다.",
            "연결된 실행 기록과 정리 상태를 확인한 뒤 원인을 해결하고 새 세션으로 다시 실행하세요.",
        )
        else -> null
    }

    private fun cleanupTemplate(code: String): DiagnosisTemplate? = when (code) {
        "TEST_SPEC_RUN_CLEANUP_FAILED" -> template(
            FailureStage.CLEANUP,
            "실행 후 정리 상태를 확인하지 못했습니다.",
            "reset 또는 fault 해제 검증이 실패했거나 완료 전에 중단됐습니다.",
            "정리 체크와 Target 상태를 확인한 뒤, 복구가 검증될 때까지 다음 실행을 시작하지 마세요.",
        )
        else -> null
    }

    private fun configurationTemplate(code: String): DiagnosisTemplate? = when (code) {
        "PREFLIGHT_NOT_CONFIGURED", "TARGET_CREDENTIAL_PREFLIGHT_NOT_CONFIGURED" -> template(
            FailureStage.CONFIGURATION,
            "안전한 preflight 경로가 설정되지 않았습니다.",
            "활성 Profile에 이 역할이 사용할 GET 경로가 선언되지 않았습니다.",
            "Profile의 허용된 GET preflight 경로를 확인하고 다시 등록하세요.",
        )
        "PILOT_TEMPLATE_REJECTED" -> template(
            FailureStage.CONFIGURATION,
            "선택한 파일럿 템플릿을 실행 조건이 거부했습니다.",
            "활성 Profile, 실행 정책 또는 선택한 후보의 전제 조건이 맞지 않습니다.",
            "후보의 준비 사유와 활성 Profile을 확인한 뒤 다시 선택하세요.",
        )
        "IDEMPOTENCY_KEY_REUSED", "TEST_SPECIFICATION_RUN_IDEMPOTENCY_CONFLICT" -> template(
            FailureStage.CONFIGURATION,
            "같은 요청 키가 다른 실행과 충돌했습니다.",
            "이미 사용된 Idempotency-Key가 다른 선택에 연결돼 있습니다.",
            "새 Idempotency-Key로 같은 선택을 다시 요청하세요.",
        )
        else -> null
    }

    private fun systemTemplate() = template(
        FailureStage.SYSTEM,
        "요청을 안전하게 완료하지 못했습니다.",
        "ARL 내부 상태 또는 현재 실행 조건을 확인해야 합니다.",
        "기술 정보를 확인하고, 필요한 설정을 바로잡은 뒤 다시 시도하세요.",
    )

    private fun template(
        stage: FailureStage,
        summary: String,
        likelyCause: String,
        nextAction: String,
    ) = DiagnosisTemplate(stage, summary, likelyCause, nextAction)

    private data class DiagnosisTemplate(
        val stage: FailureStage,
        val summary: String,
        val likelyCause: String,
        val nextAction: String,
    )
}

/** Defensive redaction for any diagnostic input before it can cross an API, persistence, or logging boundary. */
object SensitiveDiagnosticRedactor {
    /**
     * Header names that never appear in ordinary operator prose. Their presence alone means the text was built
     * from a request or response envelope, so no surrounding context is preserved.
     */
    private val envelopeMarkers = listOf(
        Regex("""(?i)\bx-arl-harness-key\b"""),
        Regex("""(?i)\bset-cookie\b"""),
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+"""),
    )

    /**
     * A secret keyword **carrying a value**. The keyword on its own is deliberately not enough: words like
     * `authorization` and `token` also occur in safe validation text ("Viewer authorization is required"),
     * and blanking those left the user with `[REDACTED]` instead of an action.
     */
    private val credentialValueMarker = Regex(
        """(?i)\b(authorization|cookie|access[_-]?token|refresh[_-]?token|token|api[_-]?key|password|""" +
            """secret|harness[_-]?key|(?:response\s+)?body)\s*[:=]\s*\S""",
    )

    /**
     * A response body carried without a `body:` label, recognised by JSON structure rather than by a known key
     * name. Requirement 3 is about the body itself, so detection cannot depend on the keys it happens to use.
     */
    private val responseBodyFragments = listOf(
        Regex("""(?s)\{\s*"[^"]*"\s*:.*$"""),
        Regex("""(?s)\[\s*\{.*$"""),
    )

    fun redact(value: String?): String? = value
        ?.take(MAX_DIAGNOSTIC_LENGTH)
        ?.let { original ->
            // A credential value in the text means the whole context goes: a partially redacted envelope is too
            // easy to bypass with an unfamiliar key name. A bare JSON fragment loses only the fragment.
            if (carriesCredential(original)) "[REDACTED]"
            else responseBodyFragments.fold(original) { redacted, pattern -> pattern.replace(redacted, "[REDACTED]") }
        }

    private fun carriesCredential(value: String): Boolean =
        credentialValueMarker.containsMatchIn(value) || envelopeMarkers.any { it.containsMatchIn(value) }

    private const val MAX_DIAGNOSTIC_LENGTH = 1_000
}
