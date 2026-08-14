package com.project.agenticreliabilitylab.targetprofile.api

import com.project.agenticreliabilitylab.api.common.BoundedRequestBody
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val PAYLOAD_LIMIT_FILTER_ORDER_OFFSET = 10

/** Bounds untrusted Profile request bodies before MVC can materialize a YAML string in memory. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + PAYLOAD_LIMIT_FILTER_ORDER_OFFSET)
class TargetProfilePayloadLimitFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.POST.name() || !request.requestURI.startsWith(TARGET_PROFILE_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > TargetProfilePayloadLimits.MAX_JSON_REQUEST_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE)
            return
        }
        filterChain.doFilter(BoundedRequestBody(request, TargetProfilePayloadLimits.MAX_JSON_REQUEST_BYTES), response)
    }

    private companion object {
        const val TARGET_PROFILE_PATH = "/api/target-profiles"
    }
}

internal object TargetProfilePayloadLimits {
    const val MAX_JSON_REQUEST_BYTES = 262_144L
}
