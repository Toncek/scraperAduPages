package com.harbor.tv.feature.sources

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.harbor.tv.ApprovalReviewState
import com.harbor.tv.BundledDefinitionSummary
import com.harbor.tv.core.model.DefinitionLifecycleState
import com.harbor.tv.core.model.MagicPhWebsiteProfile
import com.harbor.tv.core.model.SourceDiagnosticsReport
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.TvActionButton
import com.harbor.tv.core.tvui.TvButtonTone
import com.harbor.tv.core.tvui.TvFocusableCard
import com.harbor.tv.core.tvui.TvStatusBanner
import com.harbor.tv.core.tvui.TvTagChip
import com.harbor.tv.domain.sources.MagicPhSettingsAdapter
import com.harbor.tv.domain.sources.SourceRegistry
import com.harbor.tv.engine.definition.ValidationSeverity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SourcesScreen(
    registeredSources: List<SourceRegistry.RegisteredSource>,
    bundledDefinitions: List<BundledDefinitionSummary>,
    magicPhProfiles: List<MagicPhWebsiteProfile>,
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
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Sources & Declarative Registry",
                        style = MaterialTheme.typography.headlineMedium,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = "Manage validated JSON/YAML source definitions, approvals, rollbacks, and offline fixture diagnostics.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                text = "Registered Definitions (${registeredSources.size})",
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
                val def = src.definition
                val key = "reg:${src.record.sourceId}"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, stateColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${def?.source?.name ?: src.record.sourceId} (${src.record.sourceId})",
                                style = MaterialTheme.typography.titleLarge,
                                color = HarborColors.TextPrimary
                            )
                            Text(
                                text = "Version ${src.record.version} • State: ${state.name}" +
                                    (src.record.previousVersion?.let { " • Rollback available: v$it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = stateColor
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                text = "Bundled YAML Definitions (Tap to Validate & Review Approval)",
                style = MaterialTheme.typography.titleLarge,
                color = HarborColors.TextPrimary
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(bundledDefinitions, key = { it.assetPath }) { bundled ->
                    TvFocusableCard(
                        onClick = { onLoadBundledDefinition(bundled.assetPath) },
                        modifier = Modifier.width(240.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = bundled.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = HarborColors.FocusCyan
                            )
                            Text(
                                text = bundled.fileName,
                                style = MaterialTheme.typography.labelMedium,
                                color = HarborColors.TextSecondary
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HarborColors.Surface)
                    .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            text = "${profile.name} (${profile.id})" +
                                (profile.operator?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.titleMedium,
                            color = HarborColors.TextPrimary
                        )
                        Text(
                            text = "Status: ${profile.sourceStatus} • Desktop: ${profile.desktopSupported} • Mobile: ${profile.mobileSupported} • Search: ${profile.searchSupported} • Direct Video: ${profile.directVideoSupported}",
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.WarningAmber
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HarborColors.WarningAmber.copy(alpha = 0.16f))
                            .border(1.dp, HarborColors.WarningAmber, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = profile.sourceStatus,
                            style = MaterialTheme.typography.labelMedium,
                            color = HarborColors.WarningAmber
                        )
                    }
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
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    Text(
                        text = "${approvalReview.sourceName} (${approvalReview.sourceId} v${approvalReview.version})",
                        style = MaterialTheme.typography.headlineSmall,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = buildString {
                            append("Author: ${approvalReview.author ?: "Not specified"}")
                            append(" • Lifecycle: ${approvalReview.lifecycleState.name}")
                            append(" • Validation: ${if (approvalReview.isValid) "PASSED" else "FAILED"}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (approvalReview.isValid) HarborColors.SuccessGreen else HarborColors.DangerRed
                    )
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(HarborColors.WarningAmber.copy(alpha = 0.12f))
                            .border(1.dp, HarborColors.WarningAmber.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Permission Declaration: basis=${approvalReview.permissionBasis ?: "none"}" +
                                (approvalReview.permissionReference?.let { " • ref=$it" } ?: ""),
                            style = MaterialTheme.typography.titleMedium,
                            color = HarborColors.WarningAmber
                        )
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
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp))
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp))
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
                        text = "Definition Diagnostics${sourceName?.let { ": $it" } ?: ""}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = HarborColors.TextPrimary
                    )
                    Text(
                        text = "Offline FixtureRunner results with real network disabled and sensitive tokens redacted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HarborColors.TextSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(HarborColors.SurfaceCard)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagLine("Source ID", report.sourceId)
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
                        .clip(RoundedCornerShape(14.dp))
                        .background(HarborColors.Surface)
                        .border(1.dp, HarborColors.BorderSubtle, RoundedCornerShape(14.dp))
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(color.copy(alpha = 0.10f))
                        .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${res.fixtureId} [${res.operation}] — ${if (res.passed) "PASSED" else "FAILED"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = color
                    )
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
private fun DiagLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = HarborColors.TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = HarborColors.TextPrimary
        )
    }
}

private fun formatEpochMs(ms: Long?): String {
    if (ms == null) return "Never"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return fmt.format(Date(ms))
}
