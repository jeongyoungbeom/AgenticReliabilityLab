package com.project.agenticreliabilitylab.common

/** A requested application resource does not exist. */
open class ResourceNotFoundException(
    resourceType: String,
    resourceId: Any,
) : RuntimeException("$resourceType '$resourceId' was not found")

/** A syntactically valid request cannot be accepted in the current application state. */
open class ClientRequestException(
    override val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause), ClientRequestFailure

/** The caller is known but does not hold the server-side role required for a state-changing action. */
open class AccessDeniedException(
    override val message: String,
) : RuntimeException(message)
