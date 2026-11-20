package com.harbor.tv.core.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Redacts credentials, tokens, cookies, and sensitive query parameters from logs and diagnostics.
 */
object CredentialRedactor {
    private val userInfoRegex = Regex(
        """([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\s:]+):([^/@\s]+)@"""
    )
    private val sensitiveHeaderRegex = Regex(
        """(?im)^(\s*(?:Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-Api-Key|X-Auth-Token|X-Session-Token)\s*:\s*)(.+)$"""
    )
    private val inlineHeaderRegex = Regex(
        """(?i)\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-Api-Key|X-Auth-Token|X-Session-Token)\s*:\s*([^\r\n;,]+)""",
    )
    private val bearerBasicRegex = Regex(
        """(?i)\b(Bearer|Basic)\s+[A-Za-z0-9\-._~+/]+=*"""
    )
    private val querySecretRegex = Regex(
        """(?i)([?&](?:token|access_token|refresh_token|id_token|auth|api_key|apikey|secret|password|passwd|pwd|session|session_id|sessionid|sid|cookie|sig|signature|hdnts|hmac)=)([^&\s#]+)"""
    )
    private val keyValueSecretRegex = Regex(
        """(?i)(["']?(?:password|passwd|pwd|secret|token|access_token|refresh_token|id_token|api_key|apikey|authorization|cookie|session_cookie|private_key|client_secret)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,}\]]+)"""
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        result = userInfoRegex.replace(result) { m -> "${m.groupValues[1]}[REDACTED]:[REDACTED]@" }
        result = sensitiveHeaderRegex.replace(result) { m -> "${m.groupValues[1]}[REDACTED]" }
        result = inlineHeaderRegex.replace(result) { m -> "${m.groupValues[1]}: [REDACTED]" }
        result = bearerBasicRegex.replace(result) { m -> "${m.groupValues[1]} [REDACTED]" }
        result = querySecretRegex.replace(result) { m -> "${m.groupValues[1]}[REDACTED]" }
        result = keyValueSecretRegex.replace(result) { m -> "${m.groupValues[1]}\"[REDACTED]\"" }
        return result
    }
}

/**
 * Validates resolved destination hosts and IP addresses against SSRF / local-network exposure.
 */
object AddressSafetyValidator {
    fun isSafePublicAddress(
        host: String,
        inetAddresses: List<InetAddress>,
        allowLan: Boolean
    ): Boolean {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isEmpty() || inetAddresses.isEmpty()) {
            return false
        }
        if (!allowLan && isLocalHostname(normalizedHost)) {
            return false
        }
        return inetAddresses.all { address -> isAddressAllowed(address, allowLan) }
    }

    private fun isLocalHostname(host: String): Boolean {
        return host == "localhost" ||
            host == "localhost.localdomain" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local")
    }

    private fun isAddressAllowed(address: InetAddress, allowLan: Boolean): Boolean {
        // Multicast and unspecified (0.0.0.0 / ::) are never valid content destinations.
        if (address.isAnyLocalAddress || address.isMulticastAddress) {
            return false
        }

        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            return isIpv4Allowed(bytes, address, allowLan)
        }
        if (address is Inet6Address && bytes.size == 16) {
            return isIpv6Allowed(bytes, address, allowLan)
        }
        return false
    }

    private fun isIpv4Allowed(bytes: ByteArray, address: InetAddress, allowLan: Boolean): Boolean {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // 0.0.0.0/8 ("this" network) and 255.255.255.255 (broadcast) are always blocked.
        if (b0 == 0 || (b0 == 255 && b1 == 255 && (bytes[2].toInt() and 0xFF) == 255 && (bytes[3].toInt() and 0xFF) == 255)) {
            return false
        }
        if (allowLan) {
            return true
        }

        // Loopback 127.0.0.0/8
        if (address.isLoopbackAddress || b0 == 127) return false
        // Link-local 169.254.0.0/16
        if (address.isLinkLocalAddress || (b0 == 169 && b1 == 254)) return false
        // RFC1918 private: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
        if (address.isSiteLocalAddress) return false
        if (b0 == 10) return false
        if (b0 == 172 && b1 in 16..31) return false
        if (b0 == 192 && b1 == 168) return false

        return true
    }

    private fun isIpv6Allowed(bytes: ByteArray, address: InetAddress, allowLan: Boolean): Boolean {
        if (allowLan) {
            return true
        }
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return false
        }
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // IPv6 ULA fc00::/7
        if ((b0 and 0xFE) == 0xFC) return false
        // IPv6 link-local fe80::/10
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return false
        // IPv6 multicast ff00::/8
        if (b0 == 0xFF) return false

        // IPv4-mapped IPv6 ::ffff:0:0/96
        val isIpv4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() &&
            bytes[11] == 0xFF.toByte()
        if (isIpv4Mapped) {
            val v4Bytes = bytes.copyOfRange(12, 16)
            val v4Addr = InetAddress.getByAddress(v4Bytes) as? Inet4Address ?: return false
            return isIpv4Allowed(v4Bytes, v4Addr, allowLan = false)
        }

        return true
    }
}

/**
 * Deterministic salted-domain SHA-256 PIN hasher for local profile lock checks.
 */
object PinHasher {
    private const val DOMAIN_PREFIX = "HarborTV:PIN:v1:"

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = (DOMAIN_PREFIX + pin).toByteArray(StandardCharsets.UTF_8)
        val hashed = digest.digest(input)
        return hashed.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        val candidate = hashPin(pin).toByteArray(StandardCharsets.US_ASCII)
        val expected = storedHash.toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(candidate, expected)
    }
}
