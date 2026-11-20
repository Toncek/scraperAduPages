package com.harbor.tv.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.CatalogSortMode
import com.harbor.tv.core.model.CategoryItem
import com.harbor.tv.core.model.LabelComboSuggestion
import com.harbor.tv.core.model.LiveStatus
import com.harbor.tv.core.model.UserProfile
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.TvActionButton
import com.harbor.tv.core.tvui.TvButtonTone
import com.harbor.tv.core.tvui.TvFocusableCard
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.TvTagChip
import com.harbor.tv.core.tvui.TvVideoCard
import com.harbor.tv.core.tvui.formatDuration
import com.harbor.tv.core.tvui.formatDurationMs
import com.harbor.tv.core.tvui.formatTagForDisplay
import com.harbor.tv.core.tvui.friendlySourceName
import com.harbor.tv.core.tvui.resolveQualityBadge
import com.harbor.tv.domain.catalog.CatalogRepository

private fun is4kCatalogItem(item: CatalogItem): Boolean {
    return (item.maxResolutionHeight ?: 0) >= 2160 ||
        item.maxQualityLabel?.contains("4K", ignoreCase = true) == true ||
        item.title.contains("4k", ignoreCase = true)
}

@Composable
fun HomeScreen(
    activeProfile: UserProfile,
    isPrivateSession: Boolean,
    offlineFixtureMode: Boolean,
    enabledSourceIds: List<String>,
    quickCategories: List<CategoryItem>,
    rowsBySource: Map<String, List<CatalogItem>>,
    featuredHomeItem: CatalogItem? = null,
    only4kFilter: Boolean = false,
    previewEnabled: Boolean = true,
    watchHistory: List<CatalogRepository.WatchHistoryRecord>,
    topLabelCombos: List<LabelComboSuggestion> = emptyList(),
    personalizedFreshItems: List<CatalogItem> = emptyList(),
    isLoading: Boolean,
    errorMessage: String?,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onFeaturedItemFocused: (CatalogItem) -> Unit = {},
    onToggleOnly4kFilter: () -> Unit = {},
    onQuickBrowse4k: () -> Unit = {},
    onSurpriseMe: () -> Unit = {},
    onApplyLabelCombo: (LabelComboSuggestion) -> Unit = {},
    onOpenVideo: (CatalogItem) -> Unit,
    onPlayVideo: (CatalogItem) -> Unit = onOpenVideo,
    onOpenSourceCategories: (String) -> Unit = {},
    onOpenCategory: (CategoryItem) -> Unit,
    onRefresh: () -> Unit,
    onOpenSources: () -> Unit,
    onQuickLoadDemo: () -> Unit,
    onEnableAllBundledSources: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val restoreRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester =
        restoreRequesters.getOrPut(key) { FocusRequester() }

    LaunchedEffect(lastFocusedKey, rowsBySource) {
        val target = lastFocusedKey ?: return@LaunchedEffect
        restoreRequesters[target]?.let { req ->
            runCatching { req.requestFocus() }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Discover",
                        style = MaterialTheme.typography.headlineMedium,
                        color = HarborColors.TextPrimary
                    )
                    if (activeProfile.name.isNotBlank()) {
                        Text(
                            text = activeProfile.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.TextMuted
                        )
                    }
                    if (isPrivateSession) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(HarborColors.PrivatePurple.copy(alpha = 0.22f))
                                .border(1.dp, HarborColors.PrivatePurple, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Private",
                                style = MaterialTheme.typography.labelMedium,
                                color = HarborColors.PrivatePurple,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (offlineFixtureMode) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(HarborColors.AccentTeal.copy(alpha = 0.16f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Demo",
                                style = MaterialTheme.typography.labelMedium,
                                color = HarborColors.AccentTeal
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvTagChip(
                        label = if (only4kFilter) "★ 4K Only: ON" else "★ 4K Only",
                        selected = only4kFilter,
                        onClick = onToggleOnly4kFilter
                    )
                    TvActionButton(
                        label = "Browse 4K",
                        tone = TvButtonTone.ACCENT,
                        onClick = onQuickBrowse4k
                    )
                    TvActionButton(
                        label = "Surprise Me",
                        tone = TvButtonTone.PRIMARY,
                        onClick = onSurpriseMe
                    )
                    TvActionButton(
                        label = "Refresh",
                        onClick = onRefresh
                    )
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            item {
                TvStatusBanner(
                    title = "Notice",
                    body = errorMessage,
                    accentColor = HarborColors.DangerRed
                )
            }
        }

        if (isLoading) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = HarborColors.FocusCyan)
                    Text(
                        text = "Loading videos...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HarborColors.TextSecondary
                    )
                }
            }
        }

        if (enabledSourceIds.isEmpty()) {
            item {
                TvStatusBanner(
                    title = "No Sources Enabled",
                    body = "Enable bundled sources or import a declarative definition in Sources to start browsing.",
                    accentColor = HarborColors.WarningAmber
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvActionButton(
                        label = "Enable All Bundled Sources",
                        tone = TvButtonTone.PRIMARY,
                        focusRequester = requesterFor("home_load_demo"),
                        onClick = onEnableAllBundledSources
                    )
                    TvActionButton(
                        label = "Load Example Library",
                        tone = TvButtonTone.ACCENT,
                        onClick = onQuickLoadDemo
                    )
                    TvActionButton(
                        label = "Open Sources",
                        onClick = onOpenSources
                    )
                }
            }
        }

        val displayedRows = if (only4kFilter) {
            rowsBySource.mapValues { (_, list) -> list.filter(::is4kCatalogItem) }
                .filterValues { it.isNotEmpty() }
        } else {
            rowsBySource
        }

        val spotlightItem = featuredHomeItem?.takeIf { !only4kFilter || is4kCatalogItem(it) }
            ?: displayedRows.values.firstOrNull()?.firstOrNull()

        if (spotlightItem != null) {
            item(key = "featured_spotlight") {
                FeaturedCinemaSpotlight(
                    item = spotlightItem,
                    playFocusRequester = requesterFor("home_spotlight"),
                    onFocused = { onFocusKeyChanged("home_spotlight") },
                    onPlayNow = { onPlayVideo(spotlightItem) },
                    onOpenDetails = { onOpenVideo(spotlightItem) }
                )
            }
        }

        // Quick Sites row
        if (enabledSourceIds.isNotEmpty()) {
            item(key = "quick_sites") {
                val orderedKnown = listOf(
                    "eporner-api-v2",
                    "porntrex-html",
                    "xhamster-html",
                    "pornhub-webmaster",
                    "xvideos-html",
                    "xnxx-html",
                    "redtube-api"
                )
                val siteChips = (orderedKnown.filter { it in enabledSourceIds } +
                    enabledSourceIds.filter { it !in orderedKnown }).distinct()

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "site_all") {
                        TvTagChip(
                            label = "All Sites",
                            selected = true,
                            onClick = { siteChips.firstOrNull()?.let(onOpenSourceCategories) }
                        )
                    }
                    items(siteChips, key = { "site_$it" }) { srcId ->
                        TvTagChip(
                            label = friendlySourceName(srcId),
                            onClick = { onOpenSourceCategories(srcId) }
                        )
                    }
                }
            }
        }

        if (!isPrivateSession && topLabelCombos.isNotEmpty()) {
            item(key = "for_you_combos") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "For You • Top Label Combos",
                        style = MaterialTheme.typography.titleMedium,
                        color = HarborColors.FocusCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(topLabelCombos, key = { "combo_${it.searchQuery}" }) { combo ->
                            TvTagChip(
                                label = combo.displayTitle,
                                onClick = { onApplyLabelCombo(combo) }
                            )
                        }
                    }
                }
            }
        }

        val freshRowItems = if (only4kFilter) {
            personalizedFreshItems.filter(::is4kCatalogItem)
        } else {
            personalizedFreshItems
        }
        if (freshRowItems.isNotEmpty()) {
            item(key = "row_picked_for_you_fresh") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Picked For You • Fresh",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.FocusCyan,
                        fontWeight = FontWeight.Bold
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(freshRowItems, key = { "fresh_${it.compositeId}" }) { item ->
                            val key = "fresh:${item.compositeId}"
                            TvVideoCard(
                                item = item,
                                focusRequester = requesterFor(key),
                                onFocused = {
                                    onFocusKeyChanged(key)
                                    onFeaturedItemFocused(item)
                                },
                                showSourceBadge = true,
                                previewEnabled = previewEnabled,
                                onClick = { onOpenVideo(item) }
                            )
                        }
                    }
                }
            }
        }

        // Aggregated 4K Ultra HD Picks row
        val uhdPicks = rowsBySource.values
            .flatten()
            .filter(::is4kCatalogItem)
            .distinctBy { it.compositeId }
            .take(12)
        if (uhdPicks.isNotEmpty()) {
            item(key = "row_4k_picks") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "4K Ultra HD Picks",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.Gold4K,
                        fontWeight = FontWeight.Bold
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(uhdPicks, key = { "4k_${it.compositeId}" }) { item ->
                            val key = "4k:${item.compositeId}"
                            TvVideoCard(
                                item = item,
                                focusRequester = requesterFor(key),
                                onFocused = {
                                    onFocusKeyChanged(key)
                                    onFeaturedItemFocused(item)
                                },
                                showSourceBadge = true,
                                previewEnabled = previewEnabled,
                                onClick = { onOpenVideo(item) }
                            )
                        }
                    }
                }
            }
        }

        if (!isPrivateSession && watchHistory.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Continue Watching",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.AccentTeal
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(watchHistory, key = { "hist:${it.item.compositeId}" }) { record ->
                            val key = "hist:${record.item.compositeId}"
                            val totalMs = (record.item.durationSeconds ?: 0) * 1000L
                            val progress = if (totalMs > 0L) {
                                (record.resumePositionMs.toFloat() / totalMs.toFloat()).coerceIn(0.03f, 1f)
                            } else {
                                0.25f
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TvVideoCard(
                                    item = record.item,
                                    focusRequester = requesterFor(key),
                                    onFocused = {
                                        onFocusKeyChanged(key)
                                        onFeaturedItemFocused(record.item)
                                    },
                                    showSourceBadge = true,
                                    progressFraction = progress,
                                    previewEnabled = previewEnabled,
                                    onClick = { onOpenVideo(record.item) }
                                )
                                Text(
                                    text = "Resume at ${formatDurationMs(record.resumePositionMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = HarborColors.AccentTeal
                                )
                            }
                        }
                    }
                }
            }
        }

        displayedRows.forEach { (sourceId, items) ->
            item(key = "row_$sourceId") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = friendlySourceName(sourceId),
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(items, key = { it.compositeId }) { item ->
                            val key = "home:${item.compositeId}"
                            TvVideoCard(
                                item = item,
                                focusRequester = requesterFor(key),
                                onFocused = {
                                    onFocusKeyChanged(key)
                                    onFeaturedItemFocused(item)
                                },
                                showSourceBadge = false,
                                previewEnabled = previewEnabled,
                                onClick = { onOpenVideo(item) }
                            )
                        }
                        item(key = "see_all_$sourceId") {
                            TvFocusableCard(
                                onClick = { onOpenSourceCategories(sourceId) },
                                modifier = Modifier
                                    .width(168.dp)
                                    .height(216.dp),
                                shape = RoundedCornerShape(14.dp),
                                containerColor = HarborColors.SurfaceElevated,
                                focusedScale = 1.04f
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "See All ->",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = HarborColors.FocusCyan,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = friendlySourceName(sourceId),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = HarborColors.TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (quickCategories.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Quick Categories",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(quickCategories, key = { "${it.sourceId}::${it.id}" }) { cat ->
                            val key = "cat:${cat.sourceId}:${cat.id}"
                            TvTagChip(
                                label = cat.title,
                                focusRequester = requesterFor(key),
                                onFocused = { onFocusKeyChanged(key) },
                                onClick = { onOpenCategory(cat) }
                            )
                        }
                    }
                }
            }
        }

        if (enabledSourceIds.isNotEmpty() && displayedRows.isEmpty() && !isLoading) {
            item {
                TvStatusBanner(
                    title = if (only4kFilter) "No 4K Videos in Current Rows" else "No Videos Available",
                    body = if (only4kFilter) {
                        "Turn off '4K Only' or press 'Browse 4K' to search all enabled sources."
                    } else {
                        "No videos matched your active profile filters."
                    },
                    accentColor = HarborColors.WarningAmber
                )
            }
        }
    }
}

