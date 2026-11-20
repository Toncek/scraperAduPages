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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.CatalogSortMode
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
    sourceErrors: Map<String, String>,
    isLoading: Boolean,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onToggleOnly4kFilter: () -> Unit = {},
    onSelectSortMode: (CatalogSortMode) -> Unit = {},
    onQueryChange: (String) -> Unit,
    onSelectSourceFilter: (String?) -> Unit,
    onSearch: (String?) -> Unit,
    onOpenVideo: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRemoteKeys by remember { mutableStateOf(false) }
    var voiceNotice by remember { mutableStateOf<String?>(null) }
    val restoreRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester =
        restoreRequesters.getOrPut(key) { FocusRequester() }

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

    LaunchedEffect(lastFocusedKey, results) {
        val target = lastFocusedKey ?: return@LaunchedEffect
        restoreRequesters[target]?.let { req ->
            runCatching { req.requestFocus() }
        }
    }

    val quickSuggestions = remember {
        listOf("4K", "2160p", "1080p", "60fps", "HD")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineMedium,
            color = HarborColors.TextPrimary
        )

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
                    onSearch = { onSearch(null) }
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
                onClick = { onSearch(null) }
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

        // Quick search suggestions
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickSuggestions) { suggestion ->
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
            item {
                TvTagChip(
                    label = if (only4kFilter) "4K Only: ON" else "4K Only",
                    selected = only4kFilter,
                    onClick = onToggleOnly4kFilter
                )
            }
            item {
                TvTagChip(
                    label = "Default Order",
                    selected = sortMode == CatalogSortMode.DEFAULT,
                    onClick = { onSelectSortMode(CatalogSortMode.DEFAULT) }
                )
            }
            item {
                TvTagChip(
                    label = "4K First",
                    selected = sortMode == CatalogSortMode.QUALITY_FIRST,
                    onClick = { onSelectSortMode(CatalogSortMode.QUALITY_FIRST) }
                )
            }
            item {
                TvTagChip(
                    label = "Longest",
                    selected = sortMode == CatalogSortMode.LONGEST_FIRST,
                    onClick = { onSelectSortMode(CatalogSortMode.LONGEST_FIRST) }
                )
            }
            item {
                TvTagChip(
                    label = "All Sites",
                    selected = selectedSourceFilterId == null,
                    onClick = { onSelectSourceFilter(null) }
                )
            }
            items(enabledSourceIds) { sourceId ->
                TvTagChip(
                    label = friendlySiteName(sourceId),
                    selected = selectedSourceFilterId == sourceId,
                    onClick = { onSelectSourceFilter(sourceId) }
                )
            }
        }

        sourceErrors.forEach { (sourceId, err) ->
            TvStatusBanner(
                title = "${friendlySiteName(sourceId)} Unavailable",
                body = err,
                accentColor = HarborColors.WarningAmber
            )
        }

        if (isLoading) {
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

        val displayedResults = if (only4kFilter) {
            results.filter { item ->
                (item.maxResolutionHeight ?: 0) >= 2160 ||
                    item.maxQualityLabel?.contains("4K", ignoreCase = true) == true ||
                    item.title.contains("4k", ignoreCase = true)
            }
        } else {
            results
        }

        if (!isLoading && query.isNotBlank() && displayedResults.isEmpty()) {
            TvStatusBanner(
                title = "No Matching Videos",
                body = "No videos matched \"$query\". Try a different keyword or adjust your filters.",
                accentColor = HarborColors.FocusCyan
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 250.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(displayedResults, key = { it.compositeId }) { item ->
                val key = "search:${item.compositeId}"
                TvVideoCard(
                    item = item,
                    focusRequester = requesterFor(key),
                    onFocused = { onFocusKeyChanged(key) },
                    previewEnabled = previewEnabled,
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
