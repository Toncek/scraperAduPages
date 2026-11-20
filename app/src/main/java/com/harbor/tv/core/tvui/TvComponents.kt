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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
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

@Suppress("DEPRECATION")
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
    var railExpanded by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        targetValue = if (railExpanded) 208.dp else 76.dp,
        animationSpec = tween(durationMillis = 160),
        label = "railWidth"
    )

    Column(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(HarborColors.Surface)
            .border(
                width = 1.dp,
                color = HarborColors.BorderSubtle,
                shape = RoundedCornerShape(0.dp)
            )
            .onFocusChanged { railExpanded = it.hasFocus }
            .padding(horizontal = 12.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (railExpanded) Arrangement.spacedBy(10.dp) else Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HarborColors.SurfaceCard)
                    .border(1.5.dp, HarborColors.FocusCyan, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "H",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.FocusCyan,
                    fontWeight = FontWeight.Bold
                )
            }
            if (railExpanded) {
                Column {
                    Text(
                        text = "Harbor",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary,
                        maxLines = 1
                    )
                    if (offlineFixtureMode) {
                        Text(
                            text = "Demo Mode",
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.AccentTeal,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        HarborNavDestination.entries.forEach { destination ->
            val selected = destination == currentDestination
            val destIcon = when (destination) {
                HarborNavDestination.HOME -> Icons.Rounded.Home
                HarborNavDestination.CATEGORIES -> Icons.Rounded.List
                HarborNavDestination.SEARCH -> Icons.Rounded.Search
                HarborNavDestination.FAVORITES -> Icons.Rounded.Favorite
                HarborNavDestination.SOURCES -> Icons.Rounded.Star
                HarborNavDestination.SETTINGS -> Icons.Rounded.Settings
            }
            TvFocusableCard(
                onClick = { onNavigate(destination) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                containerColor = if (selected) HarborColors.FocusCyan.copy(alpha = 0.16f) else HarborColors.Surface,
                focusedContainerColor = HarborColors.SurfaceCardFocused,
                focusBorderColor = HarborColors.FocusRing,
                unfocusedBorderColor = if (selected) HarborColors.FocusCyan.copy(alpha = 0.45f) else Color.Transparent,
                focusedScale = 1.03f,
                contentPadding = 10.dp
            ) { isFocused ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (railExpanded) Arrangement.spacedBy(10.dp) else Arrangement.Center
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(22.dp)
                                .shadow(6.dp, RoundedCornerShape(2.dp), ambientColor = HarborColors.FocusCyan, spotColor = HarborColors.FocusCyan)
                                .clip(RoundedCornerShape(2.dp))
                                .background(HarborColors.FocusCyan)
                        )
                    }
                    Icon(
                        imageVector = destIcon,
                        contentDescription = destination.label,
                        tint = if (selected || isFocused) HarborColors.FocusCyan else HarborColors.TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    if (railExpanded) {
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected || isFocused) HarborColors.TextPrimary else HarborColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        TvFocusableCard(
            onClick = onSwitchProfile,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            containerColor = if (isPrivateSession) {
                HarborColors.PrivatePurple.copy(alpha = 0.16f)
            } else {
                HarborColors.SurfaceCard
            },
            focusBorderColor = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.FocusRing,
            unfocusedBorderColor = if (isPrivateSession) {
                HarborColors.PrivatePurple.copy(alpha = 0.7f)
            } else {
                HarborColors.BorderSubtle
            },
            contentPadding = 10.dp
        ) { isFocused ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (railExpanded) Arrangement.spacedBy(10.dp) else Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isPrivateSession) Icons.Rounded.Lock else Icons.Rounded.Person,
                    contentDescription = "Profile",
                    tint = when {
                        isPrivateSession -> HarborColors.PrivatePurple
                        isFocused -> HarborColors.FocusCyan
                        else -> HarborColors.TextPrimary
                    },
                    modifier = Modifier.size(22.dp)
                )
                if (railExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (isPrivateSession) "Private" else activeProfileName,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Switch Profile",
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

fun friendlySourceName(sourceId: String): String {
    return when (sourceId.trim().lowercase()) {
        "eporner-api-v2" -> "Eporner"
        "porntrex-html" -> "PornTrex"
        "xhamster-html" -> "xHamster"
        "pornhub-webmaster" -> "Pornhub"
        "xvideos-html" -> "XVideos"
        "xnxx-html" -> "XNXX"
        "redtube-api" -> "RedTube"
        "example-library" -> "Demo Library"
        else -> sourceId.trim()
            .removeSuffix("-v2")
            .removeSuffix("-api")
            .removeSuffix("-html")
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase() else c.toString() }
            }
            .ifEmpty { sourceId }
    }
}

@Composable
fun TvVideoCard(
    item: CatalogItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    showSourceBadge: Boolean = true,
    progressFraction: Float? = null
) {
    val thumbUrl = item.thumbnailReferences.firstOrNull()?.takeIf { it.isNotBlank() }
    val qualityBadge = resolveQualityBadge(
        maxResolutionHeight = item.maxResolutionHeight,
        maxQualityLabel = item.maxQualityLabel,
        tags = item.canonicalTags
    )

    TvFocusableCard(
        onClick = onClick,
        modifier = modifier.width(276.dp),
        focusRequester = focusRequester,
        onFocused = onFocused,
        shape = RoundedCornerShape(14.dp),
        focusBorderColor = HarborColors.FocusCyan,
        focusedScale = 1.05f,
        contentPadding = 0.dp
    ) { isFocused ->
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
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
                            text = friendlySourceName(item.sourceId),
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.TextMuted
                        )
                    }
                }

                // Bottom gradient scrim for legible corner badges
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.48f)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xD9050810))
                            )
                        )
                )

                // Friendly Source badge (top-left)
                if (showSourceBadge) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(HarborColors.SurfaceGlass)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = friendlySourceName(item.sourceId),
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.FocusCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Max Quality badge (top-right)
                if (qualityBadge != null) {
                    val (qLabel, qColor) = qualityBadge
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(qColor.copy(alpha = 0.94f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = qLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.Background,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Duration or Live badge (bottom-right)
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
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (item.liveStatus == LiveStatus.LIVE) HarborColors.DangerRed
                                else HarborColors.SurfaceGlass
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp)
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

                // Resume progress bar along bottom edge
                if (progressFraction != null && progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomStart)
                            .background(Color(0x66000000))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(HarborColors.FocusCyan)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isFocused) HarborColors.FocusRing else HarborColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val tagsPreview = (item.canonicalTags.ifEmpty { item.sourceTags })
                    .map(::formatTagForDisplay)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(3)
                    .joinToString(" • ")
                val secondaryText = tagsPreview.ifEmpty { friendlySourceName(item.sourceId) }
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.labelMedium,
                    color = HarborColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

fun formatTagForDisplay(rawTag: String): String {
    val clean = rawTag.trim()
    if (clean.isEmpty()) return ""
    val stripped = if ('.' in clean) clean.substringAfter('.') else clean
    return stripped.replace('_', ' ')
}

fun resolveQualityBadge(
    maxResolutionHeight: Int?,
    maxQualityLabel: String?,
    tags: List<String> = emptyList()
): Pair<String, Color>? {
    val height = maxResolutionHeight ?: 0
    return when {
        height >= 2160 -> "4K" to HarborColors.Gold4K
        height >= 1440 -> "1440p" to HarborColors.QuadHdViolet
        height >= 1080 -> "1080p" to HarborColors.FocusCyan
        height >= 720 -> "720p" to HarborColors.AccentTeal
        !maxQualityLabel.isNullOrBlank() -> {
            val clean = maxQualityLabel.trim()
            val color = when {
                clean.contains("4k", ignoreCase = true) || clean.contains("2160") -> HarborColors.Gold4K
                clean.contains("1440") || clean.contains("2k", ignoreCase = true) -> HarborColors.QuadHdViolet
                clean.contains("1080") || clean.contains("fhd", ignoreCase = true) -> HarborColors.FocusCyan
                else -> HarborColors.AccentTeal
            }
            clean to color
        }
        tags.any { it.equals("quality.4k", ignoreCase = true) } -> "4K" to HarborColors.Gold4K
        tags.any { it.equals("quality.hd", ignoreCase = true) } -> "HD" to HarborColors.AccentTeal
        else -> null
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
