package com.harbor.tv.feature.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.LiveStatus
import com.harbor.tv.core.model.PlaybackCandidate
import com.harbor.tv.core.model.PlaybackResolutionResult
import com.harbor.tv.core.model.TagConfidence
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.TvActionButton
import com.harbor.tv.core.tvui.TvButtonTone
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.TvTagChip
import com.harbor.tv.core.tvui.formatDuration
import com.harbor.tv.core.tvui.formatTagForDisplay
import com.harbor.tv.core.tvui.resolveQualityBadge
import com.harbor.tv.feature.player.PreloadState
import com.harbor.tv.feature.player.PreloadStatus

private data class QualityChipEntry(
    val label: String,
    val candidate: PlaybackCandidate?,
    val isSelected: Boolean,
    val accentColor: Color
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoDetailsScreen(
    item: CatalogItem?,
    isFavorite: Boolean,
    supportsPlayback: Boolean,
    playbackPreview: PlaybackResolutionResult?,
    preloadStatus: PreloadStatus = PreloadStatus(),
    isPrivateSession: Boolean = false,
    isLoading: Boolean,
    errorMessage: String?,
    onPlayNow: (PlaybackCandidate?) -> Unit,
    onPreloadMore: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onHideVideo: () -> Unit = {},
    onRefreshDetails: () -> Unit,
    onBack: () -> Unit,
    onSearchTag: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (item == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(HarborColors.Background)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvStatusBanner(
                title = "No Video Selected",
                body = "Return to the catalog and select a video item.",
                accentColor = HarborColors.WarningAmber
            )
            TvActionButton(label = "Back", onClick = onBack)
        }
        return
    }

    val primaryFocus = remember { FocusRequester() }
    var showTechnicalMetadata by remember(item.compositeId) { mutableStateOf(false) }

    LaunchedEffect(item.compositeId) {
        runCatching { primaryFocus.requestFocus() }
    }

    val thumbUrl = item.thumbnailReferences.firstOrNull()?.takeIf { it.isNotBlank() }
    val friendlySource = remember(item.sourceId) { friendlySourceLabel(item.sourceId) }
    val qualityBadge = remember(item.maxResolutionHeight, item.maxQualityLabel, item.canonicalTags) {
        resolveQualityBadge(
            maxResolutionHeight = item.maxResolutionHeight,
            maxQualityLabel = item.maxQualityLabel,
            tags = item.canonicalTags
        )
    }

    val clickableTags = remember(item.canonicalTags, item.sourceTags) {
        (item.canonicalTags.ifEmpty { item.sourceTags })
            .map(::formatTagForDisplay)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    val qualityChips = remember(playbackPreview, supportsPlayback) {
        buildQualityChipEntries(playbackPreview, supportsPlayback)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Cinema hero header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(380.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbUrl != null) {
                        val context = LocalContext.current
                        val heroRequest = remember(thumbUrl) {
                            ImageRequest.Builder(context)
                                .data(thumbUrl)
                                .size(Size(760, 428))
                                .precision(Precision.INEXACT)
                                .allowRgb565(true)
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = heroRequest,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.35f)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color(0xCC060911))
                                    )
                                )
                        )
                    } else {
                        Text(
                            text = friendlySource.uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = HarborColors.TextMuted
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DetailBadge(text = friendlySource, color = HarborColors.FocusCyan)

                        qualityBadge?.let { (qLabel, qColor) ->
                            val badgeText = when {
                                qLabel.equals("4K", ignoreCase = true) -> "4K"
                                qLabel.equals("1080p", ignoreCase = true) -> "1080p"
                                else -> qLabel
                            }
                            DetailBadge(
                                text = badgeText,
                                color = qColor,
                                emphasized = true
                            )
                        }

                        when (item.liveStatus) {
                            LiveStatus.LIVE -> DetailBadge(text = "LIVE", color = HarborColors.DangerRed, emphasized = true)
                            LiveStatus.UPCOMING -> DetailBadge(text = "UPCOMING", color = HarborColors.WarningAmber)
                            LiveStatus.ON_DEMAND -> item.durationSeconds?.let {
                                DetailBadge(text = formatDuration(it), color = HarborColors.AccentTeal)
                            }
                        }

                        val matchesThisPreload = preloadStatus.sourceId == item.sourceId &&
                            preloadStatus.sourceItemId == item.sourceItemId &&
                            preloadStatus.state != PreloadState.IDLE
                        if (matchesThisPreload) {
                            val badgeColor = when (preloadStatus.state) {
                                PreloadState.COMPLETED -> HarborColors.SuccessGreen
                                PreloadState.PRELOADING -> HarborColors.FocusCyan
                                PreloadState.FAILED -> HarborColors.DangerRed
                                else -> HarborColors.TextSecondary
                            }
                            val mbCached = preloadStatus.bytesCached.toDouble() / (1024.0 * 1024.0)
                            val mbTarget = preloadStatus.targetBytes.toDouble() / (1024.0 * 1024.0)
                            val label = when (preloadStatus.state) {
                                PreloadState.PRELOADING ->
                                    String.format("Preloading %.0f / %.0f MB", mbCached, mbTarget)
                                PreloadState.COMPLETED ->
                                    String.format("Ready Offline %.0f MB", mbCached)
                                PreloadState.FAILED ->
                                    "Preload Failed"
                                PreloadState.CANCELLED ->
                                    "Preload Cancelled"
                                PreloadState.IDLE -> ""
                            }
                            if (label.isNotEmpty()) {
                                DetailBadge(text = label, color = badgeColor)
                            }
                        }
                    }

                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = HarborColors.TextPrimary
                    )

                    item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyLarge,
                            color = HarborColors.TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TvActionButton(
                            label = if (supportsPlayback) "Play Now" else "Playback Unavailable",
                            tone = if (supportsPlayback) TvButtonTone.PRIMARY else TvButtonTone.SECONDARY,
                            enabled = supportsPlayback,
                            focusRequester = primaryFocus,
                            onClick = { onPlayNow(playbackPreview?.selectedCandidate) }
                        )
                        TvActionButton(
                            label = if (isFavorite) "Saved" else "Favorite",
                            tone = if (isFavorite) TvButtonTone.ACCENT else TvButtonTone.SECONDARY,
                            onClick = onToggleFavorite
                        )
                        if (supportsPlayback && !isPrivateSession && playbackPreview != null) {
                            TvActionButton(
                                label = "Preload 128 MB",
                                tone = TvButtonTone.ACCENT,
                                onClick = onPreloadMore
                            )
                        }
                        TvActionButton(
                            label = "Hide Video",
                            tone = TvButtonTone.DANGER,
                            onClick = onHideVideo
                        )
                        TvActionButton(
                            label = "Back",
                            onClick = onBack
                        )
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = HarborColors.FocusCyan)
                    Text(
                        text = "Loading video streams and details...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextSecondary
                    )
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            item {
                TvStatusBanner(
                    title = "Playback Notice",
                    body = errorMessage,
                    accentColor = HarborColors.DangerRed
                )
            }
        }

        // Sleek horizontal Quality selector
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Quality",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.TextPrimary
                )
                if (!supportsPlayback) {
                    TvStatusBanner(
                        title = "Metadata-Only Source",
                        body = "This source provides browsing and search only; direct playback is not enabled.",
                        accentColor = HarborColors.WarningAmber
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(qualityChips, key = { it.label }) { chip ->
                            TvActionButton(
                                label = chip.label,
                                tone = if (chip.isSelected) TvButtonTone.PRIMARY else TvButtonTone.SECONDARY,
                                subtitle = if (chip.isSelected) "Preferred" else null,
                                onClick = { onPlayNow(chip.candidate) }
                            )
                        }
                    }
                }
            }
        }

        // Clean clickable tags
        if (clickableTags.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        clickableTags.forEach { cleanTag ->
                            TvTagChip(
                                label = cleanTag,
                                onClick = { onSearchTag(cleanTag) }
                            )
                        }
                    }
                }
            }
        }

        // Collapsible Technical Metadata section (collapsed by default)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvActionButton(
                        label = if (showTechnicalMetadata) "Hide Technical Metadata" else "Technical Metadata",
                        onClick = { showTechnicalMetadata = !showTechnicalMetadata }
                    )
                    TvActionButton(
                        label = "Refresh Details",
                        onClick = onRefreshDetails
                    )
                }

                if (showTechnicalMetadata) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(HarborColors.SurfaceCard)
                            .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp))
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DetailBadge(
                                text = "Completeness: ${item.metadataCompleteness.name}",
                                color = HarborColors.TextSecondary
                            )
                            DetailBadge(
                                text = "Source ID: ${item.sourceId}",
                                color = HarborColors.TextSecondary
                            )
                            DetailBadge(
                                text = "Provenance: ${item.metadataProvenance}",
                                color = HarborColors.TextSecondary
                            )
                            if (item.languages.isNotEmpty()) {
                                DetailBadge(
                                    text = "Languages: ${item.languages.joinToString(", ")}",
                                    color = HarborColors.TextSecondary
                                )
                            }
                        }

                        Text(
                            text = "Tag Provenance & Confidence",
                            style = MaterialTheme.typography.titleMedium,
                            color = HarborColors.TextPrimary
                        )
                        if (item.tagProvenance.isEmpty()) {
                            Text(
                                text = if (item.sourceTags.isEmpty()) {
                                    "No source tags or canonical taxonomy mappings present."
                                } else {
                                    "Unmapped source tags: ${item.sourceTags.joinToString(", ")}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = HarborColors.TextSecondary
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item.tagProvenance.forEach { prov ->
                                    val isExplicit = prov.confidence == TagConfidence.EXPLICIT
                                    val accent = if (isExplicit) HarborColors.SuccessGreen else HarborColors.WarningAmber
                                    Column(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(accent.copy(alpha = 0.12f))
                                            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = "${prov.canonicalTag} • ${prov.confidence.name}",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = accent
                                        )
                                        Text(
                                            text = "from ${prov.sourceField}=\"${prov.originalValue}\" (v${prov.mappingVersion})",
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
    }
}

private fun buildQualityChipEntries(
    playbackPreview: PlaybackResolutionResult?,
    supportsPlayback: Boolean
): List<QualityChipEntry> {
    if (!supportsPlayback) return emptyList()
    val candidates = playbackPreview?.candidates.orEmpty()
    val selected = playbackPreview?.selectedCandidate
    val standardTiers = listOf(
        2160 to "4K 2160p",
        1440 to "1440p",
        1080 to "1080p",
        720 to "720p",
        480 to "480p"
    )

    if (candidates.isEmpty()) {
        return standardTiers.mapIndexed { idx, (height, label) ->
            QualityChipEntry(
                label = label,
                candidate = null,
                isSelected = idx == 0,
                accentColor = tierColorForHeight(height)
            )
        }
    }

    val groupedByTier = linkedMapOf<Int, PlaybackCandidate>()
    val unranked = mutableListOf<PlaybackCandidate>()
    for (cand in candidates) {
        val h = cand.resolutionHeight
        if (h != null && h > 0) {
            val tier = when {
                h >= 2160 -> 2160
                h >= 1440 -> 1440
                h >= 1080 -> 1080
                h >= 720 -> 720
                else -> 480
            }
            val existing = groupedByTier[tier]
            if (existing == null || cand == selected || (cand.bitrateKbps ?: 0) > (existing.bitrateKbps ?: 0)) {
                groupedByTier[tier] = cand
            }
        } else {
            unranked.add(cand)
        }
    }

    val result = mutableListOf<QualityChipEntry>()
    for ((tierHeight, tierLabel) in standardTiers) {
        val match = groupedByTier[tierHeight] ?: continue
        result.add(
            QualityChipEntry(
                label = tierLabel,
                candidate = match,
                isSelected = match == selected,
                accentColor = tierColorForHeight(tierHeight)
            )
        )
    }
    if (result.isEmpty()) {
        candidates.forEach { cand ->
            val label = cand.qualityLabel?.takeIf { it.isNotBlank() }
                ?: cand.resolutionHeight?.let { "${it}p" }
                ?: cand.streamType.name
            result.add(
                QualityChipEntry(
                    label = label,
                    candidate = cand,
                    isSelected = cand == selected,
                    accentColor = tierColorForHeight(cand.resolutionHeight ?: 0)
                )
            )
        }
    } else if (unranked.isNotEmpty() && result.none { it.isSelected }) {
        val firstAuto = unranked.first()
        result.add(
            QualityChipEntry(
                label = firstAuto.qualityLabel?.takeIf { it.isNotBlank() } ?: "Auto",
                candidate = firstAuto,
                isSelected = firstAuto == selected,
                accentColor = HarborColors.FocusCyan
            )
        )
    }
    return result
}

private fun tierColorForHeight(height: Int): Color = when {
    height >= 2160 -> HarborColors.Gold4K
    height >= 1440 -> HarborColors.QuadHdViolet
    height >= 1080 -> HarborColors.FocusCyan
    else -> HarborColors.AccentTeal
}

private fun friendlySourceLabel(rawSourceId: String): String {
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

@Composable
private fun DetailBadge(
    text: String,
    color: Color,
    emphasized: Boolean = false
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (emphasized) color.copy(alpha = 0.22f) else color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = if (emphasized) 0.8f else 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
            color = color
        )
    }
}
