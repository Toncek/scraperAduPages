package com.harbor.tv.feature.sources

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
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harbor.tv.ApprovalReviewState
import com.harbor.tv.BundledDefinitionSummary
import com.harbor.tv.core.model.DefinitionLifecycleState
import com.harbor.tv.core.model.MagicPhWebsiteProfile
import com.harbor.tv.core.model.SourceDiagnosticsReport
import com.harbor.tv.core.model.SourceRuntimeHealth
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.TvActionButton
import com.harbor.tv.core.tvui.TvButtonTone
import com.harbor.tv.core.tvui.TvFocusableCard
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.TvTagChip
import com.harbor.tv.core.tvui.friendlySourceName
import com.harbor.tv.domain.sources.MagicPhSettingsAdapter
import com.harbor.tv.domain.sources.SourceRegistry
import com.harbor.tv.engine.definition.ValidationSeverity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourcesScreen(
    registeredSources: List<SourceRegistry.RegisteredSource>,
    bundledDefinitions: List<BundledDefinitionSummary>,
    magicPhProfiles: List<MagicPhWebsiteProfile>,
    sourceHealthMap: Map<String, SourceRuntimeHealth> = emptyMap(),
    statusMessage: String?,
    errorMessage: String?,
    lastFocusedKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onOpenImport: () -> Unit,
    onLoadBundledDefinition: (String) -> Unit,
    onReviewApproval: (SourceRegistry.RegisteredSource) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onRollback: (String) -> Unit,
    onDiagnose: (SourceRegistry.RegisteredSource) -> Unit,
    onRemove: (String) -> Unit,
    onEnableAllBundled: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val restoreRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String): FocusRequester =
        restoreRequesters.getOrPut(key) { FocusRequester() }

    LaunchedEffect(lastFocusedKey) {
        val target = lastFocusedKey ?: return@LaunchedEffect
        restoreRequesters[target]?.let { req -> runCatching { req.requestFocus() } }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 32.dp, vertical = 24.dp),
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
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = HarborColors.FocusCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Sources & Site Registry",
                            style = MaterialTheme.typography.headlineMedium,
                            color = HarborColors.TextPrimary
                        )
                        Text(
                            text = "Manage validated JSON/YAML source definitions, approvals, rollbacks, and offline fixture diagnostics.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HarborColors.TextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvActionButton(
                        label = "Enable All Bundled",
                        tone = TvButtonTone.ACCENT,
                        onClick = onEnableAllBundled
                    )
                    TvActionButton(
                        label = "+ Import Definition",
                        tone = TvButtonTone.PRIMARY,
                        onClick = onOpenImport
                    )
                }
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            item {
                TvStatusBanner(
                    title = "Source Update",
                    body = statusMessage,
                    accentColor = HarborColors.SuccessGreen
                )
            }
        }
        if (!errorMessage.isNullOrBlank()) {
            item {
                TvStatusBanner(
                    title = "Source Error",
                    body = errorMessage,
                    accentColor = HarborColors.DangerRed
                )
            }
        }

        // Registered Sources
        item {
            Text(
                text = "Registered Sources (${registeredSources.size})",
                style = MaterialTheme.typography.titleLarge,
                color = HarborColors.TextPrimary
            )
        }

        if (registeredSources.isEmpty()) {
            item {
                TvStatusBanner(
                    title = "No Definitions Imported",
                    body = "Select any bundled YAML definition below or open Definition Import to validate and approve a source.",
                    accentColor = HarborColors.FocusCyan
                )
            }
        } else {
            items(registeredSources, key = { it.record.sourceId }) { src ->
                val state = src.lifecycleState
                val stateColor = when (state) {
                    DefinitionLifecycleState.ENABLED -> HarborColors.SuccessGreen
                    DefinitionLifecycleState.AWAITING_APPROVAL,
                    DefinitionLifecycleState.PENDING_UPDATE -> HarborColors.WarningAmber
                    DefinitionLifecycleState.BROKEN,
                    DefinitionLifecycleState.INCOMPATIBLE -> HarborColors.DangerRed
                    else -> HarborColors.TextSecondary
                }
                val stateIcon = when (state) {
                    DefinitionLifecycleState.ENABLED -> Icons.Rounded.CheckCircle
                    DefinitionLifecycleState.AWAITING_APPROVAL,
                    DefinitionLifecycleState.PENDING_UPDATE,
                    DefinitionLifecycleState.BROKEN,
                    DefinitionLifecycleState.INCOMPATIBLE -> Icons.Rounded.Warning
                    else -> Icons.Rounded.Info
                }
                val def = src.definition
                val key = "reg:${src.record.sourceId}"
                val displayName = def?.source?.name ?: friendlySourceName(src.record.sourceId)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, stateColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
                                text = displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = HarborColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${friendlySourceName(src.record.sourceId)} (${src.record.sourceId}) • v${src.record.version}" +
                                    (src.record.previousVersion?.let { " • Rollback: v$it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = HarborColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            sourceHealthMap[src.record.sourceId]?.let { health ->
                                val healthColor = when {
                                    health.isHealthy -> HarborColors.SuccessGreen
                                    state == DefinitionLifecycleState.BROKEN -> HarborColors.DangerRed
                                    else -> HarborColors.WarningAmber
                                }
                                val healthLabel = if (health.isHealthy) {
                                    buildString {
                                        append("Healthy")
                                        health.lastLatencyMs?.let { append(" • ${it}ms") }
                                        if (health.lastItemCount > 0) append(" • ${health.lastItemCount} items")
                                    }
                                } else {
                                    buildString {
                                        append("Issue")
                                        health.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                                            append(" • ${err.take(36)}")
                                        }
                                    }
                                }
                                StatusPillBadge(
                                    label = healthLabel,
                                    icon = if (health.isHealthy) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                                    color = healthColor
                                )
                            }
                            StatusPillBadge(
                                label = state.name.replace('_', ' '),
                                icon = stateIcon,
                                color = stateColor
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state == DefinitionLifecycleState.AWAITING_APPROVAL ||
                            state == DefinitionLifecycleState.PENDING_UPDATE
                        ) {
                            TvActionButton(
                                label = "Review & Approve",
                                tone = TvButtonTone.PRIMARY,
                                focusRequester = requesterFor(key),
                                onFocused = { onFocusKeyChanged(key) },
                                onClick = { onReviewApproval(src) }
                            )
                        } else if (state == DefinitionLifecycleState.ENABLED) {
                            TvActionButton(
                                label = "Disable",
                                focusRequester = requesterFor(key),
                                onFocused = { onFocusKeyChanged(key) },
                                onClick = { onToggleEnabled(src.record.sourceId, false) }
                            )
                        } else {
                            TvActionButton(
                                label = "Enable",
                                tone = TvButtonTone.ACCENT,
                                focusRequester = requesterFor(key),
                                onFocused = { onFocusKeyChanged(key) },
                                onClick = { onToggleEnabled(src.record.sourceId, true) }
                            )
                        }

                        if (src.record.previousRawContent != null) {
                            TvActionButton(
                                label = "Rollback",
                                onClick = { onRollback(src.record.sourceId) }
                            )
                        }
                        TvActionButton(
                            label = "Diagnose",
                            onClick = { onDiagnose(src) }
                        )
                        TvActionButton(
                            label = "Remove",
                            tone = TvButtonTone.DANGER,
                            onClick = { onRemove(src.record.sourceId) }
                        )
                    }
                }
            }
        }

        // Bundled YAML Definitions
        item {
            Text(
                text = "Bundled Site Definitions (Press OK to Validate & Review)",
                style = MaterialTheme.typography.titleLarge,
                color = HarborColors.TextPrimary
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(bundledDefinitions, key = { it.assetPath }) { bundled ->
                    TvFocusableCard(
                        onClick = { onLoadBundledDefinition(bundled.assetPath) },
                        modifier = Modifier.width(260.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = HarborColors.FocusCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = bundled.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = HarborColors.FocusCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = bundled.fileName,
                                style = MaterialTheme.typography.labelMedium,
                                color = HarborColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 27-Platform Magic-PH & Dataset Reference Catalog
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Magic-PH & Dataset Reference Catalog (${magicPhProfiles.size} Platforms)",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.TextPrimary
                )
                Text(
                    text = "Reference-only metadata from ${MagicPhSettingsAdapter.PROVIDER} (commit ${MagicPhSettingsAdapter.PINNED_COMMIT.take(10)}) and dataset.json. Cannot auto-activate or run userscript JS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HarborColors.TextSecondary
                )
            }
        }

        items(magicPhProfiles, key = { "mph_${it.id}" }) { profile ->
            val key = "mph:${profile.id}"
            val friendlyName = friendlySourceName(profile.id)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(HarborColors.Surface)
                    .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            text = "${profile.name} ($friendlyName • ${profile.id})" +
                                (profile.operator?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.titleMedium,
                            color = HarborColors.TextPrimary
                        )
                        Text(
                            text = "Desktop: ${yesNo(profile.desktopSupported)} • Mobile: ${yesNo(profile.mobileSupported)} • Search: ${yesNo(profile.searchSupported)} • Direct Video: ${yesNo(profile.directVideoSupported)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    StatusPillBadge(
                        label = profile.sourceStatus,
                        icon = Icons.Rounded.Info,
                        color = HarborColors.WarningAmber
                    )
                }

                profile.officialApiType?.let { api ->
                    Text(
                        text = "Official API: $api",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.AccentTeal
                    )
                }

                Text(
                    text = buildString {
                        append("Catalog hosts: ${profile.catalogHosts.joinToString(", ").ifEmpty { "none" }}")
                        append(" | Image hosts: ${profile.imageHosts.joinToString(", ").ifEmpty { "none" }}")
                        append(" | Media hosts: ${profile.mediaHosts.joinToString(", ").ifEmpty { "none" }}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = HarborColors.TextSecondary
                )

                if (profile.compatibilityNotes.isNotBlank()) {
                    Text(
                        text = profile.compatibilityNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextSecondary
                    )
                }

                val assetPath = profile.bundledDefinitionAsset
                if (assetPath != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TvActionButton(
                            label = "Load Bundled Definition",
                            tone = TvButtonTone.PRIMARY,
                            focusRequester = requesterFor(key),
                            onFocused = { onFocusKeyChanged(key) },
                            onClick = { onLoadBundledDefinition(assetPath) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DefinitionImportScreen(
    bundledDefinitions: List<BundledDefinitionSummary>,
    approvalReview: ApprovalReviewState?,
    importMessage: String?,
    importError: String?,
    isLoading: Boolean,
    onLoadBundled: (String) -> Unit,
    onImportTextOrPath: (String) -> Unit,
    onImportHttpsUrl: (String) -> Unit,
    onApproveSource: () -> Unit,
    onCancelApproval: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pathOrTextInput by remember { mutableStateOf("") }
    var httpsUrlInput by remember { mutableStateOf("") }
    val approveFocus = remember { FocusRequester() }

    LaunchedEffect(approvalReview) {
        if (approvalReview != null) {
            runCatching { approveFocus.requestFocus() }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 32.dp, vertical = 24.dp),
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
                            imageVector = if (approvalReview != null) Icons.Rounded.CheckCircle else Icons.Rounded.Add,
                            contentDescription = null,
                            tint = HarborColors.FocusCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (approvalReview != null) {
                                "Source Approval Review"
                            } else {
                                "Definition Import"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = HarborColors.TextPrimary
                        )
                        Text(
                            text = "Definitions are validated offline before approval. No network requests occur during import parsing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HarborColors.TextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                TvActionButton(label = "Back", onClick = onBack)
            }
        }

        if (isLoading) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = HarborColors.FocusCyan)
                    Text("Validating definition...", color = HarborColors.TextSecondary)
                }
            }
        }

        if (!importMessage.isNullOrBlank()) {
            item {
                TvStatusBanner(
                    title = "Validation Status",
                    body = importMessage,
                    accentColor = HarborColors.SuccessGreen
                )
            }
        }

        if (!importError.isNullOrBlank()) {
            item {
                TvStatusBanner(
                    title = "Validation / Import Error",
                    body = importError,
                    accentColor = HarborColors.DangerRed
                )
            }
        }

        if (approvalReview != null) {
            // Mandatory Section 21.2 Approval Screen
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(2.dp, HarborColors.FocusCyan, RoundedCornerShape(16.dp))
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
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
                                text = "${approvalReview.sourceName} (${friendlySourceName(approvalReview.sourceId)})",
                                style = MaterialTheme.typography.headlineSmall,
                                color = HarborColors.TextPrimary
                            )
                            Text(
                                text = "${approvalReview.sourceId} • v${approvalReview.version} • Author: ${approvalReview.author ?: "Not specified"} • Lifecycle: ${approvalReview.lifecycleState.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = HarborColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        StatusPillBadge(
                            label = if (approvalReview.isValid) "PASSED" else "FAILED",
                            icon = if (approvalReview.isValid) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                            color = if (approvalReview.isValid) HarborColors.SuccessGreen else HarborColors.DangerRed
                        )
                    }

                    approvalReview.description?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyLarge, color = HarborColors.TextSecondary)
                    }

                    // Requested domains grouped by purpose
                    Text(
                        text = "Requested Domains by Purpose (Exact Matching Only)",
                        style = MaterialTheme.typography.titleMedium,
                        color = HarborColors.FocusCyan
                    )
                    HostGroupRow("Catalog", approvalReview.catalogHosts)
                    HostGroupRow("Images", approvalReview.imageHosts)
                    HostGroupRow("Media", approvalReview.mediaHosts)
                    HostGroupRow("Authentication", approvalReview.authHosts)

                    Text(
                        text = "Transport & Auth: httpsOnly=${approvalReview.httpsOnly}, allowLan=${approvalReview.allowLan}, authType=${approvalReview.authType}" +
                            (approvalReview.authProfileId?.let { " (profile=$it)" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = "Rate Limits & Budgets: maxConcurrent=${approvalReview.maxConcurrentRequests}, minInterval=${approvalReview.minRequestIntervalMs}ms, maxResponse=${approvalReview.maxCatalogResponseBytes}B, maxItems/page=${approvalReview.maxItemsPerPage}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextSecondary
                    )

                    // Permission declaration + mandatory disclaimer
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(HarborColors.WarningAmber.copy(alpha = 0.12f))
                            .border(1.dp, HarborColors.WarningAmber.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = HarborColors.WarningAmber,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Permission Declaration: basis=${approvalReview.permissionBasis ?: "none"}" +
                                    (approvalReview.permissionReference?.let { " • ref=$it" } ?: ""),
                                style = MaterialTheme.typography.titleMedium,
                                color = HarborColors.WarningAmber
                            )
                        }
                        approvalReview.permissionNotes?.let {
                            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = HarborColors.TextPrimary)
                        }
                        Text(
                            text = "Disclaimer: A publisher-permission field is a declaration, not proof of permission.",
                            style = MaterialTheme.typography.labelLarge,
                            color = HarborColors.WarningAmber
                        )
                    }

                    approvalReview.approvalDiff?.let { diff ->
                        if (diff.requiresReapproval) {
                            TvStatusBanner(
                                title = "Permission Expansion Detected on Update",
                                body = buildString {
                                    if (diff.addedCatalogHosts.isNotEmpty()) append("Added catalog: ${diff.addedCatalogHosts}. ")
                                    if (diff.addedImageHosts.isNotEmpty()) append("Added images: ${diff.addedImageHosts}. ")
                                    if (diff.addedMediaHosts.isNotEmpty()) append("Added media: ${diff.addedMediaHosts}. ")
                                    if (diff.authChanged) append("Auth changed. ")
                                    if (diff.httpsRelaxed) append("HTTPS relaxed. ")
                                    if (diff.lanAccessAdded) append("LAN access added. ")
                                },
                                accentColor = HarborColors.WarningAmber
                            )
                        }
                    }

                    if (approvalReview.validationIssues.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Validation Findings (${approvalReview.validationIssues.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = HarborColors.TextPrimary
                            )
                            approvalReview.validationIssues.forEach { issue ->
                                val c = if (issue.severity == ValidationSeverity.ERROR) {
                                    HarborColors.DangerRed
                                } else {
                                    HarborColors.WarningAmber
                                }
                                Text(
                                    text = "[${issue.severity}] ${issue.stage} (${issue.code.name}) ${issue.path}: ${issue.message}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TvActionButton(
                            label = if (approvalReview.isPendingUpdate) {
                                "Approve Update & Activate"
                            } else {
                                "Approve & Enable Source"
                            },
                            tone = TvButtonTone.PRIMARY,
                            enabled = approvalReview.isValid,
                            focusRequester = approveFocus,
                            onClick = onApproveSource
                        )
                        TvActionButton(
                            label = "Cancel / Keep Unapproved",
                            tone = TvButtonTone.SECONDARY,
                            onClick = onCancelApproval
                        )
                    }
                }
            }
        } else {
            // Import Methods
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "1. Bundled YAML Definitions",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        bundledDefinitions.forEach { bundled ->
                            TvTagChip(
                                label = bundled.title,
                                onClick = { onLoadBundled(bundled.assetPath) }
                            )
                        }
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
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "2. Local JSON/YAML File Path or Pasted Definition",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    OutlinedTextField(
                        value = pathOrTextInput,
                        onValueChange = { pathOrTextInput = it },
                        label = { Text("/sdcard/Download/source.yaml or paste YAML/JSON") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = HarborColors.TextPrimary,
                            unfocusedTextColor = HarborColors.TextPrimary,
                            focusedBorderColor = HarborColors.FocusCyan,
                            unfocusedBorderColor = HarborColors.BorderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TvActionButton(
                        label = "Validate Local File / Text",
                        tone = TvButtonTone.PRIMARY,
                        onClick = { onImportTextOrPath(pathOrTextInput) }
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "3. HTTPS Definition URL",
                        style = MaterialTheme.typography.titleLarge,
                        color = HarborColors.TextPrimary
                    )
                    OutlinedTextField(
                        value = httpsUrlInput,
                        onValueChange = { httpsUrlInput = it },
                        label = { Text("https://example.org/harbor-source.yaml") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = HarborColors.TextPrimary,
                            unfocusedTextColor = HarborColors.TextPrimary,
                            focusedBorderColor = HarborColors.FocusCyan,
                            unfocusedBorderColor = HarborColors.BorderSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TvActionButton(
                        label = "Fetch & Validate HTTPS Definition",
                        tone = TvButtonTone.ACCENT,
                        onClick = { onImportHttpsUrl(httpsUrlInput) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HostGroupRow(label: String, hosts: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelLarge,
            color = HarborColors.TextSecondary,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = if (hosts.isEmpty()) "(none)" else hosts.joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
            color = HarborColors.TextPrimary
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    sourceName: String?,
    report: SourceDiagnosticsReport?,
    isLoading: Boolean,
    onRerunFixtures: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rerunFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { rerunFocus.requestFocus() }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HarborColors.Background)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
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
                            .background(HarborColors.AccentTeal.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Build,
                            contentDescription = null,
                            tint = HarborColors.AccentTeal,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Definition Diagnostics${sourceName?.let { ": ${friendlySourceName(it)}" } ?: ""}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = HarborColors.TextPrimary
                        )
                        Text(
                            text = "Offline FixtureRunner results with real network disabled and sensitive tokens redacted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HarborColors.TextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvActionButton(
                        label = "Re-run Offline Fixtures",
                        tone = TvButtonTone.PRIMARY,
                        focusRequester = rerunFocus,
                        onClick = onRerunFixtures
                    )
                    TvActionButton(label = "Back", onClick = onBack)
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
                    Text("Running isolated offline fixtures...", color = HarborColors.TextSecondary)
                }
            }
        }

        if (report == null && !isLoading) {
            item {
                TvStatusBanner(
                    title = "No Diagnostics Report",
                    body = "Select a registered source in Sources and press 'Diagnose'.",
                    accentColor = HarborColors.WarningAmber
                )
            }
        } else if (report != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagLine("Source", "${friendlySourceName(report.sourceId)} (${report.sourceId})")
                    DiagLine("Active Version", report.activeVersion)
                    DiagLine("Last Successful Request", formatEpochMs(report.lastSuccessfulRequestMs))
                    DiagLine("Last Successful Parse", formatEpochMs(report.lastSuccessfulParseMs))
                    DiagLine("Items Extracted", report.itemsExtracted.toString())
                    DiagLine("Items Rejected", report.itemsRejected.toString())
                    DiagLine("Playback Candidate Count", report.playbackCandidateCount.toString())
                    DiagLine(
                        "Missing Required Fields",
                        report.missingRequiredFields.ifEmpty { listOf("None") }.joinToString(", ")
                    )
                    DiagLine(
                        "Blocked Destinations",
                        report.blockedDestinations.ifEmpty { listOf("None") }.joinToString(", ")
                    )
                    DiagLine(
                        "Failure Reason",
                        report.sanitizedFailureReason?.let {
                            "${report.errorCode?.name ?: "ERROR"}: $it"
                        } ?: "None (all fixtures passed)"
                    )
                }
            }

            // Magic-PH Reference Metadata (Spec 29.9)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(HarborColors.Surface)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Reference Integration Metadata",
                        style = MaterialTheme.typography.titleMedium,
                        color = HarborColors.AccentTeal
                    )
                    DiagLine("Reference Repository", report.referenceRepository ?: "N/A")
                    DiagLine("Pinned Commit", report.pinnedCommit ?: "N/A")
                    DiagLine("Normalized Source Status", report.normalizedSourceStatus ?: "N/A")
                }
            }

            item {
                Text(
                    text = "Fixture Checks (${report.fixtureResults.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = HarborColors.TextPrimary
                )
            }

            items(report.fixtureResults, key = { it.fixtureId }) { res ->
                val color = if (res.passed) HarborColors.SuccessGreen else HarborColors.DangerRed
                val icon = if (res.passed) Icons.Rounded.CheckCircle else Icons.Rounded.Warning
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(color.copy(alpha = 0.10f))
                        .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${res.fixtureId} [${res.operation}]",
                            style = MaterialTheme.typography.titleMedium,
                            color = color,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        StatusPillBadge(
                            label = if (res.passed) "PASSED" else "FAILED",
                            icon = icon,
                            color = color
                        )
                    }
                    Text(
                        text = "Extracted: ${res.itemsExtracted} • Rejected: ${res.itemsRejected} • Candidates: ${res.candidatesExtracted}" +
                            (res.errorCode?.let { " • Error: ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = HarborColors.TextSecondary
                    )
                    Text(
                        text = res.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPillBadge(
    label: String,
    icon: ImageVector,
    color: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = HarborColors.TextSecondary,
            modifier = Modifier.width(200.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = HarborColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

private fun formatEpochMs(ms: Long?): String {
    if (ms == null) return "Never"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return fmt.format(Date(ms))
}
