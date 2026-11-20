package com.harbor.tv.engine.policy

import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.security.AddressSafetyValidator
import com.harbor.tv.engine.definition.RequestDef
import com.harbor.tv.engine.definition.SourceDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces Harbor network security policies (Master Specification Section 14):
 * - HTTPS-only and max URL length checks
 * - Exact per-purpose host allowlist matching (no wildcards)
 * - Literal IP and resolved DNS address safety via [AddressSafetyValidator]
 * - GET-only HTTP method enforcement
 * - Forbidden header and control-character rejection
 * - Redirect hop-count, destination validation, and cross-origin credential stripping
 * - Per-source concurrency and request interval rate limiting
 * - Safe, expression-free URL and query parameter interpolation
 */
class RequestPolicyEngine(
    private val dnsResolver: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val MAX_URL_LENGTH = 2048
        const val MAX_APP_CONCURRENT_REQUESTS = 10

        val FORBIDDEN_HEADERS = setOf(
            "host",
            "content-length",
            "connection",
            "proxy-authorization",
            "cookie",
            "authorization"
        )

        val SENSITIVE_REDIRECT_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token",
            "x-session-token"
        )

        private val INTERPOLATION_REGEX = Regex("""\$\{([^}]*)\}""")
        private val VALID_VAR_NAME_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$""")
    }

    private data class SourceRateState(
        val limit: Int,
        val semaphore: Semaphore,
        val timeMutex: Mutex = Mutex(),
        var lastRequestAtMs: Long = 0L
    )

    private val rateStates = ConcurrentHashMap<String, SourceRateState>()

    /**
     * Validates that the HTTP method is strictly `GET`.
     */
    fun validateMethod(method: String) {
        if (!method.trim().equals("GET", ignoreCase = true)) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "HTTP method '$method' is not permitted; MVP supports GET only"
            )
        }
    }

    /**
     * Validates and normalizes outbound request headers, rejecting forbidden headers and
     * any control characters in header names or values.
     */
    fun validateHeaders(headers: Map<String, String>): Map<String, String> {
        val sanitized = LinkedHashMap<String, String>(headers.size)
        for ((rawName, value) in headers) {
            val name = rawName.trim()
            if (name.isEmpty() || hasControlChars(name) || name.any { it.isWhitespace() }) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Invalid HTTP header name '$rawName'"
                )
            }
            if (name.lowercase(Locale.ROOT) in FORBIDDEN_HEADERS) {
                throw HarborException(
                    HarborErrorCode.POLICY_BLOCKED,
                    "Header '$name' is forbidden by Harbor request policy"
                )
            }
            if (hasControlChars(value)) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Header '$name' contains forbidden control characters"
                )
            }
            sanitized[name] = value
        }
        return sanitized
    }

    /**
     * Merges source-level headers with request-level and extra headers, validating all entries.
     */
    fun mergeAndValidateHeaders(
        definition: SourceDefinition,
        requestHeaders: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(definition.network.headers)
        merged.putAll(requestHeaders)
        merged.putAll(extraHeaders)
        return validateHeaders(merged)
    }

    /**
     * Validates [url] syntax, length, scheme, userInfo absence, exact host allowlist membership
     * for [purpose], and resolved IP address safety. Returns the validated [InetAddress] list
     * when [resolveDns] is true.
     */
    fun validateUrl(
        url: String,
        definition: SourceDefinition,
        purpose: HostPurpose,
        userAllowLan: Boolean = false,
        resolveDns: Boolean = true
    ): List<InetAddress> {
        val uri = parseAndValidateUrlStructure(url, definition.network.httpsOnly)
        val rawHost = uri.host
        val cleanHost = rawHost.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)

        val allowedHosts = allowedHostsForPurpose(definition, purpose)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

        if (!isHostAllowed(cleanHost, allowedHosts)) {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "Host '$cleanHost' is not permitted for purpose $purpose",
                sourceId = definition.source.id
            )
        }

        val allowLan = definition.network.allowLan && userAllowLan
        if (!resolveDns) {
            return emptyList()
        }

        val addresses = try {
            dnsResolver(cleanHost)
        } catch (e: Exception) {
            throw HarborException(
                HarborErrorCode.NETWORK_UNAVAILABLE,
                "Failed to resolve host '$cleanHost': ${e.message ?: e::class.java.simpleName}",
                sourceId = definition.source.id,
                isRetryable = true,
                cause = e
            )
        }

        if (!AddressSafetyValidator.isSafePublicAddress(cleanHost, addresses, allowLan)) {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "Resolved destination address for '$cleanHost' failed address safety checks (allowLan=$allowLan)",
                sourceId = definition.source.id
            )
        }
        return addresses
    }

    /**
     * Validates a redirect hop against [definition.network.redirects.maxHops], validates the
     * redirect target URL + host + DNS safety, and strips sensitive headers on cross-origin
     * redirects. Returns the sanitized headers for the next hop.
     */
    fun validateRedirect(
        fromUrl: String,
        toUrl: String,
        hopCount: Int,
        currentHeaders: Map<String, String>,
        definition: SourceDefinition,
        purpose: HostPurpose,
        userAllowLan: Boolean = false,
        resolveDns: Boolean = true
    ): Map<String, String> {
        val maxHops = definition.network.redirects.maxHops
        if (hopCount > maxHops) {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "Redirect hop count ($hopCount) exceeds maximum allowed ($maxHops)",
                sourceId = definition.source.id
            )
        }

        validateUrl(
            url = toUrl,
            definition = definition,
            purpose = purpose,
            userAllowLan = userAllowLan,
            resolveDns = resolveDns
        )

        return if (isSameOrigin(fromUrl, toUrl)) {
            currentHeaders
        } else {
            currentHeaders.filterKeys { key ->
                key.trim().lowercase(Locale.ROOT) !in SENSITIVE_REDIRECT_HEADERS
            }
        }
    }

    /**
     * Returns true when [urlA] and [urlB] share the same scheme, lowercase host, and effective port.
     */
    fun isSameOrigin(urlA: String, urlB: String): Boolean {
        val uriA = runCatching { URI(urlA) }.getOrNull() ?: return false
        val uriB = runCatching { URI(urlB) }.getOrNull() ?: return false
        val schemeA = uriA.scheme?.lowercase(Locale.ROOT) ?: return false
        val schemeB = uriB.scheme?.lowercase(Locale.ROOT) ?: return false
        val hostA = uriA.host?.lowercase(Locale.ROOT) ?: return false
        val hostB = uriB.host?.lowercase(Locale.ROOT) ?: return false
        return schemeA == schemeB && hostA == hostB && effectivePort(uriA) == effectivePort(uriB)
    }

    /**
     * Safely interpolates [requestDef.url] and [requestDef.query] against [allowedVariables],
     * rejecting undeclared or expression-like `${...}` placeholders and URL-encoding query
     * parameter names and values.
     */
    fun buildInterpolatedUrl(
        requestDef: RequestDef,
        definition: SourceDefinition,
        variables: Map<String, String>,
        allowedVariables: Set<String>
    ): String {
        validateMethod(requestDef.method)

        val effectiveVars = LinkedHashMap<String, String>(variables.size + 1)
        effectiveVars["source.baseUrl"] = definition.source.baseUrl
        effectiveVars.putAll(variables)

        for ((k, v) in effectiveVars) {
            if (hasControlChars(v)) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Interpolation variable '$k' contains forbidden control characters",
                    sourceId = definition.source.id
                )
            }
        }

        val expandedBase = interpolateString(
            template = requestDef.url,
            variables = effectiveVars,
            allowedVariables = allowedVariables,
            sourceId = definition.source.id,
            trimTrailingSlashOnBaseUrl = true
        )

        parseAndValidateUrlStructure(expandedBase, definition.network.httpsOnly)

        if (requestDef.query.isEmpty()) {
            return expandedBase
        }

        val encodedParams = requestDef.query.entries.joinToString("&") { (rawKey, rawValTemplate) ->
            if (hasControlChars(rawKey) || rawKey.isBlank()) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Invalid query parameter name '$rawKey'",
                    sourceId = definition.source.id
                )
            }
            val interpolatedVal = interpolateString(
                template = rawValTemplate,
                variables = effectiveVars,
                allowedVariables = allowedVariables,
                sourceId = definition.source.id,
                trimTrailingSlashOnBaseUrl = false
            )
            val encodedKey = encodeQueryComponent(rawKey)
            val encodedVal = encodeQueryComponent(interpolatedVal)
            "$encodedKey=$encodedVal"
        }

        val fragmentIdx = expandedBase.indexOf('#')
        val withoutFragment = if (fragmentIdx >= 0) expandedBase.substring(0, fragmentIdx) else expandedBase
        val separator = when {
            !withoutFragment.contains('?') -> "?"
            withoutFragment.endsWith("?") || withoutFragment.endsWith("&") -> ""
            else -> "&"
        }
        val finalUrl = "$withoutFragment$separator$encodedParams"
        parseAndValidateUrlStructure(finalUrl, definition.network.httpsOnly)
        return finalUrl
    }

    /**
     * Executes [block] under the source's concurrency limit (`maxConcurrentRequests`) and minimum
     * inter-request interval (`minRequestIntervalMs`).
     */
    suspend fun <T> withRateLimit(
        definition: SourceDefinition,
        block: suspend () -> T
    ): T {
        val limits = definition.network.limits
        val maxConcurrent = limits.maxConcurrentRequests.coerceIn(1, MAX_APP_CONCURRENT_REQUESTS)
        val minIntervalMs = limits.minRequestIntervalMs.coerceAtLeast(0L)
        val sourceId = definition.source.id

        val state = rateStates.compute(sourceId) { _, existing ->
            if (existing != null && existing.limit == maxConcurrent) {
                existing
            } else {
                SourceRateState(
                    limit = maxConcurrent,
                    semaphore = Semaphore(permits = maxConcurrent)
                )
            }
        }!!

        return state.semaphore.withPermit {
            if (minIntervalMs > 0L) {
                state.timeMutex.withLock {
                    val now = clockMs()
                    val elapsed = now - state.lastRequestAtMs
                    if (state.lastRequestAtMs > 0L && elapsed < minIntervalMs) {
                        delay(minIntervalMs - elapsed)
                    }
                    state.lastRequestAtMs = clockMs()
                }
            }
            block()
        }
    }

    private fun parseAndValidateUrlStructure(url: String, httpsOnly: Boolean): URI {
        if (url.isBlank()) {
            throw HarborException(HarborErrorCode.DEFINITION_INVALID, "URL must not be blank")
        }
        if (url.length > MAX_URL_LENGTH) {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "URL length (${url.length}) exceeds maximum allowed ($MAX_URL_LENGTH)"
            )
        }
        if (hasControlChars(url)) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "URL contains forbidden control characters"
            )
        }
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Malformed URL '$url'",
                cause = e
            )
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "Unsupported URL scheme '${uri.scheme}'; only http/https are allowed"
            )
        }
        if (httpsOnly && scheme != "https") {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "HTTPS is required by network.httpsOnly"
            )
        }
        if (!uri.rawUserInfo.isNullOrEmpty()) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Embedded credentials in URL userInfo are forbidden"
            )
        }
        if (uri.host.isNullOrBlank()) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "URL '$url' is missing a host"
            )
        }
        return uri
    }

    private fun isHostAllowed(host: String, allowedEntries: List<String>): Boolean {
        for (entry in allowedEntries) {
            if (entry.startsWith("*.")) {
                val domain = entry.removePrefix("*.")
                if (domain.isNotEmpty() && !domain.contains('*') && domain.contains('.') &&
                    (host == domain || host.endsWith(".$domain"))
                ) {
                    return true
                }
            } else if (!entry.contains('*') && host == entry) {
                return true
            }
        }
        return false
    }

    private fun allowedHostsForPurpose(
        definition: SourceDefinition,
        purpose: HostPurpose
    ): List<String> {
        val hosts = definition.network.allowedHosts
        return when (purpose) {
            HostPurpose.CATALOG -> hosts.catalog
            HostPurpose.IMAGES -> hosts.images
            HostPurpose.MEDIA -> hosts.media
            HostPurpose.AUTHENTICATION -> hosts.authentication
        }
    }

    private fun interpolateString(
        template: String,
        variables: Map<String, String>,
        allowedVariables: Set<String>,
        sourceId: String,
        trimTrailingSlashOnBaseUrl: Boolean
    ): String {
        if (hasControlChars(template)) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "URL template contains forbidden control characters",
                sourceId = sourceId
            )
        }

        // Validate balanced, non-nested "${...}" markers.
        var cursor = 0
        while (true) {
            val open = template.indexOf("\${", cursor)
            if (open == -1) break
            val close = template.indexOf('}', open + 2)
            if (close == -1) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Unclosed interpolation placeholder in '$template'",
                    sourceId = sourceId
                )
            }
            val nested = template.indexOf("\${", open + 2)
            if (nested != -1 && nested < close) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Nested interpolation placeholders are forbidden in '$template'",
                    sourceId = sourceId
                )
            }
            cursor = close + 1
        }

        val sb = StringBuilder(template.length)
        var lastIndex = 0
        for (match in INTERPOLATION_REGEX.findAll(template)) {
            sb.append(template, lastIndex, match.range.first)
            val varName = match.groupValues[1]
            if (!VALID_VAR_NAME_REGEX.matches(varName)) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Invalid or expression-like interpolation '\${$varName}'",
                    sourceId = sourceId
                )
            }
            if (varName !in allowedVariables) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Undeclared or disallowed interpolation variable '\${$varName}'",
                    sourceId = sourceId
                )
            }
            val rawValue = variables[varName]
                ?: throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Missing value for interpolation variable '\${$varName}'",
                    sourceId = sourceId
                )
            val value = if (
                trimTrailingSlashOnBaseUrl &&
                varName == "source.baseUrl" &&
                match.range.last + 1 < template.length &&
                template[match.range.last + 1] == '/'
            ) {
                rawValue.trimEnd('/')
            } else {
                rawValue
            }
            sb.append(value)
            lastIndex = match.range.last + 1
        }
        sb.append(template, lastIndex, template.length)
        return sb.toString()
    }

    private fun encodeQueryComponent(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private fun effectivePort(uri: URI): Int {
        if (uri.port != -1) return uri.port
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private fun hasControlChars(text: String): Boolean {
        return text.any { it.code < 0x20 || it.code == 0x7F }
    }
}
