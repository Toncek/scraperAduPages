package com.harbor.tv.core.tvui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.LiveStatus
import com.harbor.tv.core.model.TagPreferenceState

enum class HarborNavDestination(val label: String, val badge: String) {
    HOME("Home", "HM"),
    CATEGORIES("Categories", "CT"),
    SEARCH("Search", "SR"),
    FAVORITES("Favorites", "FV"),
    SOURCES("Sources", "SC"),
    SETTINGS("Settings", "ST")
}

enum class TvButtonTone {
    PRIMARY,
    SECONDARY,
    DANGER,
    ACCENT
}

fun Modifier.dpadActivatable(
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusStateChanged: (Boolean) -> Unit = {},
    onActivate: () -> Unit
): Modifier {
    val withRequester = if (focusRequester != null) {
        this.focusRequester(focusRequester)
    } else {
        this
    }
    return withRequester
        .onFocusChanged { state ->
            onFocusStateChanged(state.isFocused)
        }
        .focusable(enabled = enabled)
        .onKeyEvent { event ->
            if (!enabled) return@onKeyEvent false
            val isConfirmKey = event.key == Key.DirectionCenter ||
                event.key == Key.Enter ||
                event.key == Key.NumPadEnter
            if (!isConfirmKey) return@onKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> true
                KeyEventType.KeyUp -> {
                    onActivate()
                    true
                }
                else -> false
            }
        }
        .pointerInput(enabled, onActivate) {
            if (enabled) {
                detectTapGestures(onTap = { onActivate() })
            }
        }
        .semantics {
            role = Role.Button
        }
}

@Composable
fun TvFocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = HarborColors.SurfaceCard,
    focusedContainerColor: Color = HarborColors.SurfaceCardFocused,
    focusBorderColor: Color = HarborColors.FocusCyan,
    unfocusedBorderColor: Color = HarborColors.BorderSubtle,
    focusedScale: Float = 1.03f,
    contentPadding: Dp = 14.dp,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusedScale else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "cardScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 3.dp else 1.dp,
        animationSpec = tween(durationMillis = 120),
        label = "cardBorderWidth"
    )
    val elevation by animateDpAsState(
        targetValue = if (isFocused) 12.dp else 0.dp,
        animationSpec = tween(durationMillis = 120),
        label = "cardElevation"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isFocused) focusedContainerColor else containerColor,
        animationSpec = tween(durationMillis = 120),
        label = "cardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) focusBorderColor else unfocusedBorderColor,
        animationSpec = tween(durationMillis = 120),
        label = "cardBorderColor"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = focusBorderColor,
                spotColor = focusBorderColor
            )
            .clip(shape)
            .background(bgColor)
            .border(BorderStroke(borderWidth, borderColor), shape)
            .dpadActivatable(
                enabled = enabled,
                focusRequester = focusRequester,
                onFocusStateChanged = { focused ->
                    isFocused = focused
                    if (focused) onFocused?.invoke()
                },
                onActivate = onClick
            )
            .padding(contentPadding),
        content = { content(isFocused) }
    )
}

