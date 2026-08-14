package com.project.agenticreliabilitylab.targetprofiledraft.api

import com.project.agenticreliabilitylab.api.common.BoundedRequestBody
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val DRAFT_PAYLOAD_LIMIT_FILTER_ORDER_OFFSET = 15

/** Bounds source-document JSON before MVC deserializes an OpenAPI or README Draft request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + DRAFT_PAYLOAD_LIMIT_FILTER_ORDER_OFFSET)
class TargetProfileDraftPayloadLimitFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.POST.name() || !request.requestURI.startsWith(TARGET_PROFILE_DRAFTS_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > TargetProfileDraftPayloadLimits.MAX_JSON_REQUEST_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE)
            return
        }
        val boundedRequest = BoundedRequestBody(request, TargetProfileDraftPayloadLimits.MAX_JSON_REQUEST_BYTES)
        filterChain.doFilter(boundedRequest, response)
    }

    private companion object { const val TARGET_PROFILE_DRAFTS_PATH = "/api/target-profile-drafts" }
}

internal object TargetProfileDraftPayloadLimits {
    const val MAX_JSON_REQUEST_BYTES = 2_098_176L
}
