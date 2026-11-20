package com.harbor.tv.feature.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.TvActionButton
import com.harbor.tv.core.tvui.TvButtonTone
import com.harbor.tv.core.tvui.TvFocusableCard
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.formatDurationMs
import kotlinx.coroutines.delay

private enum class PlayerPanel {
    NONE,
    QUALITY,
    TRACKS
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    controller: HarborPlayerController,
    onExitPlayback: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    autoPlayNextEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val uiState by controller.uiState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var activePanel by remember { mutableStateOf(PlayerPanel.NONE) }
    var lastInteractionTick by remember { mutableStateOf(0L) }
    var timelineFocused by remember { mutableStateOf(false) }
    var upNextCancelled by remember(uiState.sourceItemId) { mutableStateOf(false) }
    var upNextCountdownSec by remember(uiState.sourceItemId, uiState.isEnded) { mutableIntStateOf(5) }

    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val panelFirstItemFocusRequester = remember { FocusRequester() }
    val upNextPlayFocusRequester = remember { FocusRequester() }

    val showUpNextOverlay = uiState.isEnded &&
        uiState.hasNextInQueue &&
        autoPlayNextEnabled &&
        uiState.errorMessage == null &&
        !upNextCancelled

    fun bumpInteraction() {
        lastInteractionTick = System.currentTimeMillis()
    }

    LaunchedEffect(uiState.isEnded) {
        if (!uiState.isEnded) {
            upNextCancelled = false
        }
    }

    LaunchedEffect(showUpNextOverlay, uiState.sourceItemId) {
        if (showUpNextOverlay) {
            controlsVisible = false
            activePanel = PlayerPanel.NONE
            upNextCountdownSec = 5
            runCatching { upNextPlayFocusRequester.requestFocus() }
            while (upNextCountdownSec > 0) {
                delay(1_000L)
                upNextCountdownSec -= 1
            }
            onPlayNext()
        }
    }

