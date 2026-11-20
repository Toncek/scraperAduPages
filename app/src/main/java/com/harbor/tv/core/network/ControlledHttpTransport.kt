package com.harbor.tv.core.network

import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.security.AddressSafetyValidator
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.policy.RequestPolicyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class HttpResponseData(
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String
)

interface HttpTransport {
    suspend fun executeGet(
        url: String,
        definition: SourceDefinition,
        purpose: HostPurpose,
        extraHeaders: Map<String, String> = emptyMap(),
        userAllowLan: Boolean = false
    ): HttpResponseData
}

/**
 * Policy-controlled OkHttp transport enforcing manual redirect verification, connection-time
 * DNS safety checks, rate limits, timeouts, and strict response byte budgets.
 */
class ControlledHttpTransport(
    private val policyEngine: RequestPolicyEngine,
    private val baseClient: OkHttpClient = OkHttpClient(),
    cacheDir: File? = null,
    diskCacheSizeBytes: Long = DEFAULT_DISK_CACHE_BYTES
) : HttpTransport {

    companion object {
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private const val READ_BUFFER_SIZE = 8192
        private const val MAX_COOKIE_HOSTS = 100
        const val DEFAULT_DISK_CACHE_BYTES = 32L * 1024L * 1024L
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val leanConnectionPool = ConnectionPool(3, 30L, TimeUnit.SECONDS)
    private val diskCache: Cache? = cacheDir?.let { dir ->
        if (!dir.exists()) {
            dir.mkdirs()
        }
        Cache(dir, diskCacheSizeBytes.coerceAtLeast(1024L))
    }

    /**
     * Strictly ephemeral in-memory cookie jar keyed by host (bounded to [MAX_COOKIE_HOSTS]);
     * never persisted to disk.
     */
    private val ephemeralCookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val cookieHostLastAccess = ConcurrentHashMap<String, Long>()

    private val ephemeralCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val hostKey = url.host.lowercase(Locale.ROOT)
            val now = System.currentTimeMillis()
            if (!ephemeralCookieStore.containsKey(hostKey) && ephemeralCookieStore.size >= MAX_COOKIE_HOSTS) {
                evictOldestCookieHost()
            }
            ephemeralCookieStore.compute(hostKey) { _, existing ->
                val merged = LinkedHashMap<String, Cookie>()
                existing.orEmpty()
                    .filter { it.expiresAt >= now }
                    .forEach { c -> merged["${c.name}\u0000${c.domain}\u0000${c.path}"] = c }
                for (cookie in cookies) {
                    val key = "${cookie.name}\u0000${cookie.domain}\u0000${cookie.path}"
                    if (cookie.expiresAt < now || cookie.value.isEmpty()) {
                        merged.remove(key)
                    } else {
                        merged[key] = cookie
                    }
                }
                val result = merged.values.toList()
                if (result.isEmpty()) null else result
            }
            if (ephemeralCookieStore.containsKey(hostKey)) {
                cookieHostLastAccess[hostKey] = now
            } else {
                cookieHostLastAccess.remove(hostKey)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (ephemeralCookieStore.isEmpty()) return emptyList()
            val now = System.currentTimeMillis()
            val matched = mutableListOf<Cookie>()
            for ((hostKey, cookies) in ephemeralCookieStore) {
                for (cookie in cookies) {
                    if (cookie.expiresAt >= now && cookie.matches(url)) {
                        matched.add(cookie)
                        cookieHostLastAccess[hostKey] = now
                    }
                }
            }
            return matched
        }
    }

    private val sharedBaseClient: OkHttpClient = baseClient.newBuilder()
        .connectionPool(leanConnectionPool)
        .apply {
            if (diskCache != null) {
                cache(diskCache)
            }
        }
        .build()

    /**
     * Clears ephemeral in-memory session cookies (and idle connections), and optionally evicts
     * the disk-backed HTTP cache when exiting or resetting a Private Session.
     */
    fun clearEphemeralState(evictHttpCache: Boolean = false) {
        ephemeralCookieStore.clear()
        cookieHostLastAccess.clear()
        leanConnectionPool.evictAll()
        if (evictHttpCache) {
            runCatching { diskCache?.evictAll() }
        }
    }

    private fun evictOldestCookieHost() {
        val oldestHost = cookieHostLastAccess.entries.minByOrNull { it.value }?.key
            ?: ephemeralCookieStore.keys.firstOrNull()
            ?: return
        ephemeralCookieStore.remove(oldestHost)
        cookieHostLastAccess.remove(oldestHost)
    }

    private class PolicyViolationIOException(val harborException: HarborException) :
        IOException(harborException.message, harborException)

    override suspend fun executeGet(
        url: String,
        definition: SourceDefinition,
        purpose: HostPurpose,
        extraHeaders: Map<String, String>,
        userAllowLan: Boolean
    ): HttpResponseData = withContext(Dispatchers.IO) {
        val allowLan = definition.network.allowLan && userAllowLan
        var currentHeaders = policyEngine.mergeAndValidateHeaders(
            definition = definition,
            extraHeaders = extraHeaders
        )

        policyEngine.validateUrl(
            url = url,
            definition = definition,
            purpose = purpose,
            userAllowLan = userAllowLan
        )

        val timeouts = definition.network.timeouts
        val delegateDns = sharedBaseClient.dns
        val enforcingDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved: List<InetAddress> = try {
                    delegateDns.lookup(hostname)
                } catch (e: Exception) {
                    throw PolicyViolationIOException(
                        HarborException(
                            HarborErrorCode.NETWORK_UNAVAILABLE,
                            "DNS lookup failed for '$hostname': ${e.message ?: e::class.java.simpleName}",
                            sourceId = definition.source.id,
                            isRetryable = true,
                            cause = e
                        )
                    )
                }
                if (!AddressSafetyValidator.isSafePublicAddress(hostname, resolved, allowLan)) {
                    throw PolicyViolationIOException(
                        HarborException(
                            HarborErrorCode.DESTINATION_BLOCKED,
                            "Connection-time DNS check blocked destination '$hostname'",
                            sourceId = definition.source.id
                        )
                    )
                }
                return resolved
            }
        }

        val client = sharedBaseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(if (sharedBaseClient.cookieJar == CookieJar.NO_COOKIES) ephemeralCookieJar else sharedBaseClient.cookieJar)
            .connectTimeout(timeouts.connectMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeouts.operationMs.toLong(), TimeUnit.MILLISECONDS)
            .dns(enforcingDns)
            .build()

        val maxBytes = definition.network.limits.maxCatalogResponseBytes
        var currentUrl = url
        var hopCount = 0

        while (true) {
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .get()
            var hasUserAgent = false
            for ((name, value) in currentHeaders) {
                if (name.equals("User-Agent", ignoreCase = true)) {
                    hasUserAgent = true
                }
                requestBuilder.header(name, value)
            }
            if (!hasUserAgent) {
                requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
            }
            val request = requestBuilder.build()

            val response = policyEngine.withRateLimit(definition) {
                try {
                    client.newCall(request).execute()
                } catch (e: PolicyViolationIOException) {
                    throw e.harborException
                } catch (e: IOException) {
                    throw HarborException(
                        HarborErrorCode.NETWORK_UNAVAILABLE,
                        "HTTP request failed for '$currentUrl': ${e.message ?: e::class.java.simpleName}",
                        sourceId = definition.source.id,
                        isRetryable = true,
                        cause = e
                    )
                }
            }

            if (response.code in REDIRECT_STATUS_CODES) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    throw HarborException(
                        HarborErrorCode.NETWORK_UNAVAILABLE,
                        "Redirect response (${response.code}) missing Location header",
                        sourceId = definition.source.id
                    )
                }
                val nextUrl = try {
                    URL(URL(currentUrl), location.trim()).toString()
                } catch (e: Exception) {
                    throw HarborException(
                        HarborErrorCode.DESTINATION_BLOCKED,
                        "Malformed redirect Location '$location'",
                        sourceId = definition.source.id,
                        cause = e
                    )
                }
                hopCount++
                currentHeaders = policyEngine.validateRedirect(
                    fromUrl = currentUrl,
                    toUrl = nextUrl,
                    hopCount = hopCount,
                    currentHeaders = currentHeaders,
                    definition = definition,
                    purpose = purpose,
                    userAllowLan = userAllowLan
                )
                currentUrl = nextUrl
                continue
            }

            return@withContext response.use { res ->
                when (res.code) {
                    429 -> throw HarborException(
                        HarborErrorCode.RATE_LIMITED,
                        "Source '${definition.source.id}' returned HTTP 429 Too Many Requests",
                        sourceId = definition.source.id,
                        isRetryable = true
                    )
                    401, 403 -> throw HarborException(
                        HarborErrorCode.AUTHENTICATION_REQUIRED,
                        "Source '${definition.source.id}' returned HTTP ${res.code}",
                        sourceId = definition.source.id
                    )
                    !in 200..299 -> throw HarborException(
                        HarborErrorCode.NETWORK_UNAVAILABLE,
                        "Source '${definition.source.id}' returned HTTP ${res.code}",
                        sourceId = definition.source.id,
                        isRetryable = res.code >= 500
                    )
                }

                val bodyString = readBodyWithByteLimit(res, maxBytes, definition.source.id)
                HttpResponseData(
                    requestedUrl = url,
                    finalUrl = currentUrl,
                    statusCode = res.code,
                    headers = res.headers.toMultimap(),
                    body = bodyString
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Unreachable redirect loop exit")
    }

    private fun readBodyWithByteLimit(
        response: Response,
        maxBytes: Long,
        sourceId: String
    ): String {
        val body = response.body ?: return ""
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            throw HarborException(
                HarborErrorCode.RESPONSE_TOO_LARGE,
                "Response Content-Length ($contentLength bytes) exceeds limit ($maxBytes bytes)",
                sourceId = sourceId
            )
        }

        val charset = body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var totalBytes = 0L

        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                if (totalBytes > maxBytes) {
                    throw HarborException(
                        HarborErrorCode.RESPONSE_TOO_LARGE,
                        "Response body exceeded maximum allowed size ($maxBytes bytes)",
                        sourceId = sourceId
                    )
                }
                out.write(buffer, 0, read)
            }
        }

        return out.toString(charset.name())
    }
}