@Composable
fun TvActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: TvButtonTone = TvButtonTone.SECONDARY,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    subtitle: String? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val baseBg = when (tone) {
        TvButtonTone.PRIMARY -> HarborColors.FocusCyan.copy(alpha = 0.22f)
        TvButtonTone.ACCENT -> HarborColors.AccentTeal.copy(alpha = 0.22f)
        TvButtonTone.DANGER -> HarborColors.DangerRed.copy(alpha = 0.20f)
        TvButtonTone.SECONDARY -> HarborColors.SurfaceCard
    }
    val focusedBg = when (tone) {
        TvButtonTone.PRIMARY -> HarborColors.FocusCyan
        TvButtonTone.ACCENT -> HarborColors.AccentTeal
        TvButtonTone.DANGER -> HarborColors.DangerRed
        TvButtonTone.SECONDARY -> HarborColors.SurfaceCardFocused
    }
    val textColor = when {
        !enabled -> HarborColors.TextMuted
        isFocused && tone != TvButtonTone.SECONDARY -> HarborColors.Background
        else -> HarborColors.TextPrimary
    }
    val borderColor = when {
        !enabled -> HarborColors.BorderSubtle
        isFocused -> HarborColors.FocusCyan
        tone == TvButtonTone.PRIMARY -> HarborColors.FocusCyan.copy(alpha = 0.65f)
        tone == TvButtonTone.DANGER -> HarborColors.DangerRed.copy(alpha = 0.65f)
        tone == TvButtonTone.ACCENT -> HarborColors.AccentTeal.copy(alpha = 0.65f)
        else -> HarborColors.BorderSubtle
    }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "btnScale"
    )

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 10.dp else 0.dp,
                shape = shape,
                ambientColor = HarborColors.FocusCyan,
                spotColor = HarborColors.FocusCyan
            )
            .clip(shape)
            .background(if (isFocused) focusedBg else baseBg)
            .border(if (isFocused) 3.dp else 1.dp, borderColor, shape)
            .dpadActivatable(
                enabled = enabled,
                focusRequester = focusRequester,
                onFocusStateChanged = { focused ->
                    isFocused = focused
                    if (focused) onFocused?.invoke()
                },
                onActivate = onClick
            )
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isFocused && tone != TvButtonTone.SECONDARY) {
                        HarborColors.Background.copy(alpha = 0.8f)
                    } else {
                        HarborColors.TextSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun TvTagChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    preferenceState: TagPreferenceState? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val stateColor = when (preferenceState) {
        TagPreferenceState.PREFERRED -> HarborColors.SuccessGreen
        TagPreferenceState.EXCLUDED -> HarborColors.DangerRed
        TagPreferenceState.NEUTRAL -> HarborColors.TextSecondary
        null -> if (selected) HarborColors.FocusCyan else HarborColors.BorderSubtle
    }
    val statePrefix = when (preferenceState) {
        TagPreferenceState.PREFERRED -> "+ "
        TagPreferenceState.EXCLUDED -> "x "
        TagPreferenceState.NEUTRAL -> "= "
        null -> ""
    }
    val bgColor = when {
        isFocused -> HarborColors.SurfaceCardFocused
        preferenceState == TagPreferenceState.PREFERRED -> HarborColors.SuccessGreen.copy(alpha = 0.16f)
        preferenceState == TagPreferenceState.EXCLUDED -> HarborColors.DangerRed.copy(alpha = 0.16f)
        selected -> HarborColors.FocusCyan.copy(alpha = 0.22f)
        else -> HarborColors.SurfaceCard
    }
    val borderColor = if (isFocused) HarborColors.FocusCyan else stateColor
    val shape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .scale(if (isFocused) 1.05f else 1f)
            .clip(shape)
            .background(bgColor)
            .border(if (isFocused) 2.5.dp else 1.dp, borderColor, shape)
            .dpadActivatable(
                focusRequester = focusRequester,
                onFocusStateChanged = { focused ->
                    isFocused = focused
                    if (focused) onFocused?.invoke()
                },
                onActivate = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$statePrefix$label",
            style = MaterialTheme.typography.labelLarge,
            color = if (preferenceState == TagPreferenceState.EXCLUDED && !isFocused) {
                HarborColors.DangerRed
            } else {
                HarborColors.TextPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TvNavSidebar(
    currentDestination: HarborNavDestination,
    activeProfileName: String,
    isPrivateSession: Boolean,
    offlineFixtureMode: Boolean,
    onNavigate: (HarborNavDestination) -> Unit,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(HarborColors.Surface)
            .border(
                width = 1.dp,
                color = HarborColors.BorderSubtle,
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(HarborColors.SurfaceCard)
                    .border(1.5.dp, HarborColors.FocusCyan, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "H",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.FocusCyan,
                    fontWeight = FontWeight.Bold
                )
            }
            Column {
                Text(
                    text = "Harbor",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.TextPrimary
                )
                Text(
                    text = if (offlineFixtureMode) "Fixture Mode" else "Live Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (offlineFixtureMode) HarborColors.AccentTeal else HarborColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        HarborNavDestination.entries.forEach { destination ->
            val selected = destination == currentDestination
            TvFocusableCard(
                onClick = { onNavigate(destination) },
                modifier = Modifier.fillMaxWidth(),
                containerColor = if (selected) HarborColors.FocusCyan.copy(alpha = 0.18f) else HarborColors.Surface,
                focusedContainerColor = HarborColors.SurfaceCardFocused,
                unfocusedBorderColor = if (selected) HarborColors.FocusCyan.copy(alpha = 0.6f) else Color.Transparent,
                focusedScale = 1.02f,
                contentPadding = 10.dp
            ) { isFocused ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selected || isFocused) HarborColors.FocusCyan.copy(alpha = 0.25f)
                                else HarborColors.SurfaceCard
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = destination.badge,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected || isFocused) HarborColors.FocusCyan else HarborColors.TextSecondary
                        )
                    }
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selected || isFocused) HarborColors.TextPrimary else HarborColors.TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        TvFocusableCard(
            onClick = onSwitchProfile,
            modifier = Modifier.fillMaxWidth(),
            containerColor = if (isPrivateSession) {
                HarborColors.PrivatePurple.copy(alpha = 0.16f)
            } else {
                HarborColors.SurfaceCard
            },
            unfocusedBorderColor = if (isPrivateSession) {
                HarborColors.PrivatePurple
            } else {
                HarborColors.BorderSubtle
            },
            contentPadding = 10.dp
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = if (isPrivateSession) "Private Session" else "Profile: $activeProfileName",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Switch Profile",
                    style = MaterialTheme.typography.labelMedium,
                    color = HarborColors.TextSecondary
                )
            }
        }
    }
}

