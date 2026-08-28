package com.project.agenticreliabilitylab.api.common

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.common.AccessDeniedException
import com.project.agenticreliabilitylab.common.ResourceNotFoundException
import com.project.agenticreliabilitylab.diagnosis.FailureDiagnosisFactory
import com.project.agenticreliabilitylab.diagnosis.SensitiveDiagnosticRedactor
import com.project.agenticreliabilitylab.targetprofile.domain.TargetProfileDocumentException
import com.project.agenticreliabilitylab.testspec.application.SpecParseException
import com.project.agenticreliabilitylab.testspec.application.SpecValidationException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** One stable HTTP error contract for every API controller. */
@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ResourceNotFoundException::class)
    fun notFound(exception: RuntimeException): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.message ?: "Resource was not found")

    @ExceptionHandler(ClientRequestException::class)
    fun conflict(exception: ClientRequestException): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.CONFLICT, exception.code, exception.message)

    @ExceptionHandler(TargetProfileDocumentException::class)
    fun invalidTargetProfile(exception: TargetProfileDocumentException): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.BAD_REQUEST, exception.code, exception.message)

    @ExceptionHandler(SpecParseException::class, SpecValidationException::class)
    fun invalidTestSpecification(exception: RuntimeException): ResponseEntity<ApiErrorResponse> =
        response(
            HttpStatus.BAD_REQUEST,
            "INVALID_TEST_SPECIFICATION",
            exception.message ?: "Test specification is invalid",
        )

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(exception: AccessDeniedException): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", exception.message)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableRequest(exception: HttpMessageNotReadableException): ResponseEntity<ApiErrorResponse> =
        if (exception.hasCause(RequestBodyTooLargeException::class.java)) {
            response(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Request body is too large")
        } else {
            response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.message ?: "Request is invalid")
        }

    @ExceptionHandler(
        IllegalArgumentException::class,
        BindException::class,
        MethodArgumentNotValidException::class,
    )
    fun invalidRequest(exception: Exception): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.message ?: "Request is invalid")

    @ExceptionHandler(Exception::class)
    fun unexpected(exception: Exception): ResponseEntity<ApiErrorResponse> {
        // The exception object is not handed to the logger: a Target library can embed request headers or a
        // response body in its message. The cause chain and the top frames carry no message, so they stay —
        // without them an ARL defect leaves nothing to locate it by.
        log.error(
            "Unhandled ARL API exception; correlationId={}, type={}, at={}",
            MDC.get(CORRELATION_ID_KEY),
            exception.javaClass.simpleName,
            messageFreeOrigin(exception),
        )
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected server error occurred")
    }

    /** Exception classes and stack frames only. Every message is left out rather than filtered. */
    private fun messageFreeOrigin(exception: Throwable): String {
        val causes = generateSequence(exception, Throwable::cause)
            .take(MAX_LOGGED_CAUSES)
            .joinToString(" <- ") { it.javaClass.name }
        val frames = exception.stackTrace
            .take(MAX_LOGGED_FRAMES)
            .joinToString(" | ") { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
        return "$causes [$frames]"
    }

    private fun response(status: HttpStatus, code: String, message: String): ResponseEntity<ApiErrorResponse> {
        val diagnosis = FailureDiagnosisFactory.fromCode(code, status.value())
        // Preserve existing safe validation feedback for API clients, but drop the whole source if it can carry Target
        // credentials, headers, or a response body. The UI renders the structured diagnosis in either case.
        // A blank source falls back to the diagnosis instead of throwing: an exception raised inside this advice
        // would escape to the container and replace the stable error contract with an unstructured page.
        val safeMessage = SensitiveDiagnosticRedactor.redact(message)?.takeIf { it.isNotBlank() } ?: diagnosis.summary
        return ResponseEntity.status(status).body(
            ApiErrorResponse(code, safeMessage, MDC.get(CORRELATION_ID_KEY), diagnosis),
        )
    }

    private fun Throwable.hasCause(type: Class<out Throwable>): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (type.isInstance(cause)) return true
            cause = cause.cause
        }
        return false
    }

    private companion object {
        const val CORRELATION_ID_KEY = "correlationId"
        const val MAX_LOGGED_CAUSES = 5
        const val MAX_LOGGED_FRAMES = 12
    }
}