    // Auto-hide controls after 6 seconds of inactivity while playing and no panel is open
    LaunchedEffect(controlsVisible, uiState.isPlaying, activePanel, lastInteractionTick) {
        if (controlsVisible && uiState.isPlaying && activePanel == PlayerPanel.NONE && uiState.errorMessage == null) {
            delay(6_000L)
            controlsVisible = false
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(controlsVisible, activePanel, showUpNextOverlay) {
        if (showUpNextOverlay) {
            runCatching { upNextPlayFocusRequester.requestFocus() }
        } else if (activePanel != PlayerPanel.NONE) {
            runCatching { panelFirstItemFocusRequester.requestFocus() }
        } else if (controlsVisible) {
            runCatching { playPauseFocusRequester.requestFocus() }
        } else {
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    BackHandler {
        when {
            showUpNextOverlay -> {
                upNextCancelled = true
                controlsVisible = true
            }
            activePanel != PlayerPanel.NONE -> {
                activePanel = PlayerPanel.NONE
                bumpInteraction()
            }
            controlsVisible -> {
                controlsVisible = false
            }
            else -> {
                controller.stop()
                onExitPlayback()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    bumpInteraction()
                }
                val key = event.key
                if (key == Key.MediaPlayPause || key == Key.MediaPlay || key == Key.MediaPause) {
                    if (event.type == KeyEventType.KeyUp) {
                        controller.togglePlayPause()
                        controlsVisible = true
                    }
                    return@onPreviewKeyEvent true
                }

                // Spec 18.4: Center reveals controls cleanly when hidden
                if (!controlsVisible && (key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter)) {
                    if (event.type == KeyEventType.KeyUp) {
                        controlsVisible = true
                    }
                    return@onPreviewKeyEvent true
                }

                // Instant -10s / +10s seek when pressing Left / Right even while controls are hidden
                if (!controlsVisible && (key == Key.DirectionLeft || key == Key.DirectionRight)) {
                    if (uiState.isSeekable) {
                        if (event.type == KeyEventType.KeyDown) {
                            val deltaMs = if (key == Key.DirectionLeft) -10_000L else 10_000L
                            controller.seekBy(deltaMs)
                            controlsVisible = true
                            bumpInteraction()
                        }
                    } else if (event.type == KeyEventType.KeyUp) {
                        controlsVisible = true
                    }
                    return@onPreviewKeyEvent true
                }

                // Reveal controls on Up / Down when hidden
                if (!controlsVisible && (key == Key.DirectionUp || key == Key.DirectionDown)) {
                    if (event.type == KeyEventType.KeyUp) {
                        controlsVisible = true
                    }
                    return@onPreviewKeyEvent true
                }

                false
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = controller.player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.player !== controller.player) {
                    view.player = controller.player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (uiState.isBuffering || uiState.isRecoveringExpiry) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC060911))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(color = HarborColors.FocusCyan)
                Text(
                    text = if (uiState.isRecoveringExpiry) {
                        "Refreshing stream link..."
                    } else {
                        "Buffering..."
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = HarborColors.TextPrimary
                )
            }
        }

        val errMsg = uiState.errorMessage
        if (errMsg != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.7f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(HarborColors.Surface)
                    .border(2.dp, HarborColors.DangerRed, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvStatusBanner(
                        title = "Playback Error (${uiState.errorCode?.name ?: "ERROR"})",
                        body = errMsg,
                        accentColor = HarborColors.DangerRed
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton(
                            label = "Exit Player",
                            tone = TvButtonTone.DANGER,
                            focusRequester = playPauseFocusRequester,
                            onClick = {
                                controller.stop()
                                onExitPlayback()
                            }
                        )
                        if (uiState.candidates.size > 1) {
                            TvActionButton(
                                label = "Switch Quality",
                                tone = TvButtonTone.PRIMARY,
                                onClick = {
                                    controlsVisible = true
                                    activePanel = PlayerPanel.QUALITY
                                }
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && uiState.errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Cinema top gradient scrim fading transparently into video
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xE6060911),
                                    Color(0x99060911),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 36.dp, vertical = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = uiState.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = HarborColors.TextPrimary
                        )
                        val activeCand = uiState.activeCandidate
                        val subLabel = buildString {
                            append(friendlyPlayerSource(uiState.sourceId))
                            if (activeCand != null) {
                                append(" • ${activeCand.streamType.name}")
                                activeCand.qualityLabel?.let { append(" • $it") }
                            }
                            if (uiState.isLive) append(" • LIVE")
                        }
                        Text(
                            text = subLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HarborColors.TextSecondary
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.decodedHeight > 0) {
                            val (tierLabel, tierColor) = when {
                                uiState.decodedHeight >= 2160 -> "4K UHD" to HarborColors.Gold4K
                                uiState.decodedHeight >= 1440 -> "1440p" to HarborColors.QuadHdViolet
                                uiState.decodedHeight >= 1080 -> "1080p" to HarborColors.FocusCyan
                                uiState.decodedHeight >= 720 -> "720p" to HarborColors.AccentTeal
                                else -> "${uiState.decodedHeight}p" to HarborColors.TextSecondary
                            }
                            val hudText = buildString {
                                append(tierLabel)
                                if (uiState.decodedWidth > 0) {
                                    append(" • ${uiState.decodedWidth}×${uiState.decodedHeight}")
                                }
                                if (uiState.decodedFrameRate >= 1f) {
                                    append(" @ ${uiState.decodedFrameRate.toInt()}fps")
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(tierColor.copy(alpha = 0.22f))
                                    .border(1.dp, tierColor, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = hudText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = tierColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (uiState.isPrivateSession) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(HarborColors.PrivatePurple.copy(alpha = 0.25f))
                                    .border(1.dp, HarborColors.PrivatePurple, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Private Session",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = HarborColors.PrivatePurple
                                )
                            }
                        }
                        TvActionButton(
                            label = "Exit",
                            tone = TvButtonTone.SECONDARY,
                            onClick = {
                                controller.stop()
                                onExitPlayback()
                            }
                        )
                    }
                }

                // Cinema bottom gradient scrim fading transparently into video
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xA6060911),
                                    Color(0xEE060911)
                                )
                            )
                        )
                        .padding(horizontal = 36.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val upNextTitle = uiState.nextItemTitle
                    if (!upNextTitle.isNullOrBlank()) {
                        val suffix = uiState.upNextPreloadLabel?.takeIf { it.isNotBlank() }
                            ?.let { " • $it" } ?: ""
                        Text(
                            text = "Up Next: $upNextTitle$suffix",
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.AccentTeal
                        )
                    }

                    // Focusable D-pad Timeline Scrubber (active only when stream is seekable)
                    val progressFraction = if (uiState.durationMs > 0L) {
                        (uiState.currentPositionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (timelineFocused) 2.dp else 0.dp,
                                color = if (timelineFocused) HarborColors.FocusCyan else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .onFocusChanged { timelineFocused = it.isFocused }
                            .focusable(enabled = uiState.isSeekable)
                            .onKeyEvent { event ->
                                if (!uiState.isSeekable) return@onKeyEvent false
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            controller.seekBy(-10_000L)
                                            bumpInteraction()
                                            return@onKeyEvent true
                                        }
                                        Key.DirectionRight -> {
                                            controller.seekBy(10_000L)
                                            bumpInteraction()
                                            return@onKeyEvent true
                                        }
                                        else -> Unit
                                    }
                                }
                                false
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (timelineFocused) 10.dp else 7.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (uiState.isSeekable) HarborColors.FocusCyan else HarborColors.DangerRed,
                            trackColor = Color(0x55FFFFFF)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (uiState.isLive) "LIVE" else formatDurationMs(uiState.currentPositionMs),
                                style = MaterialTheme.typography.labelLarge,
                                color = HarborColors.TextPrimary
                            )
                            Text(
                                text = if (uiState.isSeekable) {
                                    if (timelineFocused) "Left / Right to seek 10s" else formatDurationMs(uiState.durationMs)
                                } else {
                                    "Stream not seekable"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = HarborColors.TextSecondary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.hasPreviousInQueue) {
                            TvActionButton(
                                label = "Prev",
                                onClick = {
                                    onPlayPrevious()
                                    bumpInteraction()
                                }
                            )
                        }

                        if (uiState.isSeekable) {
                            TvActionButton(
                                label = "-15s",
                                onClick = {
                                    controller.seekBy(-15_000L)
                                    bumpInteraction()
                                }
                            )
                        }

                        TvActionButton(
                            label = if (uiState.isPlaying) "Pause" else "Play",
                            tone = TvButtonTone.PRIMARY,
                            focusRequester = playPauseFocusRequester,
                            onClick = {
                                controller.togglePlayPause()
                                bumpInteraction()
                            }
                        )

                        if (uiState.isSeekable) {
                            TvActionButton(
                                label = "+15s",
                                onClick = {
                                    controller.seekBy(15_000L)
                                    bumpInteraction()
                                }
                            )
                        }

                        if (uiState.hasNextInQueue) {
                            TvActionButton(
                                label = "Next",
                                tone = TvButtonTone.ACCENT,
                                onClick = {
                                    onPlayNext()
                                    bumpInteraction()
                                }
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        TvActionButton(
                            label = "Quality (${uiState.candidates.size})",
                            onClick = {
                                activePanel = if (activePanel == PlayerPanel.QUALITY) {
                                    PlayerPanel.NONE
                                } else {
                                    PlayerPanel.QUALITY
                                }
                                bumpInteraction()
                            }
                        )

                        TvActionButton(
                            label = "Audio / Subtitles",
                            onClick = {
                                activePanel = if (activePanel == PlayerPanel.TRACKS) {
                                    PlayerPanel.NONE
                                } else {
                                    PlayerPanel.TRACKS
                                }
                                bumpInteraction()
                            }
                        )
                    }
                }

                // Side overlay drawers
                if (activePanel != PlayerPanel.NONE) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 32.dp)
                            .width(380.dp)
                            .fillMaxHeight(0.75f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(HarborColors.Surface.copy(alpha = 0.96f))
                            .border(2.dp, HarborColors.FocusCyan, RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (activePanel == PlayerPanel.QUALITY) {
                                    "Stream Quality"
                                } else {
                                    "Audio & Subtitles"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = HarborColors.TextPrimary
                            )
                            TvActionButton(
                                label = "Close",
                                onClick = { activePanel = PlayerPanel.NONE }
                            )
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (activePanel == PlayerPanel.QUALITY) {
                                items(uiState.candidates.indices.toList()) { idx ->
                                    val candidate = uiState.candidates[idx]
                                    val isCurrent = candidate == uiState.activeCandidate
                                    TvFocusableCard(
                                        onClick = {
                                            controller.selectCandidate(candidate)
                                            activePanel = PlayerPanel.NONE
                                        },
                                        focusRequester = if (idx == 0) panelFirstItemFocusRequester else null,
                                        unfocusedBorderColor = if (isCurrent) HarborColors.FocusCyan else HarborColors.BorderSubtle
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = buildString {
                                                    append(candidate.qualityLabel ?: candidate.streamType.name)
                                                    if (candidate.resolutionHeight != null) {
                                                        append(" (${candidate.resolutionHeight}p)")
                                                    }
                                                    if (isCurrent) append(" • Active")
                                                },
                                                style = MaterialTheme.typography.titleMedium,
                                                color = if (isCurrent) HarborColors.FocusCyan else HarborColors.TextPrimary
                                            )
                                            Text(
                                                text = buildString {
                                                    append(candidate.streamType.name)
                                                    candidate.bitrateKbps?.let { append(" • ${it} kbps") }
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = HarborColors.TextSecondary
                                            )
                                        }
                                    }
                                }
                            } else {
                                item {
                                    Text(
                                        text = "Audio Tracks",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = HarborColors.AccentTeal
                                    )
                                }
                                if (uiState.audioTracks.isEmpty()) {
                                    item {
                                        Text(
                                            text = "Default audio track only",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = HarborColors.TextSecondary
                                        )
                                    }
                                } else {
                                    items(uiState.audioTracks.indices.toList()) { idx ->
                                        val track = uiState.audioTracks[idx]
                                        TvFocusableCard(
                                            onClick = { controller.selectAudioTrack(track) },
                                            focusRequester = if (idx == 0) panelFirstItemFocusRequester else null,
                                            unfocusedBorderColor = if (track.isSelected) HarborColors.FocusCyan else HarborColors.BorderSubtle
                                        ) {
                                            Text(
                                                text = "${track.label}${if (track.isSelected) " • Selected" else ""}",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = if (track.isSelected) HarborColors.FocusCyan else HarborColors.TextPrimary
                                            )
                                        }
                                    }
                                }

                                item {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Subtitles",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = HarborColors.AccentTeal
                                    )
                                }
                                item {
                                    TvFocusableCard(
                                        onClick = { controller.selectSubtitleTrack(null) },
                                        focusRequester = if (uiState.audioTracks.isEmpty()) panelFirstItemFocusRequester else null,
                                        unfocusedBorderColor = if (uiState.subtitlesDisabled) HarborColors.FocusCyan else HarborColors.BorderSubtle
                                    ) {
                                        Text(
                                            text = "Subtitles Off${if (uiState.subtitlesDisabled) " • Selected" else ""}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (uiState.subtitlesDisabled) HarborColors.FocusCyan else HarborColors.TextPrimary
                                        )
                                    }
                                }
                                items(uiState.subtitleTracks) { track ->
                                    val selected = track.isSelected && !uiState.subtitlesDisabled
                                    TvFocusableCard(
                                        onClick = { controller.selectSubtitleTrack(track) },
                                        unfocusedBorderColor = if (selected) HarborColors.FocusCyan else HarborColors.BorderSubtle
                                    ) {
                                        Text(
                                            text = "${track.label}${if (selected) " • Selected" else ""}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (selected) HarborColors.FocusCyan else HarborColors.TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showUpNextOverlay) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 36.dp)
                    .width(420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(HarborColors.Surface.copy(alpha = 0.96f))
                    .border(2.dp, HarborColors.FocusCyan, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Up Next in ${upNextCountdownSec}s",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.FocusCyan,
                        fontWeight = FontWeight.Bold
                    )
                    if (!uiState.upNextPreloadLabel.isNullOrBlank()) {
                        Text(
                            text = uiState.upNextPreloadLabel.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.AccentTeal
                        )
                    }
                }
                Text(
                    text = uiState.nextItemTitle ?: "Next Video",
                    style = MaterialTheme.typography.titleMedium,
                    color = HarborColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        label = "Play Next Now",
                        tone = TvButtonTone.PRIMARY,
                        focusRequester = upNextPlayFocusRequester,
                        onClick = { onPlayNext() }
                    )
                    TvActionButton(
                        label = "Cancel",
                        tone = TvButtonTone.SECONDARY,
                        onClick = {
                            upNextCancelled = true
                            controlsVisible = true
                        }
                    )
                }
            }
        }
    }
}

private fun friendlyPlayerSource(rawSourceId: String): String {
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
