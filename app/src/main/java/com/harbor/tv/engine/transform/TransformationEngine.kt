package com.harbor.tv.engine.transform

import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.engine.definition.TransformDef
import java.math.BigDecimal
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Bounded, deterministic, side-effect-free transformation engine supporting the 12 MVP
 * declarative transforms defined in Master Specification Section 11.1.
 */
class TransformationEngine {

    companion object {
        const val MAX_TRANSFORM_CHAIN_LENGTH = 10
        const val MAX_REGEX_INPUT_CHARS = 524_288
        private val WHITESPACE_RUN_REGEX = Regex("""\s+""")
        private val DIGITS_ONLY_REGEX = Regex("""^\d+$""")
        private val HEX_32_REGEX = Regex("""^[0-9a-fA-F]{32}$""")
        private val HUMAN_DURATION_REGEX = Regex(
            """^(?:(\d+)\s*(?:h|hr|hrs|hour|hours))?\s*(?:(\d+)\s*(?:m|min|mins|minute|minutes))?\s*(?:(\d+)\s*(?:s|sec|secs|second|seconds))?$""",
            RegexOption.IGNORE_CASE
        )
        private val BLOCKED_URL_SCHEMES = setOf("javascript", "data", "vbscript", "file", "about", "blob")
    }

    /**
     * Applies [transforms] sequentially to [values]. Collection-producing transforms such as
     * `split` expand the working list, and `deduplicate` removes duplicates while preserving order.
     */
    fun apply(
        values: List<String>,
        transforms: List<TransformDef>,
        contextUrl: String? = null
    ): List<String> {
        if (transforms.size > MAX_TRANSFORM_CHAIN_LENGTH) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Transform chain length (${transforms.size}) exceeds maximum ($MAX_TRANSFORM_CHAIN_LENGTH)"
            )
        }
        var current = values
        for ((index, transform) in transforms.withIndex()) {
            current = applySingleTransform(current, transform, contextUrl)
            if (transform.op == "regexExtract" && index < transforms.lastIndex) {
                val nonEmpty = current.filter { it.isNotEmpty() }
                if (nonEmpty.isEmpty()) {
                    return if (values.size == 1) listOf("") else emptyList()
                }
                current = nonEmpty
            }
        }
        return current
    }

    /**
     * Convenience wrapper for applying a transform chain to a single extracted scalar string.
     */
    fun applySingle(
        value: String,
        transforms: List<TransformDef>,
        contextUrl: String? = null
    ): String {
        val result = apply(listOf(value), transforms, contextUrl)
        return result.firstOrNull()
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Transform chain produced no output value"
            )
    }

    private fun applySingleTransform(
        values: List<String>,
        transform: TransformDef,
        contextUrl: String?
    ): List<String> {
        return when (transform.op) {
            "trim" -> values.map { it.trim() }
            "collapseWhitespace" -> values.map { it.trim().replace(WHITESPACE_RUN_REGEX, " ") }
            "lowercase" -> values.map { it.lowercase(Locale.ROOT) }
            "uppercase" -> values.map { it.uppercase(Locale.ROOT) }
            "resolveUrl" -> {
                val base = contextUrl?.takeIf { it.isNotBlank() }
                    ?: throw HarborException(
                        HarborErrorCode.DEFINITION_INVALID,
                        "Transform 'resolveUrl' requires a non-blank contextUrl"
                    )
                values.map { resolveUrl(it, base) }
            }
            "canonicalUrl" -> values.map { canonicalUrl(it) }
            "parseInteger" -> values.map { parseInteger(it).toString() }
            "parseDecimal" -> values.map { parseDecimal(it).toPlainString() }
            "parseDuration" -> values.map { parseDurationSeconds(it).toString() }
            "split" -> {
                val delimiter = transform.delimiter
                if (delimiter.isNullOrEmpty()) {
                    throw HarborException(
                        HarborErrorCode.DEFINITION_INVALID,
                        "Transform 'split' requires a non-empty delimiter"
                    )
                }
                values.flatMap { raw ->
                    raw.split(delimiter).filter { it.isNotEmpty() }
                }
            }
            "mapValues" -> {
                if (transform.mappings.isEmpty()) {
                    throw HarborException(
                        HarborErrorCode.DEFINITION_INVALID,
                        "Transform 'mapValues' requires non-empty mappings"
                    )
                }
                values.map { v -> transform.mappings[v] ?: v }
            }
            "deduplicate" -> values.distinct()
            "urlEncode" -> values.map {
                URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }
            "template" -> {
                val tmpl = transform.template ?: ""
                values.map { tmpl.replace("\${value}", it) }
            }
            "regexExtract" -> {
                val pattern = transform.pattern ?: ""
                val regex = try {
                    Regex(pattern)
                } catch (e: Exception) {
                    throw HarborException(
                        HarborErrorCode.DEFINITION_INVALID,
                        "Invalid regexExtract pattern '$pattern': ${e.message}",
                        cause = e
                    )
                }
                val group = transform.group
                values.map { input ->
                    val bounded = input.take(MAX_REGEX_INPUT_CHARS)
                    val match = regex.find(bounded)
                    if (match != null && group in 0 until match.groupValues.size) {
                        match.groupValues[group]
                    } else {
                        ""
                    }
                }
            }
            "unescapeJson" -> values.map { unescapeJsonString(it) }
            "hexChunksToBase36" -> values.map { hexChunksToBase36(it) }
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported transform operation '${transform.op}'"
            )
        }
    }

    /**
     * Resolves [rawUrl] against [contextUrl] (final validated response URL), never trusting
     * HTML `<base>` tags and blocking non-HTTP(S) schemes such as `javascript:` or `data:`.
     */
    fun resolveUrl(rawUrl: String, contextUrl: String): String {
        val candidate = rawUrl.trim()
        if (candidate.isEmpty()) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Cannot resolve blank URL"
            )
        }
        val colonIdx = candidate.indexOf(':')
        val slashIdx = candidate.indexOf('/')
        if (colonIdx > 0 && (slashIdx == -1 || colonIdx < slashIdx)) {
            val explicitScheme = candidate.substring(0, colonIdx).lowercase(Locale.ROOT)
            if (explicitScheme in BLOCKED_URL_SCHEMES || (explicitScheme != "http" && explicitScheme != "https")) {
                throw HarborException(
                    HarborErrorCode.DESTINATION_BLOCKED,
                    "Unsupported or blocked URL scheme '$explicitScheme' in '$candidate'"
                )
            }
        }

        val baseUri = try {
            URI(contextUrl.trim())
        } catch (e: Exception) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Invalid contextUrl '$contextUrl'",
                cause = e
            )
        }
        val baseScheme = baseUri.scheme?.lowercase(Locale.ROOT)
        if (baseScheme != "http" && baseScheme != "https") {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "contextUrl must use http or https scheme"
            )
        }

        val normalizedCandidate = if (candidate.startsWith("//")) {
            "$baseScheme:$candidate"
        } else {
            candidate
        }

        val resolved = try {
            URL(baseUri.toURL(), normalizedCandidate).toURI()
        } catch (e: Exception) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Failed to resolve URL '$candidate' against '$contextUrl'",
                cause = e
            )
        }

        val resolvedScheme = resolved.scheme?.lowercase(Locale.ROOT)
        if (resolvedScheme != "http" && resolvedScheme != "https") {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "Resolved URL uses unsupported scheme '$resolvedScheme'"
            )
        }
        if (resolved.host.isNullOrBlank()) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Resolved URL is missing a host: '$resolved'"
            )
        }
        return resolved.toASCIIString()
    }

    /**
     * Normalizes scheme and hostname casing to lowercase, strips URL fragments (`#...`),
     * preserves query parameters by default, and preserves trailing slash behavior.
     */
    fun canonicalUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Cannot canonicalize blank URL"
            )
        }
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Invalid URL '$trimmed' for canonicalUrl",
                cause = e
            )
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "URL '$trimmed' is missing a scheme"
            )
        if (scheme != "http" && scheme != "https") {
            throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "Unsupported URL scheme '$scheme' in canonicalUrl"
            )
        }
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "URL '$trimmed' is missing a host"
            )

        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        val userInfoPart = uri.rawUserInfo?.let { "$it@" } ?: ""
        val pathPart = uri.rawPath ?: ""
        val queryPart = uri.rawQuery?.let { "?$it" } ?: ""
        return "$scheme://$userInfoPart$host$portPart$pathPart$queryPart"
    }

    /**
     * Parses a strict integer string (optional leading `+`/`-` with optional thousand separators
     * stripped only when formatted consistently, or pure digits).
     */
    fun parseInteger(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Cannot parse blank value as integer"
            )
        }
        return trimmed.toLongOrNull()
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Invalid integer value '$trimmed'"
            )
    }

    /**
     * Parses a strict decimal value without silently coercing invalid strings.
     */
    fun parseDecimal(raw: String): BigDecimal {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Cannot parse blank value as decimal"
            )
        }
        return try {
            BigDecimal(trimmed).stripTrailingZeros()
        } catch (e: NumberFormatException) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Invalid decimal value '$trimmed'",
                cause = e
            )
        }
    }

    /**
     * Parses `SS`, `MM:SS`, or `HH:MM:SS` into non-negative integer seconds.
     * Rejects `LIVE` and unrecognized strings by throwing [HarborException] rather than returning 0.
     */
    fun parseDurationSeconds(raw: String): Int {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Cannot parse blank duration"
            )
        }
        val parts = trimmed.split(':')
        if (parts.size in 1..3 && parts.all { DIGITS_ONLY_REGEX.matches(it) }) {
            return parseColonDuration(parts, trimmed)
        }

        val humanMatch = HUMAN_DURATION_REGEX.matchEntire(trimmed)
        if (humanMatch != null) {
            val hStr = humanMatch.groupValues[1]
            val mStr = humanMatch.groupValues[2]
            val sStr = humanMatch.groupValues[3]
            if (hStr.isNotEmpty() || mStr.isNotEmpty() || sStr.isNotEmpty()) {
                return try {
                    val hours = hStr.takeIf { it.isNotEmpty() }?.toLong() ?: 0L
                    val minutes = mStr.takeIf { it.isNotEmpty() }?.toLong() ?: 0L
                    val seconds = sStr.takeIf { it.isNotEmpty() }?.toLong() ?: 0L
                    val total = Math.addExact(
                        Math.multiplyExact(hours, 3600L),
                        Math.addExact(Math.multiplyExact(minutes, 60L), seconds)
                    )
                    if (total > Int.MAX_VALUE) {
                        throw HarborException(
                            HarborErrorCode.DEFINITION_INVALID,
                            "Duration out of range: '$trimmed'"
                        )
                    }
                    total.toInt()
                } catch (e: ArithmeticException) {
                    throw HarborException(
                        HarborErrorCode.DEFINITION_INVALID,
                        "Duration overflow in '$trimmed'",
                        cause = e
                    )
                }
            }
        }

        throw HarborException(
            HarborErrorCode.DEFINITION_INVALID,
            "Unrecognized duration format '$trimmed'; expected SS, MM:SS, HH:MM:SS, or human duration"
        )
    }

    private fun parseColonDuration(parts: List<String>, trimmed: String): Int {
        return try {
            when (parts.size) {
                1 -> {
                    val seconds = parts[0].toLong()
                    if (seconds < 0 || seconds > Int.MAX_VALUE) {
                        throw HarborException(
                            HarborErrorCode.DEFINITION_INVALID,
                            "Duration out of range: '$trimmed'"
                        )
                    }
                    seconds.toInt()
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toLong()
                    if (seconds !in 0L..59L) {
                        throw HarborException(
                            HarborErrorCode.DEFINITION_INVALID,
                            "Invalid seconds '$seconds' in MM:SS duration '$trimmed'"
                        )
                    }
                    val total = Math.addExact(Math.multiplyExact(minutes, 60L), seconds)
                    if (total > Int.MAX_VALUE) {
                        throw HarborException(
                            HarborErrorCode.DEFINITION_INVALID,
                            "Duration out of range: '$trimmed'"
                        )
                    }
                    total.toInt()
                }
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toLong()
                    if (minutes !in 0L..59L || seconds !in 0L..59L) {
                        throw HarborException(
                            HarborErrorCode.DEFINITION_INVALID,
                            "Invalid MM:SS components in HH:MM:SS duration '$trimmed'"
                        )
                    }
                    val total = Math.addExact(
                        Math.multiplyExact(hours, 3600L),
                        Math.addExact(Math.multiplyExact(minutes, 60L), seconds)
                    )
                    if (total > Int.MAX_VALUE) {
                        throw HarborException(
                            HarborErrorCode.DEFINITION_INVALID,
                            "Duration out of range: '$trimmed'"
                        )
                    }
                    total.toInt()
                }
                else -> throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Unrecognized duration format '$trimmed'"
                )
            }
        } catch (e: ArithmeticException) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Duration overflow in '$trimmed'",
                cause = e
            )
        } catch (e: NumberFormatException) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Invalid numeric component in duration '$trimmed'",
                cause = e
            )
        }
    }

    private fun unescapeJsonString(raw: String): String {
        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003d", "=", ignoreCase = true)
            .replace("\\\"", "\"")
    }

    private fun hexChunksToBase36(raw: String): String {
        val trimmed = raw.trim()
        if (!HEX_32_REGEX.matches(trimmed)) {
            return raw
        }
        val sb = StringBuilder()
        for (i in 0 until 32 step 8) {
            sb.append(trimmed.substring(i, i + 8).toLong(16).toString(36))
        }
        return sb.toString()
    }
}
