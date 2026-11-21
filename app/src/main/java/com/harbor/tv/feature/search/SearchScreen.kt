package com.harbor.tv.feature.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.CatalogSortMode
import com.harbor.tv.core.model.LabelComboSuggestion
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.TvActionButton
import com.harbor.tv.core.tvui.TvButtonTone
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.TvTagChip
import com.harbor.tv.core.tvui.TvVideoCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    query: String,
    enabledSourceIds: List<String>,
    selectedSourceFilterId: String?,
    results: List<CatalogItem>,
    only4kFilter: Boolean = false,
    sortMode: CatalogSortMode = CatalogSortMode.DEFAULT,
    previewEnabled: Boolean = true,
    topLabelCombos: List<LabelComboSuggestion> = emptyList(),
    recentSearches: List<String> = emptyList(),
    sourceErrors: Map<String, String>,
    isLoading: Boolean,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onToggleOnly4kFilter: () -> Unit = {},
    onSelectSortMode: (CatalogSortMode) -> Unit = {},
    onApplyLabelCombo: (LabelComboSuggestion) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onSelectSourceFilter: (String?) -> Unit,
    onSearch: (String?) -> Unit,
    onOpenVideo: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRemoteKeys by remember { mutableStateOf(false) }
    var voiceNotice by remember { mutableStateOf<String?>(null) }
    var filtersExpanded by remember { mutableStateOf(false) }
    val restoreRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester =
        restoreRequesters.getOrPut(key) { FocusRequester() }

    val displayedResults = remember(results, only4kFilter) {
        if (only4kFilter) {
            results.filter { item ->
                (item.maxResolutionHeight ?: 0) >= 2160 ||
                    item.maxQualityLabel?.contains("4K", ignoreCase = true) == true ||
                    item.title.contains("4k", ignoreCase = true)
            }
        } else {
            results
        }
    }

    LaunchedEffect(results, query, selectedSourceFilterId, only4kFilter) {
        if (displayedResults.isNotEmpty()) {
            filtersExpanded = false
        }
    }

    val voiceSearchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val spoken = activityResult.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                voiceNotice = null
                onQueryChange(spoken)
                onSearch(spoken)
            }
        }
    }

    val initialFocusKey = remember { lastFocusedKey }
    var focusRestored by remember { mutableStateOf(false) }
    LaunchedEffect(displayedResults.isNotEmpty(), filtersExpanded) {
        if (!focusRestored && initialFocusKey != null) {
            val req = restoreRequesters[initialFocusKey]
            if (req != null) {
                focusRestored = true
                runCatching { req.requestFocus() }
            }
        }
    }

    val quickSuggestions = remember {
        listOf("4K", "2160p", "1080p", "60fps", "HD")
    }

    val compactGridMode = !filtersExpanded && displayedResults.isNotEmpty()
    val sortLabel = when (sortMode) {
        CatalogSortMode.DEFAULT -> "Default"
        CatalogSortMode.QUALITY_FIRST -> "4K First"
        CatalogSortMode.LONGEST_FIRST -> "Longest"
    }
    val nextSortMode = when (sortMode) {
        CatalogSortMode.DEFAULT -> CatalogSortMode.QUALITY_FIRST
        CatalogSortMode.QUALITY_FIRST -> CatalogSortMode.LONGEST_FIRST
        CatalogSortMode.LONGEST_FIRST -> CatalogSortMode.DEFAULT
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(
                horizontal = if (compactGridMode) 24.dp else 28.dp,
                vertical = if (compactGridMode) 14.dp else 22.dp
            ),
        verticalArrangement = Arrangement.spacedBy(if (compactGridMode) 10.dp else 14.dp)
    ) {
        if (compactGridMode) {
            val activeSummary = buildString {
                if (query.isNotBlank()) {
                    append("\"$query\"")
                } else {
                    append("Results")
                }
                if (selectedSourceFilterId != null) {
                    append(" • ${friendlySiteName(selectedSourceFilterId)}")
                }
                if (only4kFilter) {
                    append(" • 4K")
                }
                append(" (${displayedResults.size})")
            }
            val isFiltered = query.isNotBlank() || only4kFilter || selectedSourceFilterId != null

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(key = "search_slim_summary") {
                    Text(
                        text = activeSummary,
                        style = MaterialTheme.typography.titleMedium,
                        color = HarborColors.FocusCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                item(key = "search_slim_expand") {
                    TvTagChip(
                        label = "Search & Filters ▾",
                        onClick = { filtersExpanded = true }
                    )
                }
                item(key = "search_slim_4k") {
                    TvTagChip(
                        label = if (only4kFilter) "4K Only: ON" else "4K Only",
                        selected = only4kFilter,
                        onClick = onToggleOnly4kFilter
                    )
                }
                item(key = "search_slim_sort") {
                    TvTagChip(
                        label = "Sort: $sortLabel",
                        selected = sortMode != CatalogSortMode.DEFAULT,
                        onClick = { onSelectSortMode(nextSortMode) }
                    )
                }
                if (isFiltered) {
                    item(key = "search_slim_clear") {
                        TvTagChip(
                            label = "Clear",
                            onClick = {
                                voiceNotice = null
                                onSelectSourceFilter(null)
                                if (only4kFilter) onToggleOnly4kFilter()
                                onQueryChange("")
                                onSearch("")
                                filtersExpanded = true
                            }
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.headlineMedium,
                    color = HarborColors.TextPrimary
                )
                if (displayedResults.isNotEmpty()) {
                    TvTagChip(
                        label = "Hide Filters ▴",
                        onClick = { filtersExpanded = false }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search videos, tags, or creators") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (results.isNotEmpty()) filtersExpanded = false
                            onSearch(null)
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = HarborColors.TextPrimary,
                        unfocusedTextColor = HarborColors.TextPrimary,
                        focusedBorderColor = HarborColors.FocusCyan,
                        unfocusedBorderColor = HarborColors.BorderSubtle
                    ),
                    modifier = Modifier.weight(1f)
                )
                TvActionButton(
                    label = "Search",
                    tone = TvButtonTone.PRIMARY,
                    onClick = {
                        if (results.isNotEmpty()) filtersExpanded = false
                        onSearch(null)
                    }
                )
                TvActionButton(
                    label = "Voice Search",
                    tone = TvButtonTone.ACCENT,
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                            )
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search videos")
                        }
                        try {
                            voiceNotice = null
                            voiceSearchLauncher.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            voiceNotice = "Voice search is not available on this TV device. Use the on-screen keyboard or Remote Keypad."
                        } catch (_: Exception) {
                            voiceNotice = "Unable to start voice search on this device."
                        }
                    }
                )
                TvActionButton(
                    label = if (showRemoteKeys) "Hide Keypad" else "Remote Keypad",
                    onClick = { showRemoteKeys = !showRemoteKeys }
                )
                TvActionButton(
                    label = "Clear",
                    onClick = {
                        voiceNotice = null
                        onQueryChange("")
                        onSearch("")
                    }
                )
            }

            voiceNotice?.let { notice ->
                TvStatusBanner(
                    title = "Voice Search Unavailable",
                    body = notice,
                    accentColor = HarborColors.WarningAmber
                )
            }

            if (showRemoteKeys) {
                val keys = remember {
                    ("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".map { it.toString() }) +
                        listOf("SPACE", "DEL", "GO")
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    keys.forEach { k ->
                        TvActionButton(
                            label = k,
                            onClick = {
                                when (k) {
                                    "SPACE" -> onQueryChange("$query ")
                                    "DEL" -> if (query.isNotEmpty()) onQueryChange(query.dropLast(1))
                                    "GO" -> onSearch(null)
                                    else -> onQueryChange(query + k.lowercase())
                                }
                            }
                        )
                    }
                }
            }

            // Recent Searches strip
            if (recentSearches.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item(key = "recent_label") {
                        Text(
                            text = "Recent Searches:",
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.TextSecondary
                        )
                    }
                    items(
                        recentSearches,
                        key = { "recent_$it" },
                        contentType = { "chip" }
                    ) { term ->
                        TvTagChip(
                            label = term,
                            selected = query.equals(term, ignoreCase = true),
                            onClick = {
                                onQueryChange(term)
                                onSearch(term)
                            }
                        )
                    }
                    item(key = "recent_clear", contentType = "chip") {
                        TvTagChip(
                            label = "Clear",
                            onClick = onClearRecentSearches
                        )
                    }
                }
            }

            // Personalized label combinations
            if (topLabelCombos.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        topLabelCombos,
                        key = { "search_combo_${it.searchQuery}" },
                        contentType = { "chip" }
                    ) { combo ->
                        TvTagChip(
                            label = combo.displayTitle,
                            selected = query.equals(combo.searchQuery, ignoreCase = true),
                            onClick = { onApplyLabelCombo(combo) }
                        )
                    }
                }
            }

            // Quick search suggestions
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    quickSuggestions,
                    key = { "suggest_$it" },
                    contentType = { "chip" }
                ) { suggestion ->
                    TvTagChip(
                        label = suggestion,
                        selected = query.equals(suggestion, ignoreCase = true),
                        onClick = {
                            onQueryChange(suggestion)
                            onSearch(suggestion)
                        }
                    )
                }
            }

            // Friendly site filters + 4K toggle + Sort chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "search_4k", contentType = "chip") {
                    TvTagChip(
                        label = if (only4kFilter) "4K Only: ON" else "4K Only",
                        selected = only4kFilter,
                        onClick = onToggleOnly4kFilter
                    )
                }
                item(key = "search_sort_default", contentType = "chip") {
                    TvTagChip(
                        label = "Default Order",
                        selected = sortMode == CatalogSortMode.DEFAULT,
                        onClick = { onSelectSortMode(CatalogSortMode.DEFAULT) }
                    )
                }
                item(key = "search_sort_4k", contentType = "chip") {
                    TvTagChip(
                        label = "4K First",
                        selected = sortMode == CatalogSortMode.QUALITY_FIRST,
                        onClick = { onSelectSortMode(CatalogSortMode.QUALITY_FIRST) }
                    )
                }
                item(key = "search_sort_long", contentType = "chip") {
                    TvTagChip(
                        label = "Longest",
                        selected = sortMode == CatalogSortMode.LONGEST_FIRST,
                        onClick = { onSelectSortMode(CatalogSortMode.LONGEST_FIRST) }
                    )
                }
                item(key = "search_site_all", contentType = "chip") {
                    TvTagChip(
                        label = "All Sites",
                        selected = selectedSourceFilterId == null,
                        onClick = { onSelectSourceFilter(null) }
                    )
                }
                items(
                    enabledSourceIds,
                    key = { "search_site_$it" },
                    contentType = { "chip" }
                ) { sourceId ->
                    TvTagChip(
                        label = friendlySiteName(sourceId),
                        selected = selectedSourceFilterId == sourceId,
                        onClick = { onSelectSourceFilter(sourceId) }
                    )
                }
            }
        }

        sourceErrors.forEach { (sourceId, err) ->
            TvStatusBanner(
                title = "${friendlySiteName(sourceId)} Unavailable",
                body = err,
                accentColor = HarborColors.WarningAmber
            )
        }

        if (isLoading && !compactGridMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = HarborColors.FocusCyan)
                Text(
                    text = "Searching enabled sites...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
        }

        if (!isLoading && query.isNotBlank() && displayedResults.isEmpty()) {
            TvStatusBanner(
                title = "No Matching Videos",
                body = "No videos matched \"$query\". Try a different keyword or adjust your filters.",
                accentColor = HarborColors.FocusCyan
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 196.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        ) {
            items(displayedResults, key = { it.compositeId }, contentType = { "video_card" }) { item ->
                val key = "search:${item.compositeId}"
                TvVideoCard(
                    item = item,
                    focusRequester = requesterFor(key),
                    onFocused = { onFocusKeyChanged(key) },
                    previewEnabled = previewEnabled,
                    fillWidth = true,
                    compact = true,
                    onClick = { onOpenVideo(item) }
                )
            }
        }
    }
}

private fun friendlySiteName(rawSourceId: String): String {
    val cleaned = rawSourceId.trim()
        .replace(Regex("""(?i)-(?:api|html|v\d+)$"""), "")
        .replace(Regex("""(?i)-(?:api|html|v\d+)$"""), "")
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
    if (cleaned.isEmpty()) return rawSourceId
    return cleaned.split(Regex("""\s+""")).joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
