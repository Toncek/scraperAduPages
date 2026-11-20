package com.harbor.tv.domain.catalog

import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.CategoryItem
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.model.MetadataCompleteness
import com.harbor.tv.core.model.PaginatedListingResult
import com.harbor.tv.core.model.PreferenceConfig
import com.harbor.tv.core.model.UnknownMetadataMode
import com.harbor.tv.core.model.UserProfile
import com.harbor.tv.core.network.HttpTransport
import com.harbor.tv.core.storage.CatalogCacheDao
import com.harbor.tv.core.storage.CatalogCacheEntity
import com.harbor.tv.core.storage.CatalogItemJsonCodec
import com.harbor.tv.core.storage.FavoriteDao
import com.harbor.tv.core.storage.FavoriteEntity
import com.harbor.tv.core.storage.HarborSettingsStore
import com.harbor.tv.core.storage.WatchHistoryDao
import com.harbor.tv.core.storage.WatchHistoryEntity
import com.harbor.tv.domain.preferences.PreferenceFilterEngine
import com.harbor.tv.domain.sources.SourceRegistry
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.extract.UnifiedOperationExtractor
import com.harbor.tv.engine.policy.RequestPolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates source-backed catalog browsing (categories, cycle-safe paginated listings,
 * multi-source search, video details), local caching with definition-version invalidation,
 * favorites, and private-session-aware watch history while enforcing [PreferenceFilterEngine]
 * uniformly across every entry point.
 */
