package com.project.agenticreliabilitylab.targetintelligence.api

import com.project.agenticreliabilitylab.api.common.BoundedRequestBody
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val KNOWLEDGE_PAYLOAD_LIMIT_FILTER_ORDER_OFFSET = 16

/** Bounds Knowledge Snapshot intake JSON before MVC materializes an oversized OpenAPI or README body. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + KNOWLEDGE_PAYLOAD_LIMIT_FILTER_ORDER_OFFSET)
class TargetKnowledgeSnapshotPayloadLimitFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.POST.name() || !request.requestURI.startsWith(KNOWLEDGE_SNAPSHOTS_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > TargetKnowledgeSnapshotPayloadLimits.MAX_JSON_REQUEST_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE)
            return
        }
        val boundedRequest = BoundedRequestBody(request, TargetKnowledgeSnapshotPayloadLimits.MAX_JSON_REQUEST_BYTES)
        filterChain.doFilter(boundedRequest, response)
    }

    private companion object { const val KNOWLEDGE_SNAPSHOTS_PATH = "/api/target-knowledge-snapshots" }
}

internal object TargetKnowledgeSnapshotPayloadLimits {
    /** Twice the combined OpenAPI and README document bounds, leaving room for JSON escaping and Brief fields. */
    const val MAX_JSON_REQUEST_BYTES = 2_686_976L
}
