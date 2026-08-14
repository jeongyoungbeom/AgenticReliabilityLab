package com.project.agenticreliabilitylab.observability

import com.project.agenticreliabilitylab.common.IdentifierGenerator
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Adds a safe request identifier to logs and API responses without trusting arbitrary values. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter(
    private val identifierGenerator: IdentifierGenerator,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(HEADER_NAME)
            ?.takeIf { VALUE_PATTERN.matches(it) }
            ?: identifierGenerator.next().toString()
        response.setHeader(HEADER_NAME, correlationId)
        MDC.put(MDC_KEY, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    private companion object {
        const val HEADER_NAME = "X-Correlation-Id"
        const val MDC_KEY = "correlationId"
        val VALUE_PATTERN = Regex("[A-Za-z0-9._:-]{1,100}")
    }
}
