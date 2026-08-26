package com.project.agenticreliabilitylab.testspec.application.port

/**
 * Supplies the request headers an auth profile stands for.
 *
 * The specification names a profile; it never carries the credential. The value is resolved in the Runner
 * environment and returned only as request headers, so no token ever reaches the specification, the database,
 * a prompt or the evidence a report shows.
 */
interface SpecAuthProvider {
    fun headersFor(targetSystemId: String, authProfile: String): Map<String, String>

    /**
     * A UI credential session is an opaque routing key, never a credential. Existing execution paths use null and
     * therefore resolve only deployment-time secret references.
     */
    fun headersFor(
        targetSystemId: String,
        authProfile: String,
        credentialSessionId: String?,
    ): Map<String, String> = headersFor(targetSystemId, authProfile)
}

/** The Runner has no credential for a profile the Profile declared. Nothing is sent. */
class SpecAuthUnavailableException(message: String) : RuntimeException(message)
