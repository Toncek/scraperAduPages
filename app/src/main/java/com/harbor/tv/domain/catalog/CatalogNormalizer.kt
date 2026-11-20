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
        val rawThumbnails = extractStringList(rawFields["thumbnailUrl"] ?: rawFields["thumbnailReferences"])

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

        val validThumbnails = filterValidThumbnails(rawThumbnails, definition, userAllowLan)
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
            thumbnailReferences = validThumbnails,
            metadataCompleteness = completeness,
            metadataProvenance = metadataProvenance,
            fetchedAt = fetchedAt
        )
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
