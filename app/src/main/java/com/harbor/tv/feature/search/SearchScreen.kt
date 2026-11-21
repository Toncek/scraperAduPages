package com.harbor.tv.feature.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextOverflow
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
    val filterToggleFocus = remember { FocusRequester() }
    val searchButtonFocus = remember { FocusRequester() }
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

    var filtersExpanded by remember { mutableStateOf(displayedResults.isEmpty()) }

    BackHandler(enabled = filtersExpanded && displayedResults.isNotEmpty()) {
        filtersExpanded = false
        runCatching { filterToggleFocus.requestFocus() }
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
                filtersExpanded = false
                onQueryChange(spoken)
                onSearch(spoken)
            }
        }
    }

    val initialFocusKey = remember { lastFocusedKey }
    var focusRestored by remember { mutableStateOf(false) }
    LaunchedEffect(displayedResults.isNotEmpty()) {
        if (!focusRestored && !filtersExpanded && initialFocusKey != null) {
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

    val showFilterPanel = filtersExpanded || displayedResults.isEmpty()
    val compactGridMode = !showFilterPanel
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
    val activeSummary = buildString {
        if (query.isNotBlank()) {
            append("\"$query\"")
        } else {
            append("Search")
        }
        if (selectedSourceFilterId != null) {
            append(" • ${friendlySiteName(selectedSourceFilterId)}")
        }
        if (only4kFilter) {
            append(" • 4K")
        }
        if (displayedResults.isNotEmpty()) {
            append(" (${displayedResults.size})")
        }
    }
    val isFiltered = query.isNotBlank() || only4kFilter || selectedSourceFilterId != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(
                horizontal = 24.dp,
                vertical = if (compactGridMode) 12.dp else 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Persistent top control bar — never unmounts on toggle so D-pad focus stays predictable
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvActionButton(
                label = if (showFilterPanel) "Hide Search ▴" else "Search & Filters ▾",
                tone = if (showFilterPanel) TvButtonTone.SECONDARY else TvButtonTone.PRIMARY,
                focusRequester = filterToggleFocus,
                onClick = {
                    if (displayedResults.isNotEmpty()) {
                        filtersExpanded = !filtersExpanded
                    } else {
                        filtersExpanded = true
                    }
                }
            )
            TvTagChip(
                label = if (only4kFilter) "4K Only: ON" else "4K Only",
                selected = only4kFilter,
                onClick = onToggleOnly4kFilter
            )
            TvTagChip(
                label = "Sort: $sortLabel",
                selected = sortMode != CatalogSortMode.DEFAULT,
                onClick = { onSelectSortMode(nextSortMode) }
            )
            if (isFiltered) {
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
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = activeSummary,
                style = MaterialTheme.typography.titleMedium,
                color = HarborColors.FocusCyan,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (showFilterPanel) {
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
                            filtersExpanded = false
                            onSearch(null)
                            runCatching { filterToggleFocus.requestFocus() }
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
                    focusRequester = searchButtonFocus,
                    onClick = {
                        filtersExpanded = false
                        onSearch(null)
                        runCatching { filterToggleFocus.requestFocus() }
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
                                    "GO" -> {
                                        filtersExpanded = false
                                        onSearch(null)
                                        runCatching { filterToggleFocus.requestFocus() }
                                    }
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
                                filtersExpanded = false
                                onQueryChange(term)
                                onSearch(term)
                                runCatching { filterToggleFocus.requestFocus() }
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
                            onClick = {
                                filtersExpanded = false
                                onApplyLabelCombo(combo)
                                runCatching { filterToggleFocus.requestFocus() }
                            }
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
                            filtersExpanded = false
                            onQueryChange(suggestion)
                            onSearch(suggestion)
                            runCatching { filterToggleFocus.requestFocus() }
                        }
                    )
                }
            }

            // Friendly site filter chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(key = "search_sites_label") {
                    Text(
                        text = "Site:",
                        style = MaterialTheme.typography.labelLarge,
                        color = HarborColors.TextSecondary,
                        modifier = Modifier.padding(end = 2.dp)
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
