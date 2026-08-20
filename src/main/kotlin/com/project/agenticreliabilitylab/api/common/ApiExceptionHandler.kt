package com.project.agenticreliabilitylab.api.common

import com.project.agenticreliabilitylab.common.ClientRequestException
import com.project.agenticreliabilitylab.common.AccessDeniedException
import com.project.agenticreliabilitylab.common.ResourceNotFoundException
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
        log.error("Unhandled ARL API exception; correlationId={}", MDC.get(CORRELATION_ID_KEY), exception)
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected server error occurred")
    }

    private fun response(status: HttpStatus, code: String, message: String): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(status).body(ApiErrorResponse(code, message, MDC.get(CORRELATION_ID_KEY)))

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
    }
}