@Composable
private fun FeaturedCinemaSpotlight(
    item: CatalogItem,
    playFocusRequester: FocusRequester,
    onFocused: () -> Unit,
    onPlayNow: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val thumbUrl = item.thumbnailReferences.firstOrNull()?.takeIf { it.isNotBlank() }
    val qualityBadge = resolveQualityBadge(
        maxResolutionHeight = item.maxResolutionHeight,
        maxQualityLabel = item.maxQualityLabel,
        tags = item.canonicalTags
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(244.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(HarborColors.SurfaceElevated)
            .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(18.dp))
    ) {
        // Right-side full-bleed artwork fading smoothly into left pane
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.58f)
        ) {
            if (thumbUrl != null) {
                val context = LocalContext.current
                val req = remember(thumbUrl) {
                    ImageRequest.Builder(context)
                        .data(thumbUrl)
                        .size(Size(640, 360))
                        .precision(Precision.INEXACT)
                        .allowRgb565(true)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                HarborColors.SurfaceElevated,
                                HarborColors.SurfaceElevated.copy(alpha = 0.65f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Left metadata + CTA pane
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.58f)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(HarborColors.FocusCyan.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = friendlySourceName(item.sourceId),
                        style = MaterialTheme.typography.labelMedium,
                        color = HarborColors.FocusCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (qualityBadge != null) {
                    val (qLabel, qColor) = qualityBadge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(qColor)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (qLabel == "4K") "4K UHD" else qLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.Background,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                when (item.liveStatus) {
                    LiveStatus.LIVE -> Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelMedium,
                        color = HarborColors.DangerRed,
                        fontWeight = FontWeight.Bold
                    )
                    LiveStatus.UPCOMING -> Text(
                        text = "UPCOMING",
                        style = MaterialTheme.typography.labelMedium,
                        color = HarborColors.WarningAmber
                    )
                    LiveStatus.ON_DEMAND -> item.durationSeconds?.let {
                        Text(
                            text = formatDuration(it),
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.AccentTeal
                        )
                    }
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                color = HarborColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val tagsPreview = (item.canonicalTags.ifEmpty { item.sourceTags })
                .map(::formatTagForDisplay)
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
                .joinToString(" • ")
            if (tagsPreview.isNotBlank()) {
                Text(
                    text = tagsPreview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvActionButton(
                    label = "Play Now",
                    tone = TvButtonTone.PRIMARY,
                    focusRequester = playFocusRequester,
                    onFocused = onFocused,
                    onClick = onPlayNow
                )
                TvActionButton(
                    label = "Details",
                    tone = TvButtonTone.SECONDARY,
                    onClick = onOpenDetails
                )
            }
        }
    }
}

@Composable
fun CategoriesScreen(
    enabledSourceIds: List<String>,
    selectedSourceId: String?,
    categories: List<CategoryItem>,
    selectedCategory: CategoryItem?,
    items: List<CatalogItem>,
    only4kFilter: Boolean = false,
    sortMode: CatalogSortMode = CatalogSortMode.DEFAULT,
    previewEnabled: Boolean = true,
    nextCursor: String?,
    pagesLoaded: Int,
    isLoading: Boolean,
    errorMessage: String?,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onToggleOnly4kFilter: () -> Unit = {},
    onSelectSortMode: (CatalogSortMode) -> Unit = {},
    onSelectSource: (String) -> Unit,
    onSelectCategory: (CategoryItem) -> Unit,
    onLoadNextPage: () -> Unit,
    onOpenVideo: (CatalogItem) -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier
) {
    val restoreRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester =
        restoreRequesters.getOrPut(key) { FocusRequester() }

    LaunchedEffect(lastFocusedKey, items) {
        val target = lastFocusedKey ?: return@LaunchedEffect
        restoreRequesters[target]?.let { req ->
            runCatching { req.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Browse Categories",
                style = MaterialTheme.typography.headlineMedium,
                color = HarborColors.TextPrimary
            )
            if (selectedCategory != null) {
                Text(
                    text = "${friendlySourceName(selectedCategory.sourceId)} • ${selectedCategory.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
        }

        if (enabledSourceIds.isEmpty()) {
            TvStatusBanner(
                title = "No Enabled Sources",
                body = "Enable a validated source definition in Sources to browse its categories.",
                accentColor = HarborColors.WarningAmber
            )
            TvActionButton(
                label = "Open Sources",
                tone = TvButtonTone.PRIMARY,
                onClick = onOpenSources
            )
            return
        }

        // Source selector row + 4K toggle + Sort chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                TvTagChip(
                    label = if (only4kFilter) "4K Only: ON" else "4K Only",
                    selected = only4kFilter,
                    onClick = onToggleOnly4kFilter
                )
            }
            item {
                TvTagChip(
                    label = "Sort: Default",
                    selected = sortMode == CatalogSortMode.DEFAULT,
                    onClick = { onSelectSortMode(CatalogSortMode.DEFAULT) }
                )
            }
            item {
                TvTagChip(
                    label = "Sort: 4K First",
                    selected = sortMode == CatalogSortMode.QUALITY_FIRST,
                    onClick = { onSelectSortMode(CatalogSortMode.QUALITY_FIRST) }
                )
            }
            item {
                TvTagChip(
                    label = "Sort: Longest",
                    selected = sortMode == CatalogSortMode.LONGEST_FIRST,
                    onClick = { onSelectSortMode(CatalogSortMode.LONGEST_FIRST) }
                )
            }
            items(enabledSourceIds) { sourceId ->
                TvTagChip(
                    label = friendlySourceName(sourceId),
                    selected = sourceId == selectedSourceId,
                    onClick = { onSelectSource(sourceId) }
                )
            }
        }

        // Category cards row
        if (categories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(categories, key = { "${it.sourceId}::${it.id}" }) { cat ->
                    val isSelected = selectedCategory?.id == cat.id && selectedCategory.sourceId == cat.sourceId
                    TvFocusableCard(
                        onClick = { onSelectCategory(cat) },
                        unfocusedBorderColor = if (isSelected) HarborColors.FocusCyan else HarborColors.BorderSubtle,
                        contentPadding = 12.dp
                    ) {
                        Text(
                            text = cat.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) HarborColors.FocusCyan else HarborColors.TextPrimary
                        )
                    }
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            TvStatusBanner(
                title = "Category Error",
                body = errorMessage,
                accentColor = HarborColors.DangerRed
            )
        }

        if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = HarborColors.FocusCyan)
                Text(
                    text = "Loading page...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
        }

        val displayedItems = if (only4kFilter) items.filter(::is4kCatalogItem) else items

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 264.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(displayedItems, key = { it.compositeId }) { item ->
                val key = "cat_item:${item.compositeId}"
                TvVideoCard(
                    item = item,
                    focusRequester = requesterFor(key),
                    onFocused = { onFocusKeyChanged(key) },
                    showSourceBadge = false,
                    previewEnabled = previewEnabled,
                    onClick = { onOpenVideo(item) }
                )
            }
        }

        if (nextCursor != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Page $pagesLoaded loaded",
                    style = MaterialTheme.typography.labelMedium,
                    color = HarborColors.TextSecondary
                )
                TvActionButton(
                    label = "Load More Videos",
                    tone = TvButtonTone.PRIMARY,
                    enabled = !isLoading,
                    onClick = onLoadNextPage
                )
            }
        }
    }
}

