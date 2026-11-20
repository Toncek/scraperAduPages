package com.harbor.tv.domain.catalog

import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.CategoryItem
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.model.LiveStatus
import com.harbor.tv.core.model.MetadataCompleteness
import com.harbor.tv.core.model.TagConfidence
import com.harbor.tv.core.model.TagProvenance
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.policy.RequestPolicyEngine
import java.util.Locale

/**
 * Normalizes raw extracted field maps and extracted catalog items into canonical [CatalogItem]
 * and [CategoryItem] instances, applying taxonomy mappings with [TagProvenance], computing
 * [MetadataCompleteness], validating thumbnail URLs against `network.allowedHosts.images`,
 * and deduplicating strictly by `(sourceId, sourceItemId)`.
 */
class CatalogNormalizer(
    private val policyEngine: RequestPolicyEngine = RequestPolicyEngine(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    data class TagMappingResult(
        val canonicalTags: List<String>,
        val tagProvenance: List<TagProvenance>
    )

    fun normalizeCategory(
        rawFields: Map<String, Any?>,
        definition: SourceDefinition
    ): CategoryItem {
        val id = extractScalar(rawFields["id"])?.takeIf { it.isNotBlank() }
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Category record missing required field 'id'",
                sourceId = definition.source.id,
                operation = "categories"
            )
        val title = extractScalar(rawFields["title"])?.takeIf { it.isNotBlank() }
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Category record missing required field 'title'",
                sourceId = definition.source.id,
                operation = "categories"
            )
        val url = extractScalar(rawFields["url"])?.takeIf { it.isNotBlank() }
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Category record missing required field 'url'",
                sourceId = definition.source.id,
                operation = "categories"
            )

        return CategoryItem(
            sourceId = definition.source.id,
            id = id,
            title = title,
            url = url
        )
    }

    fun normalizeItem(
        rawFields: Map<String, Any?>,
        definition: SourceDefinition,
        userAllowLan: Boolean = false,
        metadataProvenance: String = "listing",
        fetchedAt: Long = clockMs()
    ): CatalogItem {
        val sourceId = definition.source.id
        val sourceItemId = extractScalar(rawFields["id"] ?: rawFields["sourceItemId"])
            ?.takeIf { it.isNotBlank() }
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Catalog record missing required field 'id'",
                sourceId = sourceId,
                operation = metadataProvenance
            )
        val title = extractScalar(rawFields["title"])
            ?.takeIf { it.isNotBlank() }
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Catalog record missing required field 'title'",
                sourceId = sourceId,
                operation = metadataProvenance
            )
        val sourcePageUrl = extractScalar(rawFields["detailUrl"] ?: rawFields["sourcePageUrl"] ?: rawFields["url"])
            ?.takeIf { it.isNotBlank() }
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Catalog record missing required field 'detailUrl'",
                sourceId = sourceId,
                operation = metadataProvenance
            )

        val description = extractScalar(rawFields["description"])?.takeIf { it.isNotBlank() }
        val durationSeconds = extractInt(rawFields["durationSeconds"])
        val liveStatus = parseLiveStatus(
            rawValue = extractScalar(rawFields["liveStatus"]),
            definition = definition,
            durationSeconds = durationSeconds
        )
        val sourceTags = extractStringList(rawFields["sourceTags"])
        val rawLanguages = extractStringList(rawFields["languages"] ?: rawFields["language"])
        val contentRating = extractScalar(rawFields["contentRating"])?.takeIf { it.isNotBlank() }
        val rawMaxQuality = extractScalar(
            rawFields["maxQuality"] ?: rawFields["maxQualityLabel"] ?: rawFields["qualityLabel"]
        )?.takeIf { it.isNotBlank() }
        val explicitMaxResolutionHeight = extractInt(
            rawFields["maxResolutionHeight"] ?: rawFields["resolutionHeight"]
        )
        val rawThumbnails = (
            extractStringList(rawFields["thumbnailUrl"]) +
                extractStringList(rawFields["thumbnailReferences"]) +
                extractStringList(rawFields["previewThumbnails"])
            ).distinct()

        return buildNormalizedCatalogItem(
            sourceId = sourceId,
            sourceItemId = sourceItemId,
            sourcePageUrl = sourcePageUrl,
            title = title,
            description = description,
            durationSeconds = durationSeconds,
            liveStatus = liveStatus,
            sourceTags = sourceTags,
            languages = rawLanguages,
            contentRating = contentRating,
            rawMaxQuality = rawMaxQuality,
            explicitMaxResolutionHeight = explicitMaxResolutionHeight,
            rawThumbnails = rawThumbnails,
            definition = definition,
            userAllowLan = userAllowLan,
            metadataProvenance = metadataProvenance,
            fetchedAt = fetchedAt
        )
    }

    fun normalizeCatalogItem(
        item: CatalogItem,
        definition: SourceDefinition,
        userAllowLan: Boolean = false
    ): CatalogItem {
        return buildNormalizedCatalogItem(
            sourceId = definition.source.id,
            sourceItemId = item.sourceItemId,
            sourcePageUrl = item.sourcePageUrl,
            title = item.title,
            description = item.description,
            durationSeconds = item.durationSeconds,
            liveStatus = item.liveStatus,
            sourceTags = item.sourceTags,
            languages = item.languages,
            contentRating = item.contentRating,
            rawMaxQuality = item.maxQualityLabel,
            explicitMaxResolutionHeight = item.maxResolutionHeight,
            rawThumbnails = item.thumbnailReferences,
            definition = definition,
            userAllowLan = userAllowLan,
            metadataProvenance = item.metadataProvenance,
            fetchedAt = item.fetchedAt
        )
    }

    fun normalizeItems(
        rawItems: List<Map<String, Any?>>,
        definition: SourceDefinition,
        userAllowLan: Boolean = false,
        metadataProvenance: String = "listing",
        fetchedAt: Long = clockMs()
    ): List<CatalogItem> {
        val normalized = rawItems.map { raw ->
            normalizeItem(
                rawFields = raw,
                definition = definition,
                userAllowLan = userAllowLan,
                metadataProvenance = metadataProvenance,
                fetchedAt = fetchedAt
            )
        }
        return deduplicateItems(normalized)
    }

    fun normalizeCatalogItems(
        items: List<CatalogItem>,
        definition: SourceDefinition,
        userAllowLan: Boolean = false
    ): List<CatalogItem> {
        return deduplicateItems(items.map { normalizeCatalogItem(it, definition, userAllowLan) })
    }

    /**
     * Deduplicates items strictly by `(sourceId, sourceItemId)` while preserving first-seen order.
     */
    fun deduplicateItems(items: List<CatalogItem>): List<CatalogItem> {
        if (items.size <= 1) return items
        val seen = HashSet<Pair<String, String>>(items.size)
        val result = ArrayList<CatalogItem>(items.size)
        for (item in items) {
            val key = item.sourceId to item.sourceItemId
            if (seen.add(key)) {
                result.add(item)
            }
        }
        return result
    }

    fun mapSourceTags(
        sourceTags: List<String>,
        definition: SourceDefinition,
        sourceField: String = "sourceTags"
    ): TagMappingResult {
        if (sourceTags.isEmpty()) {
            return TagMappingResult(emptyList(), emptyList())
        }

        val taxonomy = definition.taxonomy
        val mappings = taxonomy?.mappings.orEmpty()
        val mappingVersion = taxonomy?.mappingVersion ?: "1.0"
        val namespace = taxonomy?.namespace?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
            ?: "source"
        val lowercaseIndex = mappings.entries.associate { (k, v) ->
            k.trim().lowercase(Locale.ROOT) to v
        }

        val canonicalTags = LinkedHashSet<String>()
        val provenanceList = mutableListOf<TagProvenance>()

        for (rawTag in sourceTags) {
            val cleaned = rawTag.trim()
            if (cleaned.isEmpty()) continue

            val explicitTargets = mappings[cleaned]
                ?: lowercaseIndex[cleaned.lowercase(Locale.ROOT)]

            if (!explicitTargets.isNullOrEmpty()) {
                for (target in explicitTargets) {
                    val normalizedTarget = target.trim()
                    if (normalizedTarget.isEmpty()) continue
                    canonicalTags.add(normalizedTarget)
                    provenanceList.add(
                        TagProvenance(
                            canonicalTag = normalizedTarget,
                            sourceField = sourceField,
                            originalValue = cleaned,
                            mappingVersion = mappingVersion,
                            confidence = TagConfidence.EXPLICIT
                        )
                    )
                }
            } else {
                val slug = normalizeFallbackSlug(cleaned)
                if (slug.isNotEmpty()) {
                    val derivedTag = "$namespace.$slug"
                    canonicalTags.add(derivedTag)
                    provenanceList.add(
                        TagProvenance(
                            canonicalTag = derivedTag,
                            sourceField = sourceField,
                            originalValue = cleaned,
                            mappingVersion = mappingVersion,
                            confidence = TagConfidence.DERIVED
                        )
                    )
                }
            }
        }

        return TagMappingResult(
            canonicalTags = canonicalTags.toList(),
            tagProvenance = provenanceList
        )
    }

    fun filterValidThumbnails(
        thumbnailUrls: List<String>,
        definition: SourceDefinition,
        userAllowLan: Boolean = false
    ): List<String> {
        if (thumbnailUrls.isEmpty()) return emptyList()
        val valid = LinkedHashSet<String>()
        for (rawUrl in thumbnailUrls) {
            val candidate = rawUrl.trim()
            if (candidate.isEmpty()) continue
            val isAllowed = runCatching {
                policyEngine.validateUrl(
                    url = candidate,
                    definition = definition,
                    purpose = HostPurpose.IMAGES,
                    userAllowLan = userAllowLan,
                    resolveDns = false
                )
            }.isSuccess
            if (isAllowed) {
                valid.add(candidate)
            }
        }
        return valid.toList()
    }

    fun computeMetadataCompleteness(
        sourceItemId: String,
        title: String,
        sourcePageUrl: String,
        durationSeconds: Int?,
        liveStatus: LiveStatus,
        sourceTags: List<String>,
        canonicalTags: List<String>,
        description: String?
    ): MetadataCompleteness {
        if (sourceItemId.isBlank() || title.isBlank() || sourcePageUrl.isBlank()) {
            return MetadataCompleteness.INSUFFICIENT
        }
        if (canonicalTags.isEmpty() && sourceTags.isEmpty() && durationSeconds == null && description.isNullOrBlank() && liveStatus != LiveStatus.LIVE) {
            return MetadataCompleteness.INSUFFICIENT
        }
        if (canonicalTags.isEmpty()) {
            return MetadataCompleteness.PARTIAL
        }
        if (liveStatus != LiveStatus.LIVE && durationSeconds == null && description.isNullOrBlank()) {
            return MetadataCompleteness.PARTIAL
        }
        return MetadataCompleteness.COMPLETE
    }

    private fun buildNormalizedCatalogItem(
        sourceId: String,
        sourceItemId: String,
        sourcePageUrl: String,
        title: String,
        description: String?,
        durationSeconds: Int?,
        liveStatus: LiveStatus,
        sourceTags: List<String>,
        languages: List<String>,
        contentRating: String?,
        rawMaxQuality: String?,
        explicitMaxResolutionHeight: Int?,
        rawThumbnails: List<String>,
        definition: SourceDefinition,
        userAllowLan: Boolean,
        metadataProvenance: String,
        fetchedAt: Long
    ): CatalogItem {
        val cleanedSourceTags = sourceTags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val tagResult = mapSourceTags(cleanedSourceTags, definition)

        val resolvedLanguages = LinkedHashSet<String>()
        for (lang in languages) {
            val trimmed = lang.trim().lowercase(Locale.ROOT)
            if (trimmed.isNotEmpty()) resolvedLanguages.add(trimmed)
        }
        for (tag in tagResult.canonicalTags) {
            if (tag.startsWith("language.", ignoreCase = true)) {
                val langCode = tag.substringAfter('.').trim().lowercase(Locale.ROOT)
                if (langCode.isNotEmpty()) resolvedLanguages.add(langCode)
            }
        }
        if (resolvedLanguages.isEmpty()) {
            definition.source.language?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() }
                ?.let { resolvedLanguages.add(it) }
        }

        val (maxQualityLabel, maxResolutionHeight) = resolveMaxQuality(
            rawMaxQuality = rawMaxQuality,
            explicitHeight = explicitMaxResolutionHeight,
            sourceTags = cleanedSourceTags,
            canonicalTags = tagResult.canonicalTags,
            title = title
        )

        val validThumbnails = sampleThumbnails(
            filterValidThumbnails(rawThumbnails, definition, userAllowLan)
        )
        val completeness = computeMetadataCompleteness(
            sourceItemId = sourceItemId,
            title = title,
            sourcePageUrl = sourcePageUrl,
            durationSeconds = durationSeconds,
            liveStatus = liveStatus,
            sourceTags = cleanedSourceTags,
            canonicalTags = tagResult.canonicalTags,
            description = description
        )

        return CatalogItem(
            sourceId = sourceId,
            sourceItemId = sourceItemId,
            sourcePageUrl = sourcePageUrl,
            title = title,
            description = description,
            durationSeconds = durationSeconds,
            liveStatus = liveStatus,
            sourceTags = cleanedSourceTags,
            canonicalTags = tagResult.canonicalTags,
            tagProvenance = tagResult.tagProvenance,
            languages = resolvedLanguages.toList(),
            contentRating = contentRating,
            maxQualityLabel = maxQualityLabel,
            maxResolutionHeight = maxResolutionHeight,
            thumbnailReferences = validThumbnails,
            metadataCompleteness = completeness,
            metadataProvenance = metadataProvenance,
            fetchedAt = fetchedAt
        )
    }

    fun resolveMaxQuality(
        rawMaxQuality: String?,
        explicitHeight: Int?,
        sourceTags: List<String>,
        canonicalTags: List<String>,
        title: String
    ): Pair<String?, Int?> {
        val rawNormalized = rawMaxQuality?.let { normalizeQualityToken(it) }
        if (rawNormalized != null) {
            return rawNormalized.first to (rawNormalized.second ?: explicitHeight)
        }

        val candidates = sequence {
            for (tag in sourceTags) yield(tag)
            for (tag in canonicalTags) yield(tag)
            yield(title)
        }
        var best: Pair<String, Int>? = null
        for (candidate in candidates) {
            val parsed = detectQualityInText(candidate) ?: continue
            if (best == null || parsed.second > best.second) {
                best = parsed
            }
        }
        if (best != null) {
            return best.first to (explicitHeight ?: best.second)
        }

        val fallbackLabel = rawMaxQuality?.trim()?.takeIf { it.isNotEmpty() }
            ?: explicitHeight?.let { labelForHeight(it) }
        return fallbackLabel to explicitHeight
    }

    private fun normalizeQualityToken(raw: String): Pair<String, Int?>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        return when {
            lower == "format-4k-text" || lower == "4k" || lower == "uhd" || lower == "2160p" || lower == "2160" -> "4K" to 2160
            lower == "format-hd-text" || lower == "hd" -> "HD" to 720
            else -> detectQualityInText(trimmed)
        }
    }

    private fun detectQualityInText(text: String): Pair<String, Int>? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            Regex("""(?:^|[^a-z0-9])(4k|uhd|2160p|3840x2160)(?:$|[^a-z0-9])""").containsMatchIn(lower) ||
                lower.contains("format-4k-text") -> "4K" to 2160
            Regex("""(?:^|[^a-z0-9])(1440p|2k|qhd)(?:$|[^a-z0-9])""").containsMatchIn(lower) -> "1440p" to 1440
            Regex("""(?:^|[^a-z0-9])(1080p|fhd)(?:$|[^a-z0-9])""").containsMatchIn(lower) -> "1080p" to 1080
            Regex("""(?:^|[^a-z0-9])720p(?:$|[^a-z0-9])""").containsMatchIn(lower) -> "720p" to 720
            Regex("""(?:^|[^a-z0-9])hd(?:$|[^a-z0-9])""").containsMatchIn(lower) ||
                lower.contains("format-hd-text") -> "HD" to 720
            Regex("""(?:^|[^a-z0-9])480p(?:$|[^a-z0-9])""").containsMatchIn(lower) -> "480p" to 480
            Regex("""(?:^|[^a-z0-9])360p(?:$|[^a-z0-9])""").containsMatchIn(lower) -> "360p" to 360
            Regex("""(?:^|[^a-z0-9])240p(?:$|[^a-z0-9])""").containsMatchIn(lower) -> "240p" to 240
            else -> null
        }
    }

    private fun labelForHeight(height: Int): String? {
        return when {
            height >= 2160 -> "4K"
            height >= 1440 -> "1440p"
            height >= 1080 -> "1080p"
            height >= 720 -> "720p"
            height >= 480 -> "480p"
            height >= 360 -> "360p"
            height > 0 -> "${height}p"
            else -> null
        }
    }

    private fun parseLiveStatus(
        rawValue: String?,
        definition: SourceDefinition,
        durationSeconds: Int?
    ): LiveStatus {
        return when (rawValue?.trim()?.lowercase(Locale.ROOT)) {
            "live", "public", "true" -> LiveStatus.LIVE
            "upcoming", "scheduled" -> LiveStatus.UPCOMING
            "offline", "ondemand", "on_demand", "false" -> LiveStatus.ON_DEMAND
            null, "" -> if (definition.source.capabilities.live && durationSeconds == null) {
                LiveStatus.LIVE
            } else {
                LiveStatus.ON_DEMAND
            }
            else -> LiveStatus.ON_DEMAND
        }
    }

    private fun sampleThumbnails(urls: List<String>, maxCount: Int = 6): List<String> {
        if (urls.size <= maxCount) return urls
        if (maxCount <= 1) return urls.take(maxCount)
        return (0 until maxCount).map { i ->
            val idx = (i * (urls.size - 1)) / (maxCount - 1)
            urls[idx]
        }.distinct()
    }

    private fun normalizeFallbackSlug(rawTag: String): String {
        return rawTag.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9_-]+"""), "_")
            .trim('_')
    }

    private fun extractScalar(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value.trim()
            is Number, is Boolean -> value.toString()
            is Iterable<*> -> value.firstOrNull()?.let { extractScalar(it) }
            else -> value.toString().trim()
        }
    }

    private fun extractInt(value: Any?): Int? {
        return when (value) {
            null -> null
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            is Iterable<*> -> value.firstOrNull()?.let { extractInt(it) }
            else -> null
        }
    }

    private fun extractStringList(value: Any?): List<String> {
        return when (value) {
            null -> emptyList()
            is String -> listOf(value.trim()).filter { it.isNotEmpty() }
            is Iterable<*> -> value.mapNotNull { extractScalar(it)?.takeIf { s -> s.isNotEmpty() } }
            else -> listOfNotNull(extractScalar(value)?.takeIf { it.isNotEmpty() })
        }
    }
}
