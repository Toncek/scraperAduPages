package com.harbor.tv.domain.preferences

import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.LabelComboSuggestion
import com.harbor.tv.core.model.UserTasteProfile
import java.util.Locale

/**
 * Local on-device taste tracker and freshness ranker:
 * - Learns label, co-occurring label-pair, and source affinities from local interactions
 * - Generates smart 2-label and 3-label discovery combinations for Home/Search
 * - Tracks an LRU window of seen items to prioritize fresh unseen movies and reduce repeats
 */
class UserTasteEngine {

    companion object {
        const val MAX_SEEN_ITEMS = 500
        const val MAX_TRACKED_ENTRIES = 120
        const val FRESHNESS_BONUS = 1000.0
        private const val PAIR_SEPARATOR = "::"
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val TITLE_STRIP_REGEX = Regex("""[^a-z0-9\s]""")
    }

    fun cleanLabel(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withoutNamespace = if ('.' in trimmed) trimmed.substringAfter('.') else trimmed
        val normalized = withoutNamespace
            .replace('_', ' ')
            .replace('-', ' ')
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        if (normalized.length < 2 || normalized.length > 28) return null
        if (normalized.all { it.isDigit() }) return null
        return normalized
    }

    fun extractItemLabels(item: CatalogItem): List<String> {
        val labels = LinkedHashSet<String>()
        for (raw in item.sourceTags) {
            cleanLabel(raw)?.let { labels.add(it) }
        }
        for (raw in item.canonicalTags) {
            cleanLabel(raw)?.let { labels.add(it) }
        }
        val height = item.maxResolutionHeight ?: 0
        when {
            height >= 2160 -> labels.add("4k")
            height >= 1080 -> labels.add("1080p")
        }
        return labels.toList()
    }

    fun recordInteraction(
        current: UserTasteProfile,
        item: CatalogItem,
        weight: Double
    ): UserTasteProfile {
        if (weight <= 0.0) return current

        val itemLabels = extractItemLabels(item)
        val updatedLabels = current.labelScores.toMutableMap()
        for (label in itemLabels) {
            updatedLabels[label] = (updatedLabels[label] ?: 0.0) + weight
        }

        val updatedPairs = current.pairScores.toMutableMap()
        addPairScores(updatedPairs, itemLabels.take(8), weight)

        val updatedSources = current.sourceScores.toMutableMap()
        updatedSources[item.sourceId] = (updatedSources[item.sourceId] ?: 0.0) + weight

        val updatedSeen = appendToLru(current.seenItemIds, listOf(item.compositeId))

        return current.copy(
            labelScores = pruneScores(updatedLabels),
            pairScores = pruneScores(updatedPairs),
            sourceScores = pruneScores(updatedSources),
            seenItemIds = updatedSeen,
            totalInteractions = current.totalInteractions + 1
        )
    }

    fun recordLabelInteraction(
        current: UserTasteProfile,
        rawLabels: List<String>,
        weight: Double
    ): UserTasteProfile {
        if (weight <= 0.0 || rawLabels.isEmpty()) return current
        val cleaned = rawLabels.mapNotNull(::cleanLabel).distinct()
        if (cleaned.isEmpty()) return current

        val updatedLabels = current.labelScores.toMutableMap()
        for (label in cleaned) {
            updatedLabels[label] = (updatedLabels[label] ?: 0.0) + weight
        }

        val updatedPairs = current.pairScores.toMutableMap()
        addPairScores(updatedPairs, cleaned.take(8), weight)

        return current.copy(
            labelScores = pruneScores(updatedLabels),
            pairScores = pruneScores(updatedPairs),
            totalInteractions = current.totalInteractions + 1
        )
    }

    fun markShown(
        current: UserTasteProfile,
        compositeIds: Collection<String>
    ): UserTasteProfile {
        if (compositeIds.isEmpty()) return current
        val updatedSeen = appendToLru(current.seenItemIds, compositeIds)
        return current.copy(seenItemIds = updatedSeen)
    }

    fun buildTopCombinations(
        taste: UserTasteProfile,
        limit: Int = 6
    ): List<LabelComboSuggestion> {
        if (limit <= 0) return emptyList()

        val suggestionsByKey = linkedMapOf<Set<String>, LabelComboSuggestion>()

        // 1. Direct co-occurring pairs from pairScores
        taste.pairScores.entries
            .sortedByDescending { it.value }
            .forEach { (key, score) ->
                val parts = key.split(PAIR_SEPARATOR)
                if (parts.size == 2) {
                    val a = cleanLabel(parts[0])
                    val b = cleanLabel(parts[1])
                    if (a != null && b != null && a != b) {
                        val labels = listOf(a, b)
                        val labelSet = labels.toSet()
                        if (labelSet !in suggestionsByKey) {
                            suggestionsByKey[labelSet] = LabelComboSuggestion(
                                labels = labels,
                                displayTitle = formatComboTitle(labels),
                                searchQuery = labels.joinToString(" "),
                                score = score
                            )
                        }
                    }
                }
            }

        // 2. Top 2-label combinations synthesized from highest-scoring individual labels
        val topLabels = taste.labelScores.entries
            .sortedByDescending { it.value }
            .mapNotNull { entry -> cleanLabel(entry.key)?.let { it to entry.value } }
            .distinctBy { it.first }
            .take(8)

        for (i in topLabels.indices) {
            for (j in i + 1 until topLabels.size) {
                val (labelA, scoreA) = topLabels[i]
                val (labelB, scoreB) = topLabels[j]
                val labels = listOf(labelA, labelB)
                val labelSet = labels.toSet()
                if (labelSet !in suggestionsByKey) {
                    suggestionsByKey[labelSet] = LabelComboSuggestion(
                        labels = labels,
                        displayTitle = formatComboTitle(labels),
                        searchQuery = labels.joinToString(" "),
                        score = (scoreA + scoreB) * 0.65
                    )
                }
            }
        }

        // 3. One top 3-label combo when >= 3 labels exist with score >= 4.0
        val highConfidenceLabels = topLabels.filter { it.second >= 4.0 }.take(3)
        val tripleSuggestion = if (highConfidenceLabels.size == 3) {
            val labels = highConfidenceLabels.map { it.first }
            val avgScore = highConfidenceLabels.sumOf { it.second } * 0.55
            LabelComboSuggestion(
                labels = labels,
                displayTitle = formatComboTitle(labels),
                searchQuery = labels.joinToString(" "),
                score = avgScore
            )
        } else {
            null
        }

        val rankedPairs = suggestionsByKey.values
            .sortedByDescending { it.score }

        val finalCombos = if (tripleSuggestion != null && limit >= 2) {
            val head = rankedPairs.take(limit - 1)
            (head + tripleSuggestion).sortedByDescending { it.score }.take(limit)
        } else {
            rankedPairs.take(limit)
        }

        return finalCombos
    }

