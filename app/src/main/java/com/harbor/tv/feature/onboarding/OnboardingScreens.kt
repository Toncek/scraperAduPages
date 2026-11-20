package com.harbor.tv.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.harbor.tv.core.model.PreferenceConfig
import com.harbor.tv.core.model.QualityPreference
import com.harbor.tv.core.model.TagPreferenceState
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
fun WelcomeScreen(
    onContinueToProfiles: () -> Unit,
    onSkipToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { primaryFocus.requestFocus() }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(HarborColors.FocusCyan.copy(alpha = 0.16f))
                        .border(1.dp, HarborColors.FocusCyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = HarborColors.FocusCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Welcome to Harbor",
                        style = MaterialTheme.typography.displaySmall,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = "Your living-room TV browser for curated sources, smooth 4K playback, and complete local control.",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextSecondary
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                WelcomePrincipleCard(
                    icon = Icons.Rounded.Star,
                    accentColor = HarborColors.FocusCyan,
                    title = "Declarative Sources",
                    body = "Add supported sites using schema-checked JSON or YAML files—never executable plugins or arbitrary JavaScript. Every domain requires your approval.",
                    modifier = Modifier.weight(1f)
                )
                WelcomePrincipleCard(
                    icon = Icons.Rounded.Lock,
                    accentColor = HarborColors.PrivatePurple,
                    title = "Local-Only Privacy",
                    body = "No cloud accounts, no analytics, no autoplay previews, no casting, and no launcher recommendations. Preferences, favorites, and optional history stay on this TV.",
                    modifier = Modifier.weight(1f)
                )
                WelcomePrincipleCard(
                    icon = Icons.Rounded.Home,
                    accentColor = HarborColors.AccentTeal,
                    title = "Living-Room Ready",
                    body = "Built from the ground up for D-pad remote control with neutral TV launcher branding. Neutral branding is cosmetic only and does not hide the app on your device.",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvActionButton(
                    label = "Choose Profile & Preferences",
                    tone = TvButtonTone.PRIMARY,
                    focusRequester = primaryFocus,
                    onClick = onContinueToProfiles
                )
                TvActionButton(
                    label = "Browse with Default Profile",
                    tone = TvButtonTone.SECONDARY,
                    onClick = onSkipToHome
                )
            }
        }
    }
}

