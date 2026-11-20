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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.harbor.tv.core.tvui.TvFocusableCard
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.formatDuration
import com.harbor.tv.core.tvui.resolveQualityBadge
import com.harbor.tv.feature.player.PreloadState
import com.harbor.tv.feature.player.PreloadStatus

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
    onRefreshDetails: () -> Unit,
    onBack: () -> Unit,
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
    LaunchedEffect(item.compositeId) {
        runCatching { primaryFocus.requestFocus() }
    }

    val thumbUrl = item.thumbnailReferences.firstOrNull()?.takeIf { it.isNotBlank() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .width(340.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbUrl != null) {
                        val context = LocalContext.current
                        val heroRequest = remember(thumbUrl) {
                            ImageRequest.Builder(context)
                                .data(thumbUrl)
                                .size(Size(640, 360))
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
                    } else {
                        Text(
                            text = item.sourceId.uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = HarborColors.TextMuted
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DetailBadge(text = item.sourceId, color = HarborColors.FocusCyan)
                        when (item.liveStatus) {
                            LiveStatus.LIVE -> DetailBadge(text = "LIVE", color = HarborColors.DangerRed)
                            LiveStatus.UPCOMING -> DetailBadge(text = "UPCOMING", color = HarborColors.WarningAmber)
                            LiveStatus.ON_DEMAND -> item.durationSeconds?.let {
                                DetailBadge(text = formatDuration(it), color = HarborColors.AccentTeal)
                            }
                        }
                        resolveQualityBadge(
                            maxResolutionHeight = item.maxResolutionHeight,
                            maxQualityLabel = item.maxQualityLabel,
                            tags = item.canonicalTags
                        )?.let { (qLabel, qColor) ->
                            DetailBadge(
                                text = if (qLabel == "4K") "4K UHD" else qLabel,
                                color = qColor
                            )
                        }
                        DetailBadge(
                            text = "Metadata: ${item.metadataCompleteness.name}",
                            color = HarborColors.TextSecondary
                        )
                        if (item.languages.isNotEmpty()) {
                            DetailBadge(
                                text = "Lang: ${item.languages.joinToString(",")}",
                                color = HarborColors.TextSecondary
                            )
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
                                    String.format("ROM Preload %.1f / %.0f MB", mbCached, mbTarget)
                                PreloadState.COMPLETED ->
                                    String.format("ROM Cached %.1f MB", mbCached)
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
                        color = HarborColors.TextPrimary
                    )

                    Text(
                        text = item.description?.takeIf { it.isNotBlank() }
                            ?: "No publisher description provided in ${item.metadataProvenance} metadata.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = HarborColors.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TvActionButton(
                            label = if (supportsPlayback) "Play Now" else "Playback Unavailable (Metadata Only)",
                            tone = if (supportsPlayback) TvButtonTone.PRIMARY else TvButtonTone.SECONDARY,
                            enabled = supportsPlayback,
                            focusRequester = primaryFocus,
                            onClick = { onPlayNow(playbackPreview?.selectedCandidate) }
                        )
                        if (supportsPlayback && !isPrivateSession && playbackPreview != null) {
                            TvActionButton(
                                label = "Preload to Storage (128 MB)",
                                tone = TvButtonTone.ACCENT,
                                onClick = onPreloadMore
                            )
                        }
                        TvActionButton(
                            label = if (isFavorite) "Remove Favorite" else "Add to Favorites",
                            tone = if (isFavorite) TvButtonTone.ACCENT else TvButtonTone.SECONDARY,
                            onClick = onToggleFavorite
                        )
                        TvActionButton(
                            label = "Refresh Details",
                            onClick = onRefreshDetails
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
                        text = "Enriching metadata & resolving stream candidates...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextSecondary
                    )
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            item {
                TvStatusBanner(
                    title = "Details / Playback Notice",
                    body = errorMessage,
                    accentColor = HarborColors.DangerRed
                )
            }
        }

        // TagProvenance section (Spec 17.2)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Tag Provenance & Confidence",
                    style = MaterialTheme.typography.titleLarge,
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

        // Stream candidates preview
        item {
            val count = playbackPreview?.candidates?.size ?: 0
            Text(
                text = if (count > 0) "Resolved Stream Candidates ($count)" else "Resolved Stream Candidates",
                style = MaterialTheme.typography.titleLarge,
                color = HarborColors.TextPrimary
            )
        }

        val candidates = playbackPreview?.candidates.orEmpty()
        if (!supportsPlayback) {
            item {
                TvStatusBanner(
                    title = "Metadata-Only Source",
                    body = "This source definition declares playback=false (catalog discovery & official metadata lookup only).",
                    accentColor = HarborColors.WarningAmber
                )
            }
        } else if (candidates.isEmpty() && !isLoading) {
            item {
                Text(
                    text = "No stream candidates resolved yet. Press 'Play Now' to execute declarative playback resolution.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
        } else {
            items(candidates) { candidate ->
                val isSelected = candidate == playbackPreview?.selectedCandidate
                TvFocusableCard(
                    onClick = { onPlayNow(candidate) },
                    unfocusedBorderColor = if (isSelected) HarborColors.FocusCyan else HarborColors.BorderSubtle
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = buildString {
                                    append(candidate.streamType.name)
                                    candidate.qualityLabel?.let { append(" • $it") }
                                    candidate.resolutionHeight?.let { append(" (${it}p)") }
                                    if (isSelected) append(" [Preferred]")
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected) HarborColors.FocusCyan else HarborColors.TextPrimary
                            )
                            Text(
                                text = candidate.mimeType ?: "Advisory MIME unavailable",
                                style = MaterialTheme.typography.labelMedium,
                                color = HarborColors.TextSecondary
                            )
                        }
                        Text(
                            text = "Play Candidate",
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.FocusCyan
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBadge(
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}