@Composable
fun FavoritesScreen(
    favorites: List<CatalogItem>,
    watchLater: List<CatalogItem> = emptyList(),
    watchHistory: List<CatalogRepository.WatchHistoryRecord> = emptyList(),
    isPrivateSession: Boolean = false,
    previewEnabled: Boolean = true,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onOpenVideo: (CatalogItem) -> Unit,
    onRemoveFavorite: (CatalogItem) -> Unit,
    onPlayWatchLaterQueue: () -> Unit = {},
    onRemoveWatchLater: (CatalogItem) -> Unit = {},
    onClearWatchLater: () -> Unit = {},
    onRemoveHistoryItem: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val restoreRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester =
        restoreRequesters.getOrPut(key) { FocusRequester() }

    LaunchedEffect(lastFocusedKey, favorites, watchLater, watchHistory, selectedTab) {
        val target = lastFocusedKey ?: return@LaunchedEffect
        restoreRequesters[target]?.let { req ->
            runCatching { req.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when (selectedTab) {
                        0 -> "Saved Favorites"
                        1 -> "Watch Later Queue"
                        else -> "Watch History"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = HarborColors.TextPrimary
                )
                Text(
                    text = when (selectedTab) {
                        0 -> "Saved videos for this profile, filtered automatically by your preferences."
                        1 -> "Queued videos ready for back-to-back living-room playback."
                        else -> "Recently watched videos with saved resume positions."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvTagChip(
                    label = "Saved Favorites (${favorites.size})",
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                TvTagChip(
                    label = "Watch Later (${watchLater.size})",
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                TvTagChip(
                    label = "Watch History (${watchHistory.size})",
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }

        when (selectedTab) {
            0 -> {
                if (favorites.isEmpty()) {
                    TvStatusBanner(
                        title = "No Saved Favorites Yet",
                        body = "Open any video and choose Favorite to save it here.",
                        accentColor = HarborColors.FocusCyan
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 264.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(favorites, key = { it.compositeId }) { item ->
                            val key = "fav:${item.compositeId}"
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TvVideoCard(
                                    item = item,
                                    focusRequester = requesterFor(key),
                                    onFocused = { onFocusKeyChanged(key) },
                                    previewEnabled = previewEnabled,
                                    onClick = { onOpenVideo(item) }
                                )
                                TvActionButton(
                                    label = "Remove",
                                    tone = TvButtonTone.DANGER,
                                    onClick = { onRemoveFavorite(item) },
                                    modifier = Modifier.width(276.dp)
                                )
                            }
                        }
                    }
                }
            }

            1 -> {
                if (watchLater.isEmpty()) {
                    TvStatusBanner(
                        title = "Watch Later Queue Empty",
                        body = "Open any video and tap + Watch Later to queue it for continuous playback.",
                        accentColor = HarborColors.FocusCyan
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton(
                            label = "Play All (${watchLater.size})",
                            tone = TvButtonTone.PRIMARY,
                            onClick = onPlayWatchLaterQueue
                        )
                        TvActionButton(
                            label = "Clear Queue",
                            tone = TvButtonTone.DANGER,
                            onClick = onClearWatchLater
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 264.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(watchLater, key = { "wl_${it.compositeId}" }) { item ->
                            val key = "wl:${item.compositeId}"
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TvVideoCard(
                                    item = item,
                                    focusRequester = requesterFor(key),
                                    onFocused = { onFocusKeyChanged(key) },
                                    previewEnabled = previewEnabled,
                                    onClick = { onOpenVideo(item) }
                                )
                                TvActionButton(
                                    label = "Remove",
                                    tone = TvButtonTone.DANGER,
                                    onClick = { onRemoveWatchLater(item) },
                                    modifier = Modifier.width(276.dp)
                                )
                            }
                        }
                    }
                }
            }

            else -> {
                if (watchHistory.isEmpty()) {
                    TvStatusBanner(
                        title = if (isPrivateSession) "Private Session Active" else "No Watch History Yet",
                        body = if (isPrivateSession) {
                            "Watch history is paused during Private Session mode."
                        } else {
                            "Videos you watch will appear here so you can resume where you left off."
                        },
                        accentColor = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.FocusCyan
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 264.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(watchHistory, key = { "hist_${it.item.compositeId}" }) { record ->
                            val item = record.item
                            val key = "hist:${item.compositeId}"
                            val durationMs = (item.durationSeconds ?: 0) * 1000L
                            val progress = if (durationMs > 0L && record.resumePositionMs > 0L) {
                                (record.resumePositionMs.toFloat() / durationMs.toFloat()).coerceIn(0.02f, 1f)
                            } else {
                                null
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TvVideoCard(
                                    item = item,
                                    focusRequester = requesterFor(key),
                                    onFocused = { onFocusKeyChanged(key) },
                                    progressFraction = progress,
                                    previewEnabled = previewEnabled,
                                    onClick = { onOpenVideo(item) }
                                )
                                Row(
                                    modifier = Modifier.width(276.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (record.resumePositionMs > 0L) {
                                            "Resume ${formatDurationMs(record.resumePositionMs)}"
                                        } else {
                                            "Watched"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = HarborColors.AccentTeal
                                    )
                                    TvActionButton(
                                        label = "Remove",
                                        tone = TvButtonTone.DANGER,
                                        onClick = { onRemoveHistoryItem(item.compositeId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
