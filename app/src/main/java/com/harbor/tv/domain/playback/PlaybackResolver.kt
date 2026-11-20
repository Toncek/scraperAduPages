package com.harbor.tv.domain.playback

import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.model.PlaybackCandidate
import com.harbor.tv.core.model.PlaybackResolutionResult
import com.harbor.tv.core.model.PlaybackStreamType
import com.harbor.tv.core.model.PreferenceConfig
import com.harbor.tv.core.model.QualityPreference
import com.harbor.tv.core.model.UserProfile
import com.harbor.tv.core.network.HttpTransport
import com.harbor.tv.domain.preferences.PreferenceFilterEngine
import com.harbor.tv.engine.definition.PlaybackPolicyConfig
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.extract.UnifiedOperationExtractor
import com.harbor.tv.engine.policy.RequestPolicyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

/**
 * Resolves verified media playback candidates for an eligible [CatalogItem] across 1 to 3
 * declarative HTTP steps, validates every step against [HostPurpose.CATALOG] and every media
 * candidate against [HostPurpose.MEDIA], classifies stream types, honors [UserProfile.qualityPreference],
 * and supports bounded 1-time re-resolution on expired signed URLs without changing identity.
 */
class PlaybackResolver(
    private val transport: HttpTransport,
    private val policyEngine: RequestPolicyEngine = RequestPolicyEngine(),
    private val extractor: UnifiedOperationExtractor = UnifiedOperationExtractor(policyEngine = policyEngine),
    private val filterEngine: PreferenceFilterEngine = PreferenceFilterEngine(),
    private val resolveMediaDns: Boolean = false
) {

    companion object {
        const val MIN_PLAYBACK_STEPS = 1
        const val MAX_PLAYBACK_STEPS = 3
    }

    suspend fun resolvePlayback(
        item: CatalogItem,
        definition: SourceDefinition,
        profile: UserProfile,
        preferences: PreferenceConfig,
        enabledSourceIds: Set<String>
    ): PlaybackResolutionResult = withContext(Dispatchers.IO) {
        if (item.sourceId != definition.source.id) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Catalog item source '${item.sourceId}' does not match definition '${definition.source.id}'",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        // Fresh preference check before any playback network request (Spec Section 5.2 / 16.5)
        if (!filterEngine.isItemEligible(item, preferences, enabledSourceIds)) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Item '${item.compositeId}' is excluded by active profile preferences",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        val playbackOp = definition.operations.playback
        if (!definition.source.capabilities.playback || playbackOp == null) {
            throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Source '${definition.source.id}' does not support playback resolution",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        if (playbackOp.steps.size !in MIN_PLAYBACK_STEPS..MAX_PLAYBACK_STEPS) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Playback operation step count (${playbackOp.steps.size}) must be within $MIN_PLAYBACK_STEPS..$MAX_PLAYBACK_STEPS",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        val userAllowLan = profile.allowLanSources && definition.network.allowLan
        // Validate initial catalog detail URL before starting step execution
        policyEngine.validateUrl(
            url = item.sourcePageUrl,
            definition = definition,
            purpose = HostPurpose.CATALOG,
            userAllowLan = userAllowLan,
            resolveDns = resolveMediaDns
        )

        val executionResult = extractor.executePlayback(
            definition = definition,
            detailUrl = item.sourcePageUrl,
            transport = transport,
            userAllowLan = userAllowLan
        )

        val policy = definition.playback ?: PlaybackPolicyConfig()
        val allowedTypes = parseAllowedStreamTypes(policy.allowedTypes)
        val validatedCandidates = mutableListOf<PlaybackCandidate>()
        var lastPolicyError: HarborException? = null
        var hasFormatUnsupported = false

        for (rawCandidate in executionResult.candidates) {
            val normalizedUrl = if (policy.preserveMediaQueryParameters) {
                rawCandidate.url.trim()
            } else {
                stripQueryParameters(rawCandidate.url.trim())
            }

            try {
                policyEngine.validateUrl(
                    url = normalizedUrl,
                    definition = definition,
                    purpose = HostPurpose.MEDIA,
                    userAllowLan = userAllowLan,
                    resolveDns = resolveMediaDns
                )
            } catch (e: HarborException) {
                lastPolicyError = e
                continue
            }

            val streamType = classifyStreamType(normalizedUrl, rawCandidate.mimeType)
            if (streamType !in allowedTypes) {
                hasFormatUnsupported = true
                continue
            }

            val sanitizedHeaders = if (rawCandidate.headers.isEmpty()) {
                emptyMap()
            } else {
                policyEngine.validateHeaders(rawCandidate.headers)
            }

            validatedCandidates.add(
                rawCandidate.copy(
                    url = normalizedUrl,
                    streamType = streamType,
                    headers = sanitizedHeaders
                )
            )
        }

        if (validatedCandidates.isEmpty()) {
            if (lastPolicyError != null && !hasFormatUnsupported) {
                throw lastPolicyError
            }
            if (hasFormatUnsupported) {
                throw HarborException(
                    HarborErrorCode.FORMAT_UNSUPPORTED,
                    "No playback candidates matched allowedTypes ${policy.allowedTypes}",
                    sourceId = definition.source.id,
                    operation = "playback"
                )
            }
            throw HarborException(
                HarborErrorCode.PLAYBACK_NOT_FOUND,
                "No valid media playback candidates found for '${item.compositeId}'",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        val selected = selectCandidate(validatedCandidates, profile)
        PlaybackResolutionResult(
            sourceId = item.sourceId,
            sourceItemId = item.sourceItemId,
            sourcePageUrl = item.sourcePageUrl,
            candidates = validatedCandidates,
            selectedCandidate = selected,
            requestPolicySourceId = definition.source.id
        )
    }

    /**
     * Performs a bounded 1-time re-resolution when an active signed media URL expires (Spec Section 13.5),
     * verifying that `(sourceId, sourceItemId)` never changes.
     */
    suspend fun resolveAgainOnExpired(
        previousResult: PlaybackResolutionResult,
        item: CatalogItem,
        definition: SourceDefinition,
        profile: UserProfile,
        preferences: PreferenceConfig,
        enabledSourceIds: Set<String>,
        attemptNumber: Int = 1
    ): PlaybackResolutionResult {
        if (previousResult.sourceId != item.sourceId || previousResult.sourceItemId != item.sourceItemId) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Expired URL retry refused: item identity (${item.sourceId}, ${item.sourceItemId}) " +
                    "does not match previous result (${previousResult.sourceId}, ${previousResult.sourceItemId})",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        val onExpired = (definition.playback ?: PlaybackPolicyConfig()).onExpired
        if (!onExpired.action.equals("resolveAgain", ignoreCase = true)) {
            throw HarborException(
                HarborErrorCode.STREAM_EXPIRED,
                "Source '${definition.source.id}' does not permit resolveAgain on expired URLs",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        val maxAttempts = onExpired.maxAttempts.coerceIn(0, 1)
        if (attemptNumber < 1 || attemptNumber > maxAttempts) {
            throw HarborException(
                HarborErrorCode.STREAM_EXPIRED,
                "Expired URL retry budget exhausted (attempt $attemptNumber > max $maxAttempts)",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        val refreshed = resolvePlayback(
            item = item,
            definition = definition,
            profile = profile,
            preferences = preferences,
            enabledSourceIds = enabledSourceIds
        )

        if (refreshed.sourceId != previousResult.sourceId || refreshed.sourceItemId != previousResult.sourceItemId) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Expired URL re-resolution altered content identity",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        return refreshed
    }

    fun classifyStreamType(url: String, mimeType: String?): PlaybackStreamType {
        val mime = mimeType?.trim()?.lowercase(Locale.ROOT)
        if (mime != null) {
            if (mime.contains("mpegurl")) return PlaybackStreamType.HLS
            if (mime.contains("dash+xml")) return PlaybackStreamType.DASH
            if (mime.contains("video/mp4")) return PlaybackStreamType.PROGRESSIVE
        }

        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT) }.getOrNull()
            ?: url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> PlaybackStreamType.HLS
            path.endsWith(".mpd") -> PlaybackStreamType.DASH
            path.endsWith(".mp4") -> PlaybackStreamType.PROGRESSIVE
            else -> PlaybackStreamType.PROGRESSIVE
        }
    }

    fun selectCandidate(
        candidates: List<PlaybackCandidate>,
        profile: UserProfile
    ): PlaybackCandidate? {
        if (candidates.isEmpty()) return null
        return when (profile.qualityPreference) {
            QualityPreference.HIGHEST_AVAILABLE -> candidates.maxWithOrNull(
                compareBy<PlaybackCandidate>(
                    { it.resolutionHeight ?: 0 },
                    { it.bitrateKbps ?: 0 },
                    { streamTypePriority(it.streamType) }
                )
            )

            QualityPreference.PREFERRED_RESOLUTION -> {
                val target = profile.preferredResolutionHeight
                val atOrBelow = candidates.filter { (it.resolutionHeight ?: Int.MAX_VALUE) <= target }
                if (atOrBelow.isNotEmpty()) {
                    atOrBelow.minWithOrNull(
                        compareBy<PlaybackCandidate>(
                            { kotlin.math.abs((it.resolutionHeight ?: target) - target) },
                            { -(it.bitrateKbps ?: 0) }
                        )
                    )
                } else {
                    candidates.minByOrNull {
                        kotlin.math.abs((it.resolutionHeight ?: Int.MAX_VALUE) - target)
                    }
                }
            }

            QualityPreference.PREFERRED_BITRATE -> {
                val target = profile.preferredBitrateKbps
                candidates.minWithOrNull(
                    compareBy<PlaybackCandidate>(
                        { kotlin.math.abs((it.bitrateKbps ?: target) - target) },
                        { -(it.resolutionHeight ?: 0) }
                    )
                )
            }

            QualityPreference.MANUAL -> candidates.firstOrNull()

            QualityPreference.AUTO_FALLBACK -> candidates.maxWithOrNull(
                compareBy<PlaybackCandidate>(
                    { streamTypePriority(it.streamType) },
                    { it.resolutionHeight ?: 0 },
                    { it.bitrateKbps ?: 0 }
                )
            )
        }
    }

    private fun streamTypePriority(streamType: PlaybackStreamType): Int {
        return when (streamType) {
            PlaybackStreamType.HLS -> 3
            PlaybackStreamType.DASH -> 2
            PlaybackStreamType.PROGRESSIVE -> 1
        }
    }

    private fun parseAllowedStreamTypes(rawTypes: List<String>): Set<PlaybackStreamType> {
        if (rawTypes.isEmpty()) {
            return setOf(PlaybackStreamType.HLS, PlaybackStreamType.DASH, PlaybackStreamType.PROGRESSIVE)
        }
        val result = LinkedHashSet<PlaybackStreamType>()
        for (raw in rawTypes) {
            when (raw.trim().lowercase(Locale.ROOT)) {
                "hls" -> result.add(PlaybackStreamType.HLS)
                "dash" -> result.add(PlaybackStreamType.DASH)
                "progressive", "mp4" -> result.add(PlaybackStreamType.PROGRESSIVE)
            }
        }
        return result
    }

    private fun stripQueryParameters(url: String): String {
        val fragmentIndex = url.indexOf('#')
        val withoutFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val queryIndex = withoutFragment.indexOf('?')
        return if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
    }
}
