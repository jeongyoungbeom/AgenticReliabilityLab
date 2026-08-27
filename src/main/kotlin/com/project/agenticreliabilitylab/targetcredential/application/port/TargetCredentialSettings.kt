package com.project.agenticreliabilitylab.targetcredential.application.port

import java.time.Duration

/**
 * Retention and transport settings for Target credential sessions.
 *
 * [idleTimeout] is deliberately an *idle* timeout, not an absolute one. The previous absolute 30-minute expiry killed
 * sessions in the middle of long runs, which is why it was removed; measuring from last use instead means active work
 * never expires while genuinely abandoned sessions are still reclaimed. Reclaiming matters because the cookie is a
 * browser-session cookie: closing the browser ends the session for the user but leaves the server side behind.
 */
interface TargetCredentialSettings {
    /** Off by default: ARL is normally served over plain HTTP locally, and a LAN host would reject a Secure cookie. */
    val cookieSecure: Boolean
    val idleTimeout: Duration
    val maxSessions: Int
}
