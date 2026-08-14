package com.project.agenticreliabilitylab.target.domain

import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI

@Component
class TargetNetworkPolicy {
    /**
     * Resolve immediately before dispatch. Every answer must remain inside the
     * target's registered CIDR allowlist because the HTTP client may choose any
     * address returned by DNS.
     */
    fun resolveAllowed(uri: URI, target: RegisteredTarget): List<InetAddress> {
        val addresses = try {
            InetAddress.getAllByName(uri.host)
        } catch (exception: Exception) {
            throw TargetNetworkPolicyException("Target host '${uri.host}' could not be resolved: ${exception.javaClass.simpleName}")
        }
        if (addresses.isEmpty() || addresses.any { address -> target.allowedNetworkCidrs.none { it.contains(address) } }) {
            throw TargetNetworkPolicyException(
                "Target host '${uri.host}' resolved outside its registered network allowlist",
            )
        }
        return addresses.toList()
    }
}

class TargetNetworkPolicyException(message: String) : RuntimeException(message)
