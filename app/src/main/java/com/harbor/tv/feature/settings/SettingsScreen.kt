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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Settings (${activeProfile.name})",
                            style = MaterialTheme.typography.headlineMedium,
                            color = HarborColors.TextPrimary
                        )
                        Text(
                            text = "Local privacy, metadata strictness, stream quality, network permissions, and profile lock.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HarborColors.TextSecondary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton(label = "Tag Preferences", onClick = onOpenPreferenceSetup)
                        TvActionButton(label = "Profiles", onClick = onOpenProfileSelection)
                        TvActionButton(label = "Sources", onClick = onOpenSources)
                    }
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

            item {
                SettingsToggleRow(
                    title = "Private Session Mode",
                    subtitle = "Avoids persisting search history, watch history, resume positions, and media cache. Does not protect from network operators, source providers, or device administrators.",
                    valueLabel = if (isPrivateSession) "ON" else "OFF",
                    isEnabled = isPrivateSession,
                    accent = HarborColors.PrivatePurple,
                    onClick = { onTogglePrivateSession(!isPrivateSession) }
                )
            }

            item {
                val isStrict = activeProfile.unknownMetadataMode == UnknownMetadataMode.STRICT
                SettingsToggleRow(
                    title = "Unknown Metadata Mode",
                    subtitle = if (isStrict) {
                        "Strict: items lacking complete classification are hidden (depends on source metadata and mappings)."
                    } else {
                        "Standard: items with unknown optional tags remain eligible unless excluded."
                    },
                    valueLabel = activeProfile.unknownMetadataMode.name,
                    isEnabled = isStrict,
                    accent = HarborColors.FocusCyan,
                    onClick = {
                        onSetUnknownMetadataMode(
                            if (isStrict) UnknownMetadataMode.STANDARD else UnknownMetadataMode.STRICT
                        )
                    }
                )
            }

            item {
                SettingsToggleRow(
                    title = "Offline Fixture-Backed Browsing Mode",
                    subtitle = "Serves bundled offline HTML/JSON fixtures for installed demo sources so you can browse and inspect out of the box without live network access.",
                    valueLabel = if (offlineFixtureMode) "FIXTURES ON" else "LIVE NETWORK",
                    isEnabled = offlineFixtureMode,
                    accent = HarborColors.AccentTeal,
                    onClick = { onToggleOfflineFixtureMode(!offlineFixtureMode) }
                )
            }

            item {
                SettingsToggleRow(
                    title = "Allow LAN / Local Network Sources",
                    subtitle = "Explicit permission mode required before user-owned local network sources may resolve private/loopback/link-local addresses.",
                    valueLabel = if (activeProfile.allowLanSources) "ALLOWED" else "BLOCKED",
                    isEnabled = activeProfile.allowLanSources,
                    accent = HarborColors.WarningAmber,
                    onClick = { onToggleAllowLan(!activeProfile.allowLanSources) }
                )
            }

            item {
                SettingsToggleRow(
                    title = "Local Watch History & Resume Position",
                    subtitle = "Saves watch progress locally when Private Session is OFF.",
                    valueLabel = if (activeProfile.historyEnabled) "ENABLED" else "DISABLED",
                    isEnabled = activeProfile.historyEnabled,
                    accent = HarborColors.FocusCyan,
                    onClick = { onToggleHistoryEnabled(!activeProfile.historyEnabled) }
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Playback Quality Preference",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QualityPreference.entries.forEach { mode ->
                            TvTagChip(
                                label = mode.name.replace('_', ' '),
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(720, 1080, 2160).forEach { res ->
                            TvTagChip(
                                label = "${res}p Target",
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
                                label = "${kbps} kbps",
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
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Persistent Storage & Video Preloading",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    val usedMb = mediaCacheUsedBytes.toDouble() / (1024.0 * 1024.0)
                    val maxGb = mediaCacheMaxBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                    val freeGb = availableStorageBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                    Text(
                        text = String.format(
                            "ROM Video Cache (noBackupFilesDir): %.1f MB used / %.2f GB limit • Free Storage: %.2f GB",
                            usedMb,
                            maxGb,
                            freeGb
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.AccentTeal
                    )
                    if (hwCodecSummary.isNotBlank()) {
                        Text(
                            text = hwCodecSummary,
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.FocusCyan
                        )
                    }
                    SettingsToggleRow(
                        title = "Background Video Preloading to ROM",
                        subtitle = "Preloads opening segments (24 MB) on Video Details into persistent ROM storage when Private Session is OFF.",
                        valueLabel = if (videoPreloadEnabled) "ON" else "OFF",
                        isEnabled = videoPreloadEnabled,
                        accent = HarborColors.AccentTeal,
                        onClick = { onToggleVideoPreload(!videoPreloadEnabled) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    TvActionButton(
                        label = if (activeProfile.pinHash != null) "Change / Remove PIN Lock" else "Set Profile PIN Lock",
                        tone = TvButtonTone.PRIMARY,
                        onClick = {
                            draftPin = ""
                            showPinSetupDialog = true
                        }
                    )
                    TvActionButton(
                        label = "Clear Cache & Watch History",
                        tone = TvButtonTone.DANGER,
                        onClick = onClearCacheAndHistory
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

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    valueLabel: String,
    isEnabled: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    TvFocusableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        unfocusedBorderColor = if (isEnabled) accent.copy(alpha = 0.7f) else HarborColors.BorderSubtle
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.titleMedium,
                color = if (isEnabled) accent else HarborColors.TextSecondary
            )
        }
    }
}