@Composable
private fun WelcomePrincipleCard(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(HarborColors.SurfaceCard)
            .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(16.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = accentColor
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = HarborColors.TextPrimary
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSelectionScreen(
    profiles: List<UserProfile>,
    activeProfileId: String,
    isPrivateSession: Boolean,
    errorMessage: String?,
    onSelectProfile: (UserProfile, String?) -> Unit,
    onVerifyPin: (UserProfile, String) -> Boolean,
    onCreateProfile: (String, String?) -> Unit,
    onTogglePrivateSession: (Boolean) -> Unit,
    onContinueToPreferences: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingPinProfile by remember { mutableStateOf<UserProfile?>(null) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var showCreateForm by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var newProfilePin by remember { mutableStateOf("") }

    val firstCardFocus = remember { FocusRequester() }
    val pinFirstDigitFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstCardFocus.requestFocus() }
    }
    LaunchedEffect(pendingPinProfile) {
        if (pendingPinProfile != null) {
            runCatching { pinFirstDigitFocus.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 42.dp, vertical = 30.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(HarborColors.FocusCyan.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = HarborColors.FocusCyan,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Who's Watching?",
                                style = MaterialTheme.typography.headlineMedium,
                                color = HarborColors.TextPrimary
                            )
                            Text(
                                text = "Switching profiles clears visible catalog results right away and applies your own filters and quality target.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = HarborColors.TextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TvActionButton(
                            label = if (showCreateForm) "Hide New Profile" else "+ New Profile",
                            onClick = { showCreateForm = !showCreateForm }
                        )
                        TvActionButton(
                            label = "Continue to Preferences",
                            tone = TvButtonTone.PRIMARY,
                            onClick = onContinueToPreferences
                        )
                        TvActionButton(
                            label = "Back",
                            onClick = onBack
                        )
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                item {
                    TvStatusBanner(
                        title = "Profile Notice",
                        body = errorMessage,
                        accentColor = HarborColors.DangerRed
                    )
                }
            }

            item {
                TvFocusableCard(
                    onClick = { onTogglePrivateSession(!isPrivateSession) },
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = if (isPrivateSession) {
                        HarborColors.PrivatePurple.copy(alpha = 0.16f)
                    } else {
                        HarborColors.SurfaceCard
                    },
                    unfocusedBorderColor = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.BorderSubtle
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        (if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.TextSecondary)
                                            .copy(alpha = 0.16f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.TextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Private Session: ${if (isPrivateSession) "ON" else "OFF"}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.TextPrimary
                                )
                                Text(
                                    text = "Avoids saving search history, watch history, resume positions, and disk video cache. Does not hide traffic from your network operator, source providers, or device administrators.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HarborColors.TextSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    (if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.FocusCyan)
                                        .copy(alpha = 0.16f)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isPrivateSession) "ACTIVE" else "ENABLE",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isPrivateSession) HarborColors.PrivatePurple else HarborColors.FocusCyan
                            )
                        }
                    }
                }
            }

            if (showCreateForm) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(HarborColors.Surface)
                            .border(1.dp, HarborColors.FocusCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = HarborColors.FocusCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Create Local Profile",
                                style = MaterialTheme.typography.titleLarge,
                                color = HarborColors.TextPrimary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            OutlinedTextField(
                                value = newProfileName,
                                onValueChange = { newProfileName = it },
                                label = { Text("Profile Name") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = HarborColors.TextPrimary,
                                    unfocusedTextColor = HarborColors.TextPrimary,
                                    focusedBorderColor = HarborColors.FocusCyan,
                                    unfocusedBorderColor = HarborColors.BorderSubtle
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = newProfilePin,
                                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) newProfilePin = it },
                                label = { Text("Optional PIN (up to 6 digits)") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = HarborColors.TextPrimary,
                                    unfocusedTextColor = HarborColors.TextPrimary,
                                    focusedBorderColor = HarborColors.FocusCyan,
                                    unfocusedBorderColor = HarborColors.BorderSubtle
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf("Guest", "Family", "Night").forEach { preset ->
                                TvTagChip(
                                    label = "Preset: $preset",
                                    onClick = { newProfileName = preset }
                                )
                            }
                            TvActionButton(
                                label = "Create Profile",
                                tone = TvButtonTone.PRIMARY,
                                onClick = {
                                    onCreateProfile(newProfileName, newProfilePin.ifBlank { null })
                                    newProfileName = ""
                                    newProfilePin = ""
                                    showCreateForm = false
                                }
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Local Profiles",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.TextPrimary
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(profiles.indices.toList()) { idx ->
                        val profile = profiles[idx]
                        val isActive = profile.id == activeProfileId
                        TvFocusableCard(
                            onClick = {
                                if (profile.pinHash != null) {
                                    pendingPinProfile = profile
                                    enteredPin = ""
                                    pinError = null
                                } else {
                                    onSelectProfile(profile, null)
                                }
                            },
                            modifier = Modifier.width(300.dp),
                            focusRequester = if (idx == 0) firstCardFocus else null,
                            unfocusedBorderColor = if (isActive) HarborColors.FocusCyan else HarborColors.BorderSubtle
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    (if (isActive) HarborColors.FocusCyan else HarborColors.SurfaceElevated)
                                                        .copy(alpha = if (isActive) 0.2f else 1f)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (profile.pinHash != null) Icons.Rounded.Lock else Icons.Rounded.Person,
                                                contentDescription = null,
                                                tint = if (isActive) HarborColors.FocusCyan else HarborColors.TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Text(
                                            text = profile.name,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = if (isActive) HarborColors.FocusCyan else HarborColors.TextPrimary
                                        )
                                    }
                                    if (isActive) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = HarborColors.SuccessGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "ACTIVE",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = HarborColors.SuccessGreen
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${profile.unknownMetadataMode.name} filter • ${profile.qualityPreference.name.replace('_', ' ')}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = HarborColors.TextSecondary
                                )
                                Text(
                                    text = if (profile.pinHash != null) {
                                        "PIN Lock: Enabled (casual access guard)"
                                    } else {
                                        "PIN Lock: Off"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (profile.pinHash != null) HarborColors.WarningAmber else HarborColors.TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        // D-pad PIN Pad modal overlay
        val targetPinProfile = pendingPinProfile
        if (targetPinProfile != null) {
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
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = HarborColors.FocusCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Enter PIN for ${targetPinProfile.name}",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = "Optional PIN restricts casual profile switching only; it is not strong security against device admins.",
                        style = MaterialTheme.typography.labelMedium,
                        color = HarborColors.TextSecondary
                    )
                    Text(
                        text = "*".repeat(enteredPin.length).ifEmpty { "Enter digits" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = HarborColors.FocusCyan
                    )
                    if (pinError != null) {
                        Text(
                            text = pinError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = HarborColors.DangerRed
                        )
                    }
                    listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("Clear", "0", "Del")
                    ).forEachIndexed { rowIdx, rowDigits ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowDigits.forEachIndexed { colIdx, keyLabel ->
                                TvActionButton(
                                    label = keyLabel,
                                    focusRequester = if (rowIdx == 0 && colIdx == 0) pinFirstDigitFocus else null,
                                    onClick = {
                                        pinError = null
                                        when (keyLabel) {
                                            "Clear" -> enteredPin = ""
                                            "Del" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                            else -> if (enteredPin.length < 6) enteredPin += keyLabel
                                        }
                                    },
                                    modifier = Modifier.width(105.dp)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton(
                            label = "Unlock",
                            tone = TvButtonTone.PRIMARY,
                            onClick = {
                                if (onVerifyPin(targetPinProfile, enteredPin)) {
                                    pendingPinProfile = null
                                    onSelectProfile(targetPinProfile, enteredPin)
                                } else {
                                    pinError = "Incorrect PIN"
                                    enteredPin = ""
                                }
                            }
                        )
                        TvActionButton(
                            label = "Cancel",
                            onClick = { pendingPinProfile = null }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreferenceSetupScreen(
    activeProfile: UserProfile,
    preferences: PreferenceConfig,
    onCycleTag: (String) -> Unit,
    onSetUnknownMetadataMode: (UnknownMetadataMode) -> Unit,
    onSetQualityPreference: (QualityPreference) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val defaultTags = remember {
        listOf(
            "genre.documentary",
            "topic.nature",
            "genre.educational",
            "language.en",
            "quality.hd",
            "quality.4k",
            "topic.science",
            "topic.live"
        )
    }
    val allTags = (defaultTags + preferences.tagPreferences.keys).distinct()
    var customTagInput by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 42.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(HarborColors.FocusCyan.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = HarborColors.FocusCyan,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Viewing Preferences (${activeProfile.name})",
                            style = MaterialTheme.typography.headlineMedium,
                            color = HarborColors.TextPrimary
                        )
                        Text(
                            text = "Hidden tags and filters apply everywhere before thumbnails load—across Home, Categories, Search, Favorites, History, and Playback.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HarborColors.TextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvActionButton(
                        label = "Save & Browse",
                        tone = TvButtonTone.PRIMARY,
                        focusRequester = firstFocus,
                        onClick = onFinish
                    )
                    TvActionButton(
                        label = "Back",
                        onClick = onBack
                    )
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(HarborColors.SurfaceCard)
                    .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Topic & Tag Rules",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.TextPrimary
                )
                Text(
                    text = "Press OK on any tag to cycle: Neutral -> Preferred (+) -> Excluded (x).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    allTags.forEach { tag ->
                        val state = preferences.tagPreferences[tag] ?: TagPreferenceState.NEUTRAL
                        TvTagChip(
                            label = tag,
                            preferenceState = state,
                            onClick = { onCycleTag(tag) }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customTagInput,
                        onValueChange = { customTagInput = it },
                        label = { Text("Add custom canonical or source tag") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = HarborColors.TextPrimary,
                            unfocusedTextColor = HarborColors.TextPrimary,
                            focusedBorderColor = HarborColors.FocusCyan,
                            unfocusedBorderColor = HarborColors.BorderSubtle
                        ),
                        modifier = Modifier.width(340.dp)
                    )
                    TvActionButton(
                        label = "Add & Cycle",
                        onClick = {
                            if (customTagInput.isNotBlank()) {
                                onCycleTag(customTagInput.trim())
                                customTagInput = ""
                            }
                        }
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Unknown Metadata Policy",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.TextPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val isStandard = activeProfile.unknownMetadataMode == UnknownMetadataMode.STANDARD
                    TvFocusableCard(
                        onClick = { onSetUnknownMetadataMode(UnknownMetadataMode.STANDARD) },
                        modifier = Modifier.weight(1f),
                        unfocusedBorderColor = if (isStandard) HarborColors.FocusCyan else HarborColors.BorderSubtle
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isStandard) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = if (isStandard) HarborColors.FocusCyan else HarborColors.TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Standard Mode${if (isStandard) " • Active" else ""}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isStandard) HarborColors.FocusCyan else HarborColors.TextPrimary
                                )
                            }
                            Text(
                                text = "Videos with unmapped optional tags remain visible unless an exclusion rule matches.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = HarborColors.TextSecondary
                            )
                        }
                    }

                    val isStrict = activeProfile.unknownMetadataMode == UnknownMetadataMode.STRICT
                    TvFocusableCard(
                        onClick = { onSetUnknownMetadataMode(UnknownMetadataMode.STRICT) },
                        modifier = Modifier.weight(1f),
                        unfocusedBorderColor = if (isStrict) HarborColors.FocusCyan else HarborColors.BorderSubtle
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isStrict) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = if (isStrict) HarborColors.FocusCyan else HarborColors.TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Strict Mode${if (isStrict) " • Active" else ""}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isStrict) HarborColors.FocusCyan else HarborColors.TextPrimary
                                )
                            }
                            Text(
                                text = "Videos lacking complete classification are hidden. Classification depends on source metadata and taxonomy mappings.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = HarborColors.TextSecondary
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = HarborColors.Gold4K,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Default Stream Quality Preference",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QualityPreference.entries.forEach { mode ->
                        TvTagChip(
                            label = mode.name.replace('_', ' '),
                            selected = activeProfile.qualityPreference == mode,
                            onClick = { onSetQualityPreference(mode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppStartupPinLockScreen(
    errorMessage: String?,
    onSubmitPin: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var enteredPin by remember { mutableStateOf("") }
    val firstDigitFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { firstDigitFocus.requestFocus() }
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            enteredPin = ""
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .onPreviewKeyEvent { event ->
                val digitChar = remoteKeyToDigit(event.key)
                if (digitChar != null) {
                    if (event.type == KeyEventType.KeyUp && enteredPin.length < 6) {
                        enteredPin += digitChar
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.key == Key.Backspace) {
                    if (event.type == KeyEventType.KeyUp && enteredPin.isNotEmpty()) {
                        enteredPin = enteredPin.dropLast(1)
                    }
                    return@onPreviewKeyEvent true
                }
                false
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                    enteredPin.length in 4..6
                ) {
                    onSubmitPin(enteredPin)
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(HarborColors.Surface)
                .border(1.5.dp, HarborColors.FocusCyan.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(HarborColors.FocusCyan.copy(alpha = 0.16f))
                    .border(1.dp, HarborColors.FocusCyan.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Locked",
                    tint = HarborColors.FocusCyan,
                    modifier = Modifier.size(30.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Harbor is Locked",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = HarborColors.TextPrimary
                )
                Text(
                    text = "Enter your 4–6 digit startup PIN to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }

            // Masked PIN dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(6) { idx ->
                    val filled = idx < enteredPin.length
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (filled) HarborColors.FocusCyan else HarborColors.SurfaceCard
                            )
                            .border(
                                width = 1.5.dp,
                                color = if (filled) HarborColors.FocusCyan else HarborColors.BorderSubtle,
                                shape = RoundedCornerShape(50)
                            )
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.DangerRed
                )
            }

            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("Clear", "0", "Del")
            ).forEachIndexed { rowIdx, rowDigits ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowDigits.forEachIndexed { colIdx, keyLabel ->
                        TvActionButton(
                            label = keyLabel,
                            focusRequester = if (rowIdx == 0 && colIdx == 0) firstDigitFocus else null,
                            onClick = {
                                when (keyLabel) {
                                    "Clear" -> enteredPin = ""
                                    "Del" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                    else -> if (enteredPin.length < 6) enteredPin += keyLabel
                                }
                            },
                            modifier = Modifier.width(114.dp)
                        )
                    }
                }
            }

            TvActionButton(
                label = "Unlock",
                tone = TvButtonTone.PRIMARY,
                enabled = enteredPin.length in 4..6,
                onClick = { onSubmitPin(enteredPin) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun remoteKeyToDigit(key: Key): Char? {
    return when (key) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }
}