@Composable
fun TvVideoCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null
) {
    val thumbUrl = item.thumbnailReferences.firstOrNull()?.takeIf { it.isNotBlank() }

    TvFocusableCard(
        onClick = onClick,
        modifier = modifier.width(268.dp),
        focusRequester = focusRequester,
        onFocused = onFocused,
        contentPadding = 10.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(HarborColors.Background)
            ) {
                if (thumbUrl != null) {
                    val context = LocalContext.current
                    val imageRequest = remember(thumbUrl) {
                        ImageRequest.Builder(context)
                            .data(thumbUrl)
                            .size(480, 270)
                            .allowRgb565(true)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.sourceId.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.TextMuted
                        )
                    }
                }

                // Source badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(HarborColors.Scrim)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.sourceId,
                        style = MaterialTheme.typography.labelMedium,
                        color = HarborColors.FocusCyan
                    )
                }

                // Duration or Live badge
                val badgeText = when {
                    item.liveStatus == LiveStatus.LIVE -> "LIVE"
                    item.liveStatus == LiveStatus.UPCOMING -> "UPCOMING"
                    item.durationSeconds != null -> formatDuration(item.durationSeconds)
                    else -> null
                }
                if (badgeText != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (item.liveStatus == LiveStatus.LIVE) HarborColors.DangerRed
                                else HarborColors.Scrim
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (item.liveStatus == LiveStatus.LIVE) {
                                HarborColors.Background
                            } else {
                                HarborColors.TextPrimary
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = HarborColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val tagsPreview = (item.canonicalTags.ifEmpty { item.sourceTags })
                .take(3)
                .joinToString(" • ")
            Text(
                text = tagsPreview.ifEmpty { "Metadata: ${item.metadataCompleteness.name.lowercase()}" },
                style = MaterialTheme.typography.labelMedium,
                color = HarborColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TvStatusBanner(
    title: String,
    body: String,
    accentColor: Color = HarborColors.FocusCyan,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.12f))
            .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = accentColor
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = HarborColors.TextPrimary
            )
        }
    }
}

fun formatDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

fun formatDurationMs(ms: Long): String = formatDuration((ms / 1000L).toInt())
