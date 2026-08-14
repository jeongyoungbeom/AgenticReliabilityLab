package com.project.agenticreliabilitylab.access

import com.project.agenticreliabilitylab.common.AccessDeniedException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val API_AUTHORIZATION_FILTER_ORDER_OFFSET = 20

/**
 * Makes every API route default-deny in SECURED mode. Controllers retain their
 * narrower checks where they need the authenticated actor for an audit event.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + API_AUTHORIZATION_FILTER_ORDER_OFFSET)
class ApiAuthorizationFilter(
    private val operatorAccessService: OperatorAccessService,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(API_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            requireAccess(request)
            filterChain.doFilter(request, response)
        } catch (exception: AccessDeniedException) {
            writeForbidden(response, exception.message)
        }
    }

    private fun requireAccess(request: HttpServletRequest) {
        if (request.method == HttpMethod.OPTIONS.name()) return

        val authorization = request.getHeader(AUTHORIZATION_HEADER)
        when {
            request.requestURI.isProfileManagementPath() && request.method.isSafeReadMethod() ->
                operatorAccessService.requireViewer(authorization)

            request.requestURI.isProfileManagementPath() ->
                operatorAccessService.requireProfileEditor(authorization)

            request.requestURI.isTargetHealthPath() ->
                operatorAccessService.requireExecutor(authorization)

            request.method.isSafeReadMethod() ->
                operatorAccessService.requireViewer(authorization)

            else -> operatorAccessService.requireExecutor(authorization)
        }
    }

    private fun writeForbidden(response: HttpServletResponse, message: String) {
        val correlationId = MDC.get(CORRELATION_ID_KEY)
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            "{\"code\":\"ACCESS_DENIED\",\"message\":\"${message.toJsonString()}\"," +
                "\"correlationId\":${correlationId.toJsonValue()}}",
        )
    }

    private fun String.isProfileManagementPath(): Boolean =
        startsWith(TARGET_PROFILES_PATH) || startsWith(TARGET_PROFILE_DRAFTS_PATH)

    private fun String.isTargetHealthPath(): Boolean =
        matches(TARGET_HEALTH_PATH)

    private fun String.isSafeReadMethod(): Boolean =
        this == HttpMethod.GET.name() || this == HttpMethod.HEAD.name()

    private fun String.toJsonString(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun String?.toJsonValue(): String =
        if (this == null) "null" else "\"${toJsonString()}\""

    private companion object {
        const val API_PATH = "/api/"
        const val TARGET_PROFILES_PATH = "/api/target-profiles"
        const val TARGET_PROFILE_DRAFTS_PATH = "/api/target-profile-drafts"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val CORRELATION_ID_KEY = "correlationId"
        val TARGET_HEALTH_PATH = Regex("/api/targets/[^/]+/health")
    }
}
