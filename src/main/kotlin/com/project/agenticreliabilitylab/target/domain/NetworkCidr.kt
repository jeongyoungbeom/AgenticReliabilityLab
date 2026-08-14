package com.project.agenticreliabilitylab.target.domain

import java.net.InetAddress

/** An immutable CIDR allowlist entry used to verify every DNS answer before a target call. */
class NetworkCidr private constructor(
    private val networkAddress: ByteArray,
    private val prefixLength: Int,
) {
    fun contains(address: InetAddress): Boolean {
        val candidate = address.address
        if (candidate.size != networkAddress.size) return false
        val completeBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        if (!candidate.copyOfRange(0, completeBytes).contentEquals(networkAddress.copyOfRange(0, completeBytes))) {
            return false
        }
        if (remainingBits == 0) return true
        val mask = (0xff shl (8 - remainingBits)) and 0xff
        return (candidate[completeBytes].toInt() and mask) == (networkAddress[completeBytes].toInt() and mask)
    }

    override fun toString(): String = "${InetAddress.getByAddress(networkAddress).hostAddress}/$prefixLength"

    override fun equals(other: Any?): Boolean =
        other is NetworkCidr && prefixLength == other.prefixLength && networkAddress.contentEquals(other.networkAddress)

    override fun hashCode(): Int = 31 * networkAddress.contentHashCode() + prefixLength

    companion object {
        fun parse(value: String): NetworkCidr {
            val parts = value.trim().split('/', limit = 2)
            require(parts.size == 2 && IP_LITERAL_PATTERN.matches(parts[0])) {
                "Allowed CIDR '$value' must contain an IPv4 or IPv6 literal and prefix length"
            }
            val address = InetAddress.getByName(parts[0]).address
            val prefixLength = parts[1].toIntOrNull()
                ?: throw IllegalArgumentException("Allowed CIDR '$value' has an invalid prefix length")
            require(prefixLength in 0..address.size * 8) {
                "Allowed CIDR '$value' has a prefix length outside its address family"
            }
            return NetworkCidr(address, prefixLength)
        }

        private val IP_LITERAL_PATTERN = Regex("[0-9A-Fa-f:.]+")
    }
}
