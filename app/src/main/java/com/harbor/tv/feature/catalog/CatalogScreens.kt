package com.harbor.tv.feature.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.CategoryItem
import com.harbor.tv.core.model.UserProfile
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.TvActionButton
import com.harbor.tv.core.tvui.TvButtonTone
import com.harbor.tv.core.tvui.TvFocusableCard
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.TvTagChip
import com.harbor.tv.core.tvui.TvVideoCard
import com.harbor.tv.core.tvui.formatDurationMs
import com.harbor.tv.domain.catalog.CatalogRepository

@Composable
fun HomeScreen(
    activeProfile: UserProfile,
    isPrivateSession: Boolean,
    offlineFixtureMode: Boolean,
    enabledSourceIds: List<String>,
    quickCategories: List<CategoryItem>,
    rowsBySource: Map<String, List<CatalogItem>>,
    watchHistory: List<CatalogRepository.WatchHistoryRecord>,
    isLoading: Boolean,
    errorMessage: String?,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onOpenVideo: (CatalogItem) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Home Catalog",
                        style = MaterialTheme.typography.headlineMedium,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = buildString {
                            append(if (isPrivateSession) "Private Session Active" else "Profile: ${activeProfile.name}")
                            append(" • Metadata: ${activeProfile.unknownMetadataMode.name}")
                            append(" • ${if (offlineFixtureMode) "Offline Fixture Mode" else "Live Network Mode"}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.TextSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvActionButton(
                        label = "Refresh",
                        onClick = onRefresh
                    )
                    TvActionButton(
                        label = "Enable All Bundled",
                        tone = TvButtonTone.ACCENT,
                        onClick = onEnableAllBundledSources
                    )
                    TvActionButton(
                        label = "Manage Sources",
                        tone = TvButtonTone.PRIMARY,
                        onClick = onOpenSources
                    )
                }
            }
        }

        if (isPrivateSession) {
            item {
                TvStatusBanner(
                    title = "Private Session Active",
                    body = "Watch history, search history, resume positions, and disk media cache are not being persisted.",
                    accentColor = HarborColors.PrivatePurple
                )
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            item {
                TvStatusBanner(
                    title = "Catalog Notice",
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
                        text = "Loading & filtering catalog...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HarborColors.TextSecondary
                    )
                }
            }
        }

        if (enabledSourceIds.isEmpty()) {
            item {
                TvStatusBanner(
                    title = "No Sources Enabled Yet",
                    body = "Harbor ships with no enabled sources by default. Import and approve a declarative YAML/JSON definition—or load the bundled Example Video Library fixture source—to browse.",
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
                        label = "Load Example Library for Approval",
                        tone = TvButtonTone.ACCENT,
                        onClick = onQuickLoadDemo
                    )
                    TvActionButton(
                        label = "Browse Sources & 27-Platform Reference",
                        onClick = onOpenSources
                    )
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
                                label = "${cat.title} (${cat.sourceId})",
                                focusRequester = requesterFor(key),
                                onFocused = { onFocusKeyChanged(key) },
                                onClick = { onOpenCategory(cat) }
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
                        text = "Recently Watched",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.AccentTeal
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(watchHistory, key = { "hist:${it.item.compositeId}" }) { record ->
                            val key = "hist:${record.item.compositeId}"
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TvVideoCard(
                                    item = record.item,
                                    focusRequester = requesterFor(key),
                                    onFocused = { onFocusKeyChanged(key) },
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

        rowsBySource.forEach { (sourceId, items) ->
            item(key = "row_$sourceId") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Source: $sourceId (${items.size} eligible)",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(items, key = { it.compositeId }) { item ->
                            val key = "home:${item.compositeId}"
                            TvVideoCard(
                                item = item,
                                focusRequester = requesterFor(key),
                                onFocused = { onFocusKeyChanged(key) },
                                onClick = { onOpenVideo(item) }
                            )
                        }
                    }
                }
            }
        }

        if (enabledSourceIds.isNotEmpty() && rowsBySource.isEmpty() && !isLoading) {
            item {
                TvStatusBanner(
                    title = "No Eligible Videos",
                    body = "Your enabled sources returned no videos matching the active profile's preferences or Strict metadata rules.",
                    accentColor = HarborColors.WarningAmber
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
    nextCursor: String?,
    pagesLoaded: Int,
    isLoading: Boolean,
    errorMessage: String?,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
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
                text = "Categories",
                style = MaterialTheme.typography.headlineMedium,
                color = HarborColors.TextPrimary
            )
            if (selectedCategory != null) {
                Text(
                    text = "${selectedCategory.title} • Pages loaded: $pagesLoaded",
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

        // Source selector row
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(enabledSourceIds) { sourceId ->
                TvTagChip(
                    label = sourceId,
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

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 250.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(items, key = { it.compositeId }) { item ->
                val key = "cat_item:${item.compositeId}"
                TvVideoCard(
                    item = item,
                    focusRequester = requesterFor(key),
                    onFocused = { onFocusKeyChanged(key) },
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
                    text = "Opaque pagination cursor ready",
                    style = MaterialTheme.typography.labelMedium,
                    color = HarborColors.TextSecondary
                )
                TvActionButton(
                    label = "Load Next Page",
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
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onOpenVideo: (CatalogItem) -> Unit,
    onRemoveFavorite: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val restoreRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester =
        restoreRequesters.getOrPut(key) { FocusRequester() }

    LaunchedEffect(lastFocusedKey, favorites) {
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
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.headlineMedium,
            color = HarborColors.TextPrimary
        )
        Text(
            text = "Filtered dynamically by your active profile's PreferenceFilterEngine rules (exclusions and Strict mode apply here too).",
            style = MaterialTheme.typography.bodyMedium,
            color = HarborColors.TextSecondary
        )

        if (favorites.isEmpty()) {
            TvStatusBanner(
                title = "No Eligible Favorites",
                body = "Add favorites from Video Details. Any favorite matching an excluded tag, disabled source, or Strict metadata filter is hidden automatically.",
                accentColor = HarborColors.FocusCyan
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
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
                            onClick = { onOpenVideo(item) }
                        )
                        TvActionButton(
                            label = "Remove Favorite",
                            tone = TvButtonTone.DANGER,
                            onClick = { onRemoveFavorite(item) },
                            modifier = Modifier.width(268.dp)
                        )
                    }
                }
            }
        }
    }
}
