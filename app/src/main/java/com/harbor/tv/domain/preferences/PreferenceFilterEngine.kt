package com.harbor.tv.domain.preferences

import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.LiveStatus
import com.harbor.tv.core.model.MetadataCompleteness
import com.harbor.tv.core.model.PreferenceConfig
import com.harbor.tv.core.model.TagPreferenceState
import com.harbor.tv.core.model.UnknownMetadataMode
import java.util.Locale

/**
 * Enforces Harbor's 6-stage preference and exclusion policy (Master Specification Section 16):
 * 1. Source authorization
 * 2. Profile restrictions (live/on-demand, duration bounds, allowed languages)
 * 3. Hard tag exclusions (canonicalTags and sourceTags)
 * 4. Required metadata policy (Strict vs Standard)
 * 5. Search/category query constraints
 * 6. Ranking (preferred tags boosted to top; excluded items never return)
 *
 * Also enforces Section 16.4 (filtering before artwork) by stripping thumbnail references
 * from any non-eligible items.
 */
class PreferenceFilterEngine {

    data class FilterPartitionResult(
        val eligible: List<CatalogItem>,
        val rejectedWithoutArtwork: List<CatalogItem>
    )

    fun filterAndRank(
        items: List<CatalogItem>,
        preferences: PreferenceConfig,
        enabledSourceIds: Set<String>,
        searchQuery: String? = null
    ): List<CatalogItem> {
        if (items.isEmpty()) return emptyList()

        val normalizedQueryTokens = parseQueryTokens(searchQuery)
        val eligibleWithScore = mutableListOf<Pair<CatalogItem, Int>>()

        for (item in items) {
            // Stages 1..4
            if (!isItemEligible(item, preferences, enabledSourceIds)) {
                // Section 16.4: never retain artwork references on rejected items
                stripArtwork(item)
                continue
            }
            // Stage 5: Search/query constraint
            if (normalizedQueryTokens.isNotEmpty() && !matchesQuery(item, normalizedQueryTokens)) {
                continue
            }
            // Stage 6: Ranking score (higher = more preferred tags matched)
            val score = computePreferenceScore(item, preferences)
            eligibleWithScore.add(item to score)
        }

        return eligibleWithScore
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Partitions [items] into eligible ranked items and rejected items whose `thumbnailReferences`
     * have been stripped before any UI or artwork-loader inspection (Section 16.4).
     */
    fun partitionAndStripRejectedArtwork(
        items: List<CatalogItem>,
        preferences: PreferenceConfig,
        enabledSourceIds: Set<String>,
        searchQuery: String? = null
    ): FilterPartitionResult {
        val normalizedQueryTokens = parseQueryTokens(searchQuery)
        val eligibleWithScore = mutableListOf<Pair<CatalogItem, Int>>()
        val rejected = mutableListOf<CatalogItem>()

        for (item in items) {
            if (!isItemEligible(item, preferences, enabledSourceIds)) {
                rejected.add(stripArtwork(item))
                continue
            }
            if (normalizedQueryTokens.isNotEmpty() && !matchesQuery(item, normalizedQueryTokens)) {
                rejected.add(stripArtwork(item))
                continue
            }
            eligibleWithScore.add(item to computePreferenceScore(item, preferences))
        }

        return FilterPartitionResult(
            eligible = eligibleWithScore.sortedByDescending { it.second }.map { it.first },
            rejectedWithoutArtwork = rejected
        )
    }

    /**
     * Evaluates stages 1..4 deterministically for any single entry point (Home, Categories,
     * Search, Favorites, History, Deep links, Autoplay).
     */
    fun isItemEligible(
        item: CatalogItem,
        preferences: PreferenceConfig,
        enabledSourceIds: Set<String>
    ): Boolean {
        // Stage 1: Source authorization
        if (item.sourceId !in enabledSourceIds || item.sourceId in preferences.excludedSources) {
            return false
        }

        // Stage 2: Profile restrictions (live/on-demand, duration bounds, allowed languages)
        if (!passesProfileRestrictions(item, preferences)) {
            return false
        }

        // Stage 3: Hard exclusions (if ANY canonicalTag or sourceTag is EXCLUDED, immediately reject)
        if (hasExcludedTag(item, preferences)) {
            return false
        }

        // Stage 4: Required metadata policy (Strict mode)
        if (!passesRequiredMetadataPolicy(item, preferences)) {
            return false
        }

        return true
    }

    fun stripArtwork(item: CatalogItem): CatalogItem {
        return if (item.thumbnailReferences.isEmpty()) {
            item
        } else {
            item.copy(thumbnailReferences = emptyList())
        }
    }

    private fun passesProfileRestrictions(
        item: CatalogItem,
        preferences: PreferenceConfig
    ): Boolean {
        when (item.liveStatus) {
            LiveStatus.LIVE -> if (!preferences.includeLive) return false
            LiveStatus.ON_DEMAND -> if (!preferences.includeOnDemand) return false
            LiveStatus.UPCOMING -> if (!preferences.includeLive && !preferences.includeOnDemand) return false
        }

        val minDuration = preferences.minDurationSeconds
        val maxDuration = preferences.maxDurationSeconds
        if (minDuration != null || maxDuration != null) {
            val duration = item.durationSeconds
            if (duration == null) {
                if (item.liveStatus != LiveStatus.LIVE && preferences.unknownMetadataMode == UnknownMetadataMode.STRICT) {
                    return false
                }
            } else {
                if (minDuration != null && duration < minDuration) return false
                if (maxDuration != null && duration > maxDuration) return false
            }
        }

        if (preferences.allowedLanguages.isNotEmpty()) {
            val allowedNormalized = preferences.allowedLanguages
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .toSet()

            val itemLanguages = collectNormalizedLanguages(item)
            if (itemLanguages.isEmpty()) {
                if (preferences.unknownMetadataMode == UnknownMetadataMode.STRICT) {
                    return false
                }
            } else if (itemLanguages.none { it in allowedNormalized }) {
                return false
            }
        }

        return true
    }

    private fun hasExcludedTag(
        item: CatalogItem,
        preferences: PreferenceConfig
    ): Boolean {
        if (preferences.tagPreferences.isEmpty()) return false
        val excludedKeys = preferences.tagPreferences
            .filterValues { it == TagPreferenceState.EXCLUDED }
            .keys
            .map { it.trim().lowercase(Locale.ROOT) }
            .toSet()

        if (excludedKeys.isEmpty()) return false

        val allItemTags = buildItemTagLookup(item)
        return allItemTags.any { it in excludedKeys }
    }

    private fun passesRequiredMetadataPolicy(
        item: CatalogItem,
        preferences: PreferenceConfig
    ): Boolean {
        if (preferences.unknownMetadataMode != UnknownMetadataMode.STRICT) {
            return true
        }
        if (item.metadataCompleteness != MetadataCompleteness.COMPLETE) {
            return false
        }
        if (item.canonicalTags.isEmpty()) {
            return false
        }
        if (preferences.requiredCanonicalTagPrefixes.isNotEmpty()) {
            val missingRequiredPrefix = preferences.requiredCanonicalTagPrefixes.any { prefix ->
                val cleanPrefix = prefix.trim()
                cleanPrefix.isNotEmpty() && item.canonicalTags.none { tag ->
                    tag.startsWith(cleanPrefix, ignoreCase = true)
                }
            }
            if (missingRequiredPrefix) {
                return false
            }
        }
        return true
    }

    private fun computePreferenceScore(
        item: CatalogItem,
        preferences: PreferenceConfig
    ): Int {
        if (preferences.tagPreferences.isEmpty()) return 0
        val preferredKeys = preferences.tagPreferences
            .filterValues { it == TagPreferenceState.PREFERRED }
            .keys
            .map { it.trim().lowercase(Locale.ROOT) }
            .toSet()

        if (preferredKeys.isEmpty()) return 0
        return buildItemTagLookup(item).count { it in preferredKeys }
    }

    private fun buildItemTagLookup(item: CatalogItem): Set<String> {
        val tags = LinkedHashSet<String>()
        for (tag in item.canonicalTags) {
            val norm = tag.trim().lowercase(Locale.ROOT)
            if (norm.isNotEmpty()) tags.add(norm)
        }
        for (tag in item.sourceTags) {
            val norm = tag.trim().lowercase(Locale.ROOT)
            if (norm.isNotEmpty()) tags.add(norm)
        }
        for (prov in item.tagProvenance) {
            val c = prov.canonicalTag.trim().lowercase(Locale.ROOT)
            val o = prov.originalValue.trim().lowercase(Locale.ROOT)
            if (c.isNotEmpty()) tags.add(c)
            if (o.isNotEmpty()) tags.add(o)
        }
        return tags
    }

    private fun collectNormalizedLanguages(item: CatalogItem): Set<String> {
        val langs = LinkedHashSet<String>()
        for (lang in item.languages) {
            val norm = lang.trim().lowercase(Locale.ROOT)
            if (norm.isNotEmpty()) langs.add(norm)
        }
        for (tag in item.canonicalTags) {
            if (tag.startsWith("language.", ignoreCase = true)) {
                val code = tag.substringAfter('.').trim().lowercase(Locale.ROOT)
                if (code.isNotEmpty()) langs.add(code)
            }
        }
        return langs
    }

    private fun parseQueryTokens(searchQuery: String?): List<String> {
        if (searchQuery.isNullOrBlank()) return emptyList()
        return searchQuery.trim()
            .lowercase(Locale.ROOT)
            .split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
    }

    private fun matchesQuery(item: CatalogItem, queryTokens: List<String>): Boolean {
        val searchableParts = buildList {
            add(item.title)
            if (!item.description.isNullOrBlank()) add(item.description)
            addAll(item.sourceTags)
            addAll(item.canonicalTags)
            add(item.sourceId)
        }.joinToString(" ").lowercase(Locale.ROOT)

        return queryTokens.all { token -> searchableParts.contains(token) }
    }
}
