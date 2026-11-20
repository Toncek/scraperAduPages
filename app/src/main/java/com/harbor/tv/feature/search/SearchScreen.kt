package com.harbor.tv.feature.search

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.harbor.tv.core.model.CatalogItem
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
    sourceErrors: Map<String, String>,
    isLoading: Boolean,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectSourceFilter: (String?) -> Unit,
    onSearch: (String?) -> Unit,
    onOpenVideo: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRemoteKeys by remember { mutableStateOf(false) }
    val restoreRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester =
        restoreRequesters.getOrPut(key) { FocusRequester() }

    LaunchedEffect(lastFocusedKey, results) {
        val target = lastFocusedKey ?: return@LaunchedEffect
        restoreRequesters[target]?.let { req ->
            runCatching { req.requestFocus() }
        }
    }

    val quickSuggestions = remember {
        listOf("nature", "alpine", "documentaries", "education", "science", "4k", "english")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Multi-Source Search",
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
                label = { Text("Search across enabled sources") },
                singleLine = true,
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
                label = if (showRemoteKeys) "Hide Keypad" else "Remote Keypad",
                onClick = { showRemoteKeys = !showRemoteKeys }
            )
            TvActionButton(
                label = "Clear",
                onClick = {
                    onQueryChange("")
                    onSearch("")
                }
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

        // Quick tag suggestions
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(quickSuggestions) { suggestion ->
                TvTagChip(
                    label = "#$suggestion",
                    selected = query.equals(suggestion, ignoreCase = true),
                    onClick = {
                        onQueryChange(suggestion)
                        onSearch(suggestion)
                    }
                )
            }
        }

        // Source filter chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TvTagChip(
                    label = "All Enabled Sources",
                    selected = selectedSourceFilterId == null,
                    onClick = { onSelectSourceFilter(null) }
                )
            }
            items(enabledSourceIds) { sourceId ->
                TvTagChip(
                    label = sourceId,
                    selected = selectedSourceFilterId == sourceId,
                    onClick = { onSelectSourceFilter(sourceId) }
                )
            }
        }

        sourceErrors.forEach { (sourceId, err) ->
            TvStatusBanner(
                title = "Source '$sourceId' Search Error",
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
                    text = "Searching & applying preferences...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
        }

        if (!isLoading && query.isNotBlank() && results.isEmpty()) {
            TvStatusBanner(
                title = "No Eligible Results",
                body = "No items matched '$query' under your active profile's PreferenceFilterEngine rules.",
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
            items(results, key = { it.compositeId }) { item ->
                val key = "search:${item.compositeId}"
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
