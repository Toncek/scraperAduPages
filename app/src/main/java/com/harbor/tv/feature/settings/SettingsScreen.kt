package com.harbor.tv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.harbor.tv.core.model.QualityPreference
import com.harbor.tv.core.model.UnknownMetadataMode
import com.harbor.tv.core.model.UserProfile
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.TvActionButton
import com.harbor.tv.core.tvui.TvButtonTone
import com.harbor.tv.core.tvui.TvFocusableCard
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.TvTagChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    activeProfile: UserProfile,
    isPrivateSession: Boolean,
    offlineFixtureMode: Boolean,
    videoPreloadEnabled: Boolean = true,
    autoPlayNextEnabled: Boolean = true,
    focusPreviewEnabled: Boolean = true,
    mediaCacheLimitMb: Int = 1536,
    mediaCacheUsedBytes: Long = 0L,
    mediaCacheMaxBytes: Long = 1536L * 1024L * 1024L,
    availableStorageBytes: Long = 0L,
    hwCodecSummary: String = "",
    statusMessage: String?,
    onTogglePrivateSession: (Boolean) -> Unit,
    onSetUnknownMetadataMode: (UnknownMetadataMode) -> Unit,
    onSetQualityPreference: (QualityPreference, Int, Int) -> Unit,
    onToggleAllowLan: (Boolean) -> Unit,
    onToggleHistoryEnabled: (Boolean) -> Unit,
    onToggleOfflineFixtureMode: (Boolean) -> Unit,
    onToggleVideoPreload: (Boolean) -> Unit = {},
    onToggleAutoPlayNext: (Boolean) -> Unit = {},
    onToggleFocusPreview: (Boolean) -> Unit = {},
    onSetMediaCacheLimitMb: (Int) -> Unit = {},
    onSetProfilePin: (String?) -> Unit,
    onClearCacheAndHistory: () -> Unit,
    onOpenPreferenceSetup: () -> Unit,
    onOpenProfileSelection: () -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var draftPin by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = "Active profile: ${activeProfile.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextSecondary
                    )
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                item {
                    TvStatusBanner(
                        title = "Settings Updated",
                        body = statusMessage,
                        accentColor = HarborColors.SuccessGreen
                    )
                }
            }

            // 1. Video & 4K Playback
            item {
                SettingsGroupCard(
                    title = "Video & 4K Playback",
                    subtitle = "Choose your preferred stream quality and hardware decoding targets.",
                    icon = Icons.Rounded.PlayArrow,
                    accent = HarborColors.Gold4K
                ) {
                    if (hwCodecSummary.isNotBlank()) {
                        Text(
                            text = hwCodecSummary,
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.FocusCyan
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QualityPreference.entries.forEach { mode ->
                            TvTagChip(
                                label = friendlyQualityModeLabel(mode),
                                selected = activeProfile.qualityPreference == mode,
                                onClick = {
                                    onSetQualityPreference(
                                        mode,
                                        activeProfile.preferredResolutionHeight,
                                        activeProfile.preferredBitrateKbps
                                    )
                                }
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf(
                            720 to "720p HD",
                            1080 to "1080p Full HD",
                            2160 to "4K Ultra HD"
                        ).forEach { (res, label) ->
                            TvTagChip(
                                label = label,
                                selected = activeProfile.preferredResolutionHeight == res,
                                onClick = {
                                    onSetQualityPreference(
                                        activeProfile.qualityPreference,
                                        res,
                                        activeProfile.preferredBitrateKbps
                                    )
                                }
                            )
                        }
                        listOf(2500, 5000, 10000).forEach { kbps ->
                            TvTagChip(
                                label = "${kbps / 1000.0} Mbps".replace(".0 ", " "),
                                selected = activeProfile.preferredBitrateKbps == kbps,
                                onClick = {
                                    onSetQualityPreference(
                                        activeProfile.qualityPreference,
                                        activeProfile.preferredResolutionHeight,
                                        kbps
                                    )
                                }
                            )
                        }
                    }
                    SettingsToggleRow(
                        title = "Auto-Play Next Video",
                        subtitle = "Automatically plays the next video in queue after a 5-second countdown.",
                        valueLabel = if (autoPlayNextEnabled) "ON" else "OFF",
                        isEnabled = autoPlayNextEnabled,
                        accent = HarborColors.Gold4K,
                        onClick = { onToggleAutoPlayNext(!autoPlayNextEnabled) }
                    )
                }
            }

            // 2. Storage & Preloading
            item {
                val safeMaxBytes = mediaCacheMaxBytes.coerceAtLeast(1L)
                val usedFraction = (mediaCacheUsedBytes.toFloat() / safeMaxBytes.toFloat()).coerceIn(0f, 1f)
                val usedMb = mediaCacheUsedBytes.toDouble() / (1024.0 * 1024.0)
                val maxGb = safeMaxBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                val freeGb = availableStorageBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

                SettingsGroupCard(
                    title = "Storage & Preloading",
                    subtitle = "Manage TV storage cache size and background video preloading.",
                    icon = Icons.Rounded.Refresh,
                    accent = HarborColors.AccentTeal
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(
                            progress = { usedFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = HarborColors.AccentTeal,
                            trackColor = HarborColors.Background
                        )
                        Text(
                            text = String.format(
                                "%.1f MB used / %.2f GB limit • %.2f GB free on TV",
                                usedMb,
                                maxGb,
                                freeGb
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HarborColors.TextSecondary
                        )
                    }

                    SettingsToggleRow(
                        title = "Background Video Preloading",
                        subtitle = "Preloads opening segments on Video Details for faster playback start.",
                        valueLabel = if (videoPreloadEnabled) "ON" else "OFF",
                        isEnabled = videoPreloadEnabled,
                        accent = HarborColors.AccentTeal,
                        onClick = { onToggleVideoPreload(!videoPreloadEnabled) }
                    )

                    SettingsToggleRow(
                        title = "Thumbnail Storyboard Preview",
                        subtitle = "Cycles low-memory storyboard thumbnails when a video card stays focused.",
                        valueLabel = if (focusPreviewEnabled) "ON" else "OFF",
                        isEnabled = focusPreviewEnabled,
                        accent = HarborColors.AccentTeal,
                        onClick = { onToggleFocusPreview(!focusPreviewEnabled) }
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cache Limit:",
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.TextSecondary
                        )
                        listOf(
                            1024 to "1.0 GB",
                            1536 to "1.5 GB",
                            2048 to "2.0 GB"
                        ).forEach { (mb, label) ->
                            TvTagChip(
                                label = label,
                                selected = mediaCacheLimitMb == mb,
                                onClick = { onSetMediaCacheLimitMb(mb) }
                            )
                        }
                    }

                    TvActionButton(
                        label = "Clear Cache & Watch History",
                        tone = TvButtonTone.DANGER,
                        onClick = onClearCacheAndHistory
                    )
                }
            }

            // 3. Privacy & Profiles
            item {
                val isStrict = activeProfile.unknownMetadataMode == UnknownMetadataMode.STRICT
                SettingsGroupCard(
                    title = "Privacy & Profiles",
                    subtitle = "Control private browsing, watch history, content filtering, and PIN locks.",
                    icon = Icons.Rounded.Lock,
                    accent = HarborColors.PrivatePurple
                ) {
                    SettingsToggleRow(
                        title = "Private Session Mode",
                        subtitle = "Skips saving watch history, resume positions, and video cache this session.",
                        valueLabel = if (isPrivateSession) "ON" else "OFF",
                        isEnabled = isPrivateSession,
                        accent = HarborColors.PrivatePurple,
                        onClick = { onTogglePrivateSession(!isPrivateSession) }
                    )

                    SettingsToggleRow(
                        title = "Watch History & Resume",
                        subtitle = "Saves playback progress locally when Private Session is off.",
                        valueLabel = if (activeProfile.historyEnabled) "ON" else "OFF",
                        isEnabled = activeProfile.historyEnabled,
                        accent = HarborColors.FocusCyan,
                        onClick = { onToggleHistoryEnabled(!activeProfile.historyEnabled) }
                    )

                    SettingsToggleRow(
                        title = "Strict Metadata Filter",
                        subtitle = if (isStrict) {
                            "Strict: hides videos missing complete tags or classification."
                        } else {
                            "Standard: shows videos unless they match an excluded tag."
                        },
                        valueLabel = if (isStrict) "STRICT" else "STANDARD",
                        isEnabled = isStrict,
                        accent = HarborColors.FocusCyan,
                        onClick = {
                            onSetUnknownMetadataMode(
                                if (isStrict) UnknownMetadataMode.STANDARD else UnknownMetadataMode.STRICT
                            )
                        }
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TvActionButton(
                            label = if (activeProfile.pinHash != null) "Change or Remove PIN" else "Set Profile PIN",
                            tone = TvButtonTone.PRIMARY,
                            onClick = {
                                draftPin = ""
                                showPinSetupDialog = true
                            }
                        )
                        TvActionButton(
                            label = "Switch Profile",
                            onClick = onOpenProfileSelection
                        )
                        TvActionButton(
                            label = "Tag Preferences",
                            onClick = onOpenPreferenceSetup
                        )
                    }
                }
            }

            // 4. Sources & Advanced
            item {
                SettingsGroupCard(
                    title = "Sources & Advanced",
                    subtitle = "Manage installed websites, offline sample mode, and local network access.",
                    icon = Icons.Rounded.Settings,
                    accent = HarborColors.FocusCyan
                ) {
                    SettingsToggleRow(
                        title = "Offline Sample Mode",
                        subtitle = "Browse built-in sample catalogs safely without live network requests.",
                        valueLabel = if (offlineFixtureMode) "FIXTURES ON" else "LIVE NETWORK",
                        isEnabled = offlineFixtureMode,
                        accent = HarborColors.AccentTeal,
                        onClick = { onToggleOfflineFixtureMode(!offlineFixtureMode) }
                    )

                    SettingsToggleRow(
                        title = "Allow Local Network (LAN) Sources",
                        subtitle = "Permits user-owned home-network media servers on private IP addresses.",
                        valueLabel = if (activeProfile.allowLanSources) "ALLOWED" else "BLOCKED",
                        isEnabled = activeProfile.allowLanSources,
                        accent = HarborColors.WarningAmber,
                        onClick = { onToggleAllowLan(!activeProfile.allowLanSources) }
                    )

                    TvActionButton(
                        label = "Manage Sources",
                        tone = TvButtonTone.PRIMARY,
                        onClick = onOpenSources
                    )
                }
            }
        }

        if (showPinSetupDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HarborColors.Scrim),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .width(420.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(HarborColors.Surface)
                        .border(2.dp, HarborColors.FocusCyan, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Configure Profile PIN Lock",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = "Casual access guard only; does not protect against device owners or administrators.",
                        style = MaterialTheme.typography.labelMedium,
                        color = HarborColors.TextSecondary
                    )
                    Text(
                        text = if (draftPin.isEmpty()) "Enter new 4-6 digit PIN" else "*".repeat(draftPin.length),
                        style = MaterialTheme.typography.headlineSmall,
                        color = HarborColors.FocusCyan
                    )
                    listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("Clear", "0", "Del")
                    ).forEach { rowDigits ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowDigits.forEach { digit ->
                                TvActionButton(
                                    label = digit,
                                    onClick = {
                                        when (digit) {
                                            "Clear" -> draftPin = ""
                                            "Del" -> if (draftPin.isNotEmpty()) draftPin = draftPin.dropLast(1)
                                            else -> if (draftPin.length < 6) draftPin += digit
                                        }
                                    },
                                    modifier = Modifier.width(105.dp)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton(
                            label = "Save PIN",
                            tone = TvButtonTone.PRIMARY,
                            enabled = draftPin.length >= 4,
                            onClick = {
                                onSetProfilePin(draftPin)
                                showPinSetupDialog = false
                            }
                        )
                        if (activeProfile.pinHash != null) {
                            TvActionButton(
                                label = "Remove PIN",
                                tone = TvButtonTone.DANGER,
                                onClick = {
                                    onSetProfilePin(null)
                                    showPinSetupDialog = false
                                }
                            )
                        }
                        TvActionButton(
                            label = "Cancel",
                            onClick = { showPinSetupDialog = false }
                        )
                    }
                }
            }
        }
    }
}

private fun friendlyQualityModeLabel(mode: QualityPreference): String {
    return when (mode) {
        QualityPreference.HIGHEST_AVAILABLE -> "Highest Available"
        QualityPreference.PREFERRED_RESOLUTION -> "Preferred Resolution"
        QualityPreference.PREFERRED_BITRATE -> "Preferred Bitrate"
        QualityPreference.MANUAL -> "Manual"
        QualityPreference.AUTO_FALLBACK -> "Auto Fallback"
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HarborColors.SurfaceCard)
            .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.16f))
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
        }
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    valueLabel: String,
    isEnabled: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    TvFocusableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        containerColor = HarborColors.SurfaceElevated,
        unfocusedBorderColor = if (isEnabled) accent.copy(alpha = 0.7f) else HarborColors.BorderSubtle
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = HarborColors.TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.titleMedium,
                color = if (isEnabled) accent else HarborColors.TextSecondary
            )
        }
    }
}