    fun rankFreshAndPersonalized(
        items: List<CatalogItem>,
        taste: UserTasteProfile,
        watchedCompositeIds: Set<String>,
        avoidRepeats: Boolean,
        excludeWatched: Boolean = false
    ): List<CatalogItem> {
        if (items.isEmpty()) return emptyList()

        val filtered = if (excludeWatched && avoidRepeats) {
            items.filterNot { it.compositeId in watchedCompositeIds }
        } else {
            items
        }
        if (filtered.isEmpty()) return emptyList()

        val seenSet = taste.seenItemIds.toHashSet()

        val scored = filtered.map { item ->
            val isFresh = item.compositeId !in seenSet && item.compositeId !in watchedCompositeIds
            val freshnessScore = if (isFresh) FRESHNESS_BONUS else 0.0

            val itemLabels = extractItemLabels(item)
            val labelAffinity = itemLabels.sumOf { label -> taste.labelScores[label] ?: 0.0 }
            val sourceAffinity = taste.sourceScores[item.sourceId] ?: 0.0
            val qualityBonus = qualityTierBonus(item.maxResolutionHeight)

            item to (freshnessScore + labelAffinity + sourceAffinity + qualityBonus)
        }.sortedByDescending { it.second }.map { it.first }

        if (!avoidRepeats) {
            return scored
        }

        val seenNormalizedTitles = HashSet<String>(scored.size)
        val dedupedByTitle = ArrayList<CatalogItem>(scored.size)
        for (item in scored) {
            val normTitle = normalizeTitleForDedup(item.title)
            if (normTitle.isEmpty() || seenNormalizedTitles.add(normTitle)) {
                dedupedByTitle.add(item)
            }
        }
        return dedupedByTitle
    }

    private fun addPairScores(
        pairScores: MutableMap<String, Double>,
        labels: List<String>,
        weight: Double
    ) {
        if (labels.size < 2) return
        for (i in labels.indices) {
            for (j in i + 1 until labels.size) {
                val a = labels[i]
                val b = labels[j]
                if (a == b) continue
                val key = if (a < b) "$a$PAIR_SEPARATOR$b" else "$b$PAIR_SEPARATOR$a"
                pairScores[key] = (pairScores[key] ?: 0.0) + weight
            }
        }
    }

    private fun appendToLru(
        existing: List<String>,
        incoming: Collection<String>
    ): List<String> {
        val lru = LinkedHashSet<String>(existing.size + incoming.size)
        lru.addAll(existing)
        for (id in incoming) {
            if (id.isNotBlank()) {
                lru.remove(id)
                lru.add(id)
            }
        }
        val list = lru.toList()
        return if (list.size > MAX_SEEN_ITEMS) list.takeLast(MAX_SEEN_ITEMS) else list
    }

    private fun pruneScores(scores: Map<String, Double>): Map<String, Double> {
        if (scores.size <= MAX_TRACKED_ENTRIES) return scores
        return scores.entries
            .sortedByDescending { it.value }
            .take(MAX_TRACKED_ENTRIES)
            .associate { it.key to it.value }
    }

    private fun qualityTierBonus(height: Int?): Double {
        val h = height ?: 0
        return when {
            h >= 2160 -> 40.0
            h >= 1440 -> 25.0
            h >= 1080 -> 15.0
            h >= 720 -> 5.0
            else -> 0.0
        }
    }

    private fun normalizeTitleForDedup(title: String): String {
        return title.lowercase(Locale.ROOT)
            .replace(TITLE_STRIP_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun formatComboTitle(labels: List<String>): String {
        return labels.joinToString(" + ") { formatDisplayWord(it) }
    }

    private fun formatDisplayWord(label: String): String {
        return label.split(' ').joinToString(" ") { word ->
            when (word.lowercase(Locale.ROOT)) {
                "4k", "hd", "uhd", "fhd", "vr", "pov" -> word.uppercase(Locale.ROOT)
                "2160p", "1440p", "1080p", "720p", "480p", "60fps" -> word.lowercase(Locale.ROOT)
                else -> word.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
        }
    }
}