class CatalogRepository(
    private val sourceRegistry: SourceRegistry,
    private val transport: HttpTransport,
    private val catalogCacheDao: CatalogCacheDao? = null,
    private val favoriteDao: FavoriteDao? = null,
    private val watchHistoryDao: WatchHistoryDao? = null,
    private val settingsStore: HarborSettingsStore? = null,
    private val normalizer: CatalogNormalizer = CatalogNormalizer(),
    private val filterEngine: PreferenceFilterEngine = PreferenceFilterEngine(),
    private val extractor: UnifiedOperationExtractor = UnifiedOperationExtractor(),
    private val policyEngine: RequestPolicyEngine = RequestPolicyEngine(),
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_CACHE_TTL_MS = 30L * 60L * 1000L // 30 minutes
        const val DEFAULT_MAX_PAGES = 20
        const val STRICT_DETAIL_ENRICH_BUDGET = 5
    }

    /**
     * Tracks visited pagination URLs and page budget per browsing session to prevent cycles
     * and unbounded crawling (Master Specification Section 12.1).
     */
    class PaginationSession(
        val sourceId: String,
        val maxPages: Int = DEFAULT_MAX_PAGES
    ) {
        private val visitedUrls = linkedSetOf<String>()
        var pagesLoaded: Int = 0
            private set

        fun canVisit(url: String, effectiveMaxPages: Int = maxPages): Boolean {
            if (url.isBlank()) return false
            if (pagesLoaded >= effectiveMaxPages) return false
            return url !in visitedUrls
        }

        fun hasVisited(url: String): Boolean = url in visitedUrls

        fun markVisited(url: String) {
            if (visitedUrls.add(url)) {
                pagesLoaded++
            }
        }
    }

    data class MultiSourceSearchResult(
        val items: List<CatalogItem>,
        val queriedSourceIds: List<String>,
        val sourceErrors: Map<String, HarborException> = emptyMap()
    )

    data class WatchHistoryRecord(
        val item: CatalogItem,
        val resumePositionMs: Long,
        val watchedAt: Long
    )

    suspend fun getCategories(
        sourceId: String,
        profile: UserProfile
    ): List<CategoryItem> = withContext(Dispatchers.IO) {
        val definition = requireEnabledDefinition(sourceId)
        val op = definition.operations.categories
        if (!definition.source.capabilities.categories || op == null) {
            throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Source '$sourceId' does not support categories",
                sourceId = sourceId,
                operation = "categories"
            )
        }

        val userAllowLan = profile.allowLanSources && definition.network.allowLan
        val requestUrl = policyEngine.buildInterpolatedUrl(
            requestDef = op.request,
            definition = definition,
            variables = emptyMap(),
            allowedVariables = setOf("source.baseUrl")
        )
        val response = transport.executeGet(
            url = requestUrl,
            definition = definition,
            purpose = HostPurpose.CATALOG,
            extraHeaders = op.request.headers,
            userAllowLan = userAllowLan
        )
        val extracted = extractor.extractCategories(definition, response.body, response.finalUrl)
        extracted.categories.map { cat ->
            normalizer.normalizeCategory(
                rawFields = mapOf("id" to cat.id, "title" to cat.title, "url" to cat.url),
                definition = definition
            )
        }
    }

    suspend fun loadListingPage(
        sourceId: String,
        pageUrl: String,
        profile: UserProfile,
        preferences: PreferenceConfig,
        session: PaginationSession = PaginationSession(sourceId)
    ): PaginatedListingResult = withContext(Dispatchers.IO) {
        if (session.sourceId != sourceId) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Pagination cursor/session for '${session.sourceId}' cannot be mixed with '$sourceId'",
                sourceId = sourceId,
                operation = "listing"
            )
        }
        val definition = requireEnabledDefinition(sourceId)
        if (sourceId in preferences.excludedSources) {
            return@withContext PaginatedListingResult(items = emptyList(), nextCursor = null)
        }
        val op = definition.operations.listing
            ?: throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Source '$sourceId' does not define 'operations.listing'",
                sourceId = sourceId,
                operation = "listing"
            )

        val effectiveMaxPages = minOf(session.maxPages, op.pagination?.maxPages ?: DEFAULT_MAX_PAGES)
        if (!session.canVisit(pageUrl, effectiveMaxPages)) {
            return@withContext PaginatedListingResult(items = emptyList(), nextCursor = null)
        }

        val userAllowLan = profile.allowLanSources && definition.network.allowLan
        val requestUrl = policyEngine.buildInterpolatedUrl(
            requestDef = op.request,
            definition = definition,
            variables = mapOf("input.url" to pageUrl),
            allowedVariables = setOf("source.baseUrl", "input.url")
        )

        val response = transport.executeGet(
            url = requestUrl,
            definition = definition,
            purpose = HostPurpose.CATALOG,
            extraHeaders = op.request.headers,
            userAllowLan = userAllowLan
        )
        session.markVisited(pageUrl)
        if (response.finalUrl != pageUrl) {
            // Track canonical final URL as well so redirect loops cannot re-enter the session
            session.markVisited(response.finalUrl)
        }

        val extracted = extractor.extractListing(definition, "listing", response.body, response.finalUrl)
        var normalizedItems = normalizer.normalizeCatalogItems(
            items = extracted.listing.items,
            definition = definition,
            userAllowLan = userAllowLan
        )

        if (preferences.unknownMetadataMode == UnknownMetadataMode.STRICT &&
            definition.source.capabilities.details &&
            definition.operations.details != null
        ) {
            normalizedItems = enrichStrictPartialItems(
                items = normalizedItems,
                definition = definition,
                userAllowLan = userAllowLan,
                budget = STRICT_DETAIL_ENRICH_BUDGET
            )
        }

        val enabledIds = sourceRegistry.getEnabledSourceIds()
        val filtered = filterEngine.filterAndRank(
            items = normalizedItems,
            preferences = preferences,
            enabledSourceIds = enabledIds
        )

        if (!isPrivateSession(profile)) {
            cacheItems(normalizedItems, definition.source.version)
        }

        val validatedNextCursor = validateNextCursor(
            rawNext = extracted.listing.nextCursor,
            definition = definition,
            session = session,
            effectiveMaxPages = effectiveMaxPages,
            userAllowLan = userAllowLan
        )

        PaginatedListingResult(
            items = filtered,
            nextCursor = validatedNextCursor,
            totalExtracted = extracted.listing.totalExtracted,
            totalRejected = extracted.listing.totalRejected + (normalizedItems.size - filtered.size)
        )
    }

    suspend fun search(
        query: String,
        profile: UserProfile,
        preferences: PreferenceConfig,
        sourceIds: Set<String>? = null
    ): MultiSourceSearchResult = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext MultiSourceSearchResult(items = emptyList(), queriedSourceIds = emptyList())
        }

        val enabledDefs = sourceRegistry.getEnabledDefinitions()
            .filter { def ->
                val id = def.source.id
                (sourceIds == null || id in sourceIds) &&
                    id !in preferences.excludedSources &&
                    def.source.capabilities.search &&
                    def.operations.search != null
            }

        val allNormalized = Collections.synchronizedList(mutableListOf<CatalogItem>())
        val queriedIds = enabledDefs.map { it.source.id }
        val errors = ConcurrentHashMap<String, HarborException>()
        val privateSession = isPrivateSession(profile)

        coroutineScope {
            enabledDefs.map { definition ->
                async {
                    val sourceId = definition.source.id
                    val op = definition.operations.search ?: return@async
                    val userAllowLan = profile.allowLanSources && definition.network.allowLan

                    try {
                        withTimeout(15_000L) {
                            val requestUrl = policyEngine.buildInterpolatedUrl(
                                requestDef = op.request,
                                definition = definition,
                                variables = mapOf("input.query" to trimmedQuery),
                                allowedVariables = setOf("source.baseUrl", "input.query")
                            )
                            val response = transport.executeGet(
                                url = requestUrl,
                                definition = definition,
                                purpose = HostPurpose.CATALOG,
                                extraHeaders = op.request.headers,
                                userAllowLan = userAllowLan
                            )
                            val extracted = extractor.extractListing(definition, "search", response.body, response.finalUrl)
                            val normalized = normalizer.normalizeCatalogItems(
                                items = extracted.listing.items,
                                definition = definition,
                                userAllowLan = userAllowLan
                            )
                            allNormalized.addAll(normalized)
                            if (!privateSession) {
                                cacheItems(normalized, definition.source.version)
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        errors[sourceId] = HarborException(
                            HarborErrorCode.NETWORK_UNAVAILABLE,
                            "Search timed out for '$sourceId'",
                            sourceId = sourceId,
                            operation = "search",
                            isRetryable = true,
                            cause = e
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: HarborException) {
                        errors[sourceId] = e
                    } catch (e: Exception) {
                        errors[sourceId] = HarborException(
                            HarborErrorCode.NETWORK_UNAVAILABLE,
                            "Search failed for '$sourceId': ${e.message}",
                            sourceId = sourceId,
                            operation = "search",
                            cause = e
                        )
                    }
                }
            }.awaitAll()
        }

        val deduplicated = normalizer.deduplicateItems(allNormalized.toList())
        val enabledIds = sourceRegistry.getEnabledSourceIds()
        val filtered = filterEngine.filterAndRank(
            items = deduplicated,
            preferences = preferences,
            enabledSourceIds = enabledIds,
            searchQuery = trimmedQuery
        )

        MultiSourceSearchResult(
            items = filtered,
            queriedSourceIds = queriedIds,
            sourceErrors = errors.toMap()
        )
    }

    suspend fun getVideoDetails(
        item: CatalogItem,
        profile: UserProfile,
        preferences: PreferenceConfig
    ): CatalogItem = withContext(Dispatchers.IO) {
        val definition = requireEnabledDefinition(item.sourceId)
        val enabledIds = sourceRegistry.getEnabledSourceIds()
        val userAllowLan = profile.allowLanSources && definition.network.allowLan

        // If item is already excluded by source, restrictions, or hard tag exclusions before detail enrichment,
        // refuse immediately without making network requests.
        if (item.sourceId !in enabledIds || item.sourceId in preferences.excludedSources) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Source '${item.sourceId}' is not authorized for this profile",
                sourceId = item.sourceId,
                operation = "details"
            )
        }

        val detailsOp = definition.operations.details
        val mergedItem = if (definition.source.capabilities.details && detailsOp != null) {
            fetchAndMergeDetails(item, definition, userAllowLan)
        } else {
            normalizer.normalizeCatalogItem(item, definition, userAllowLan)
        }

        if (!filterEngine.isItemEligible(mergedItem, preferences, enabledIds)) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Item '${mergedItem.compositeId}' is excluded by profile preferences",
                sourceId = mergedItem.sourceId,
                operation = "details"
            )
        }

        if (!isPrivateSession(profile)) {
            cacheItems(listOf(mergedItem), definition.source.version)
        }
        mergedItem
    }

    suspend fun getCachedCatalogItems(
        sourceId: String,
        preferences: PreferenceConfig
    ): List<CatalogItem> = withContext(Dispatchers.IO) {
        val dao = catalogCacheDao ?: return@withContext emptyList()
        val definition = sourceRegistry.getEnabledDefinition(sourceId) ?: return@withContext emptyList()
        val now = clockMs()
        dao.deleteExpired(now)
        val cached = dao.getValidForSource(sourceId, definition.source.version, now)
            .mapNotNull { CatalogItemJsonCodec.decode(it.itemJson) }
        val enabledIds = sourceRegistry.getEnabledSourceIds()
        filterEngine.filterAndRank(
            items = normalizer.deduplicateItems(cached),
            preferences = preferences,
            enabledSourceIds = enabledIds
        )
    }

    /**
     * Hydrates valid cached catalog items per enabled source on [Dispatchers.IO] for instant
     * cold-start Home rendering while still enforcing active [PreferenceFilterEngine] exclusions.
     */
    suspend fun getCachedHomeRows(
        sourceIds: List<String>,
        preferences: PreferenceConfig
    ): Map<String, List<CatalogItem>> = withContext(Dispatchers.IO) {
        val dao = catalogCacheDao ?: return@withContext emptyMap()
        if (sourceIds.isEmpty()) return@withContext emptyMap()

        val now = clockMs()
        dao.deleteExpired(now)
        val enabledIds = sourceRegistry.getEnabledSourceIds()
        val result = linkedMapOf<String, List<CatalogItem>>()

        for (sourceId in sourceIds) {
            if (sourceId !in enabledIds || sourceId in preferences.excludedSources) continue
            val definition = sourceRegistry.getEnabledDefinition(sourceId) ?: continue
            val cached = dao.getValidForSource(sourceId, definition.source.version, now)
                .mapNotNull { CatalogItemJsonCodec.decode(it.itemJson) }
            if (cached.isEmpty()) continue
            val filtered = filterEngine.filterAndRank(
                items = normalizer.deduplicateItems(cached),
                preferences = preferences,
                enabledSourceIds = enabledIds
            )
            if (filtered.isNotEmpty()) {
                result[sourceId] = filtered
            }
        }
        result
    }

    suspend fun getFavorites(
        profileId: String,
        preferences: PreferenceConfig
    ): List<CatalogItem> {
        val dao = favoriteDao ?: return emptyList()
        val enabledIds = sourceRegistry.getEnabledSourceIds()
        val rawItems = dao.getForProfile(profileId).mapNotNull { entity ->
            CatalogItemJsonCodec.decode(entity.itemJson)
        }
        return filterEngine.filterAndRank(
            items = rawItems,
            preferences = preferences,
            enabledSourceIds = enabledIds
        )
    }

    suspend fun toggleFavorite(
        profileId: String,
        item: CatalogItem
    ): Boolean {
        val dao = favoriteDao ?: return false
        val compositeId = item.compositeId
        return if (dao.isFavorite(profileId, compositeId)) {
            dao.delete(profileId, compositeId)
            false
        } else {
            dao.upsert(
                FavoriteEntity(
                    profileId = profileId,
                    compositeId = compositeId,
                    sourceId = item.sourceId,
                    itemJson = CatalogItemJsonCodec.encode(item),
                    addedAt = clockMs()
                )
            )
            true
        }
    }

    suspend fun isFavorite(profileId: String, compositeId: String): Boolean {
        return favoriteDao?.isFavorite(profileId, compositeId) ?: false
    }

    suspend fun getWatchHistory(
        profileId: String,
        preferences: PreferenceConfig
    ): List<WatchHistoryRecord> {
        val dao = watchHistoryDao ?: return emptyList()
        val enabledIds = sourceRegistry.getEnabledSourceIds()
        return dao.getForProfile(profileId).mapNotNull { entity ->
            val decoded = CatalogItemJsonCodec.decode(entity.itemJson) ?: return@mapNotNull null
            if (!filterEngine.isItemEligible(decoded, preferences, enabledIds)) {
                null
            } else {
                WatchHistoryRecord(
                    item = decoded,
                    resumePositionMs = entity.resumePositionMs,
                    watchedAt = entity.watchedAt
                )
            }
        }
    }

    /**
     * Persists resume position and watch history ONLY when private session mode is OFF
     * and [UserProfile.historyEnabled] is true (Master Specification Section 19.2).
     */
    suspend fun recordWatchProgress(
        profile: UserProfile,
        item: CatalogItem,
        resumePositionMs: Long
    ) {
        if (isPrivateSession(profile) || !profile.historyEnabled) {
            return
        }
        val dao = watchHistoryDao ?: return
        val sanitizedPosition = resumePositionMs.coerceAtLeast(0L)
        dao.upsert(
            WatchHistoryEntity(
                profileId = profile.id,
                compositeId = item.compositeId,
                sourceId = item.sourceId,
                itemJson = CatalogItemJsonCodec.encode(item),
                resumePositionMs = sanitizedPosition,
                watchedAt = clockMs()
            )
        )
    }

    suspend fun clearWatchHistory(profileId: String) {
        watchHistoryDao?.clearForProfile(profileId)
    }

    private suspend fun fetchAndMergeDetails(
        item: CatalogItem,
        definition: SourceDefinition,
        userAllowLan: Boolean
    ): CatalogItem {
        val op = definition.operations.details ?: return item
        val requestUrl = policyEngine.buildInterpolatedUrl(
            requestDef = op.request,
            definition = definition,
            variables = mapOf("input.detailUrl" to item.sourcePageUrl),
            allowedVariables = setOf("source.baseUrl", "input.detailUrl")
        )
        val response = transport.executeGet(
            url = requestUrl,
            definition = definition,
            purpose = HostPurpose.CATALOG,
            extraHeaders = op.request.headers,
            userAllowLan = userAllowLan
        )
        val output = extractor.extractDetails(definition, response.body, response.finalUrl)
        val record = output.record

        val mergedTitle = record.firstOrNull("title")?.takeIf { it.isNotBlank() } ?: item.title
        val mergedDescription = record.firstOrNull("description")?.takeIf { it.isNotBlank() } ?: item.description
        val mergedDuration = record.firstOrNull("durationSeconds")?.toIntOrNull() ?: item.durationSeconds
        val mergedSourceTags = (item.sourceTags + record.list("sourceTags"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val mergedLanguages = (item.languages + record.list("languages"))
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
        val mergedThumbs = (item.thumbnailReferences + record.list("thumbnailUrl"))
            .filter { it.isNotBlank() }
            .distinct()

        return normalizer.normalizeCatalogItem(
            item.copy(
                title = mergedTitle,
                description = mergedDescription,
                durationSeconds = mergedDuration,
                sourceTags = mergedSourceTags,
                languages = mergedLanguages,
                contentRating = record.firstOrNull("contentRating") ?: item.contentRating,
                thumbnailReferences = mergedThumbs,
                metadataProvenance = "details",
                fetchedAt = clockMs()
            ),
            definition = definition,
            userAllowLan = userAllowLan
        )
    }

    private suspend fun enrichStrictPartialItems(
        items: List<CatalogItem>,
        definition: SourceDefinition,
        userAllowLan: Boolean,
        budget: Int
    ): List<CatalogItem> {
        var remainingBudget = budget
        return items.map { item ->
            if (item.metadataCompleteness != MetadataCompleteness.COMPLETE && remainingBudget > 0) {
                remainingBudget--
                runCatching { fetchAndMergeDetails(item, definition, userAllowLan) }
                    .getOrDefault(item)
            } else {
                item
            }
        }
    }

    private fun validateNextCursor(
        rawNext: String?,
        definition: SourceDefinition,
        session: PaginationSession,
        effectiveMaxPages: Int,
        userAllowLan: Boolean
    ): String? {
        val candidate = rawNext?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!session.canVisit(candidate, effectiveMaxPages)) {
            return null
        }
        val isValid = runCatching {
            policyEngine.validateUrl(
                url = candidate,
                definition = definition,
                purpose = HostPurpose.CATALOG,
                userAllowLan = userAllowLan,
                resolveDns = false
            )
        }.isSuccess
        return if (isValid) candidate else null
    }

    private suspend fun cacheItems(items: List<CatalogItem>, definitionVersion: String) {
        val dao = catalogCacheDao ?: return
        if (items.isEmpty()) return
        val now = clockMs()
        val expiresAt = now + cacheTtlMs
        val entities = items.map { item ->
            CatalogCacheEntity(
                compositeId = item.compositeId,
                sourceId = item.sourceId,
                definitionVersion = definitionVersion,
                itemJson = CatalogItemJsonCodec.encode(item),
                fetchedAt = now,
                expiresAt = expiresAt
            )
        }
        dao.upsertAll(entities)
    }

    private suspend fun requireEnabledDefinition(sourceId: String): SourceDefinition {
        return sourceRegistry.getEnabledDefinition(sourceId)
            ?: throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Source '$sourceId' is not registered and enabled",
                sourceId = sourceId
            )
    }

    private suspend fun isPrivateSession(profile: UserProfile): Boolean {
        return profile.isPrivateSession || (settingsStore?.isPrivateSession() == true)
    }
}
