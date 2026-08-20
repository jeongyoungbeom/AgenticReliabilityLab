package com.project.agenticreliabilitylab.testspec.api

import com.project.agenticreliabilitylab.api.common.BoundedRequestBody
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val TEST_SPEC_PAYLOAD_FILTER_ORDER_OFFSET = 10

/** Bounds model-produced specification JSON before MVC materializes its document tree. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + TEST_SPEC_PAYLOAD_FILTER_ORDER_OFFSET)
class TestSpecificationPayloadLimitFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.POST.name() || request.requestURI != TEST_SPECIFICATION_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > TestSpecificationPayloadLimits.MAX_JSON_REQUEST_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE)
            return
        }
        filterChain.doFilter(
            BoundedRequestBody(request, TestSpecificationPayloadLimits.MAX_JSON_REQUEST_BYTES),
            response,
        )
    }

    private companion object {
        const val TEST_SPECIFICATION_PATH = "/api/test-specifications"
    }
}

internal object TestSpecificationPayloadLimits {
    const val MAX_JSON_REQUEST_BYTES = 262_144L
}
