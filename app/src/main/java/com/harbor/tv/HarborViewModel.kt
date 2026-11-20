package com.harbor.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.CategoryItem
import com.harbor.tv.core.model.DefinitionLifecycleState
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.model.MagicPhWebsiteProfile
import com.harbor.tv.core.model.PlaybackCandidate
import com.harbor.tv.core.model.PlaybackResolutionResult
import com.harbor.tv.core.model.PreferenceConfig
import com.harbor.tv.core.model.QualityPreference
import com.harbor.tv.core.model.SourceDiagnosticsReport
import com.harbor.tv.core.model.TagPreferenceState
import com.harbor.tv.core.model.UnknownMetadataMode
import com.harbor.tv.core.model.UserProfile
import com.harbor.tv.core.network.ControlledHttpTransport
import com.harbor.tv.core.network.HttpResponseData
import com.harbor.tv.core.network.HttpTransport
import com.harbor.tv.core.security.AddressSafetyValidator
import com.harbor.tv.core.security.CredentialRedactor
import com.harbor.tv.core.security.PinHasher
import com.harbor.tv.core.storage.HarborDatabase
import com.harbor.tv.core.storage.HarborSettingsStore
import com.harbor.tv.core.storage.toEntity
import com.harbor.tv.core.tvui.HarborNavDestination
import com.harbor.tv.domain.catalog.CatalogNormalizer
import com.harbor.tv.domain.catalog.CatalogRepository
import com.harbor.tv.domain.playback.PlaybackResolver
import com.harbor.tv.domain.preferences.PreferenceFilterEngine
import com.harbor.tv.domain.sources.MagicPhSettingsAdapter
import com.harbor.tv.domain.sources.SourceRegistry
import com.harbor.tv.engine.definition.DefinitionImporter
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.definition.ValidationIssue
import com.harbor.tv.engine.extract.UnifiedOperationExtractor
import com.harbor.tv.engine.fixtures.FixtureRunner
import com.harbor.tv.engine.policy.RequestPolicyEngine
import com.harbor.tv.feature.player.HarborPlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class HarborScreen {
    WELCOME,
    PROFILE_SELECTION,
    PREFERENCE_SETUP,
    HOME,
    CATEGORIES,
    SEARCH,
    VIDEO_DETAILS,
    PLAYER,
    FAVORITES,
    SOURCES,
    DEFINITION_IMPORT,
    DIAGNOSTICS,
    SETTINGS
}

data class BundledDefinitionSummary(
    val assetPath: String,
    val fileName: String,
    val title: String
)

data class ApprovalReviewState(
    val sourceId: String,
    val sourceName: String,
    val version: String,
    val author: String?,
    val description: String?,
    val catalogHosts: List<String>,
    val imageHosts: List<String>,
    val mediaHosts: List<String>,
    val authHosts: List<String>,
    val httpsOnly: Boolean,
    val allowLan: Boolean,
    val authType: String,
    val authProfileId: String?,
    val authScopes: List<String>,
    val maxConcurrentRequests: Int,
    val minRequestIntervalMs: Long,
    val maxCatalogResponseBytes: Long,
    val maxItemsPerPage: Int,
    val permissionBasis: String?,
    val permissionReference: String?,
    val permissionNotes: String?,
    val isValid: Boolean,
    val validationIssues: List<ValidationIssue>,
    val approvalDiff: SourceRegistry.ApprovalDiff?,
    val isPendingUpdate: Boolean,
    val lifecycleState: DefinitionLifecycleState
)

data class HarborUiState(
    val currentScreen: HarborScreen = HarborScreen.WELCOME,
    val onboardingCompleted: Boolean = false,
    val profiles: List<UserProfile> = emptyList(),
    val activeProfile: UserProfile = UserProfile(id = "default", name = "Default"),
    val isPrivateSession: Boolean = false,
    val preferences: PreferenceConfig = PreferenceConfig(),
    val offlineFixtureMode: Boolean = false,
    val videoPreloadEnabled: Boolean = true,
    val mediaCacheLimitMb: Int = HarborSettingsStore.DEFAULT_MEDIA_CACHE_LIMIT_MB,
    val mediaCacheUsedBytes: Long = 0L,
    val mediaCacheMaxBytes: Long = HarborSettingsStore.DEFAULT_MEDIA_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
    val availableStorageBytes: Long = 0L,

    val registeredSources: List<SourceRegistry.RegisteredSource> = emptyList(),
    val bundledDefinitions: List<BundledDefinitionSummary> = emptyList(),
    val magicPhCatalog: List<MagicPhWebsiteProfile> = MagicPhSettingsAdapter.allProfiles(),

    val homeRowsBySource: Map<String, List<CatalogItem>> = emptyMap(),
    val homeQuickCategories: List<CategoryItem> = emptyList(),
    val featuredHomeItem: CatalogItem? = null,
    val only4kFilter: Boolean = false,
    val watchHistory: List<CatalogRepository.WatchHistoryRecord> = emptyList(),

    val selectedCategorySourceId: String? = null,
    val categoriesForSelectedSource: List<CategoryItem> = emptyList(),
    val selectedCategory: CategoryItem? = null,
    val categoryItems: List<CatalogItem> = emptyList(),
    val categoryNextCursor: String? = null,
    val categoryPagesLoaded: Int = 0,

    val searchQuery: String = "",
    val searchSourceFilterId: String? = null,
    val searchResults: List<CatalogItem> = emptyList(),
    val searchErrors: Map<String, String> = emptyMap(),

    val favorites: List<CatalogItem> = emptyList(),

    val selectedVideo: CatalogItem? = null,
    val selectedVideoIsFavorite: Boolean = false,
    val selectedVideoPlayback: PlaybackResolutionResult? = null,
    val selectedVideoSupportsPlayback: Boolean = false,

    val approvalReview: ApprovalReviewState? = null,
    val importMessage: String? = null,
    val importError: String? = null,

    val diagnosticsReport: SourceDiagnosticsReport? = null,
    val diagnosticsSourceName: String? = null,

    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,

    val lastFocusedKeyByScreen: Map<HarborScreen, String> = emptyMap()
)

class HarborViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val db = HarborDatabase.getInstance(appContext)
    private val settingsStore = HarborSettingsStore(appContext)
    private val policyEngine = RequestPolicyEngine()
    private val extractor = UnifiedOperationExtractor(policyEngine = policyEngine)
    private val normalizer = CatalogNormalizer(policyEngine = policyEngine)
    private val filterEngine = PreferenceFilterEngine()
    private val fixtureRunner = FixtureRunner(extractor = extractor, policyEngine = policyEngine)

    val sourceRegistry = SourceRegistry(
        sourceRecordDao = db.sourceRecordDao(),
        catalogCacheDao = db.catalogCacheDao()
    )

    private val _uiState = MutableStateFlow(
        HarborUiState(
            bundledDefinitions = discoverBundledDefinitions()
        )
    )
    val uiState: StateFlow<HarborUiState> = _uiState.asStateFlow()

    private val controlledTransport = ControlledHttpTransport(
        policyEngine = policyEngine,
        cacheDir = File(appContext.cacheDir, "harbor_http_cache")
    )
    private val hybridTransport = AssetFixtureAwareHttpTransport(
        appContext = appContext,
        sourceRegistry = sourceRegistry,
        policyEngine = policyEngine,
        liveTransport = controlledTransport,
        isOfflineMode = { _uiState.value.offlineFixtureMode }
    )

    val catalogRepository = CatalogRepository(
        sourceRegistry = sourceRegistry,
        transport = hybridTransport,
        catalogCacheDao = db.catalogCacheDao(),
        favoriteDao = db.favoriteDao(),
        watchHistoryDao = db.watchHistoryDao(),
        settingsStore = settingsStore,
        normalizer = normalizer,
        filterEngine = filterEngine,
        extractor = extractor,
        policyEngine = policyEngine
    )

    val playbackResolver = PlaybackResolver(
        transport = hybridTransport,
        policyEngine = policyEngine,
        extractor = extractor,
        filterEngine = filterEngine
    )

    val playerController = HarborPlayerController(appContext)

    private val navBackStack = ArrayDeque<HarborScreen>()
    private var activePaginationSession: CatalogRepository.PaginationSession? = null
    private var refreshHomeJob: Job? = null
    private val homeMergeMutex = Mutex()
    private var selectedVideoPlaybackResolvedAtMs: Long = 0L

    init {
        initializeAppState()
    }

    private val definitionImporter = DefinitionImporter()

    private fun initializeAppState() {
        viewModelScope.launch {
            ensureDefaultProfileExists()
            ensureBundledDefaultSourcesSeeded()
            val onboardingDone = settingsStore.isOnboardingCompleted()
            val privateSession = settingsStore.isPrivateSession()
            val videoPreloadEnabled = settingsStore.isVideoPreloadEnabled()
            val cacheLimitMb = settingsStore.getMediaCacheLimitMb()
            playerController.configureMediaCacheLimit(cacheLimitMb)
            val activeId = settingsStore.getActiveProfileId()
            val profiles = db.profileDao().getAll().map {
                it.toUserProfile(isPrivateSession = privateSession)
            }
            val activeProfile = profiles.firstOrNull { it.id == activeId }
                ?: profiles.firstOrNull()
                ?: UserProfile(id = "default", name = "Default", isPrivateSession = privateSession)
            val prefs = settingsStore.getPreferences(activeProfile.id)
                .copy(unknownMetadataMode = activeProfile.unknownMetadataMode)

            _uiState.update {
                it.copy(
                    onboardingCompleted = onboardingDone,
                    currentScreen = if (onboardingDone) HarborScreen.HOME else HarborScreen.WELCOME,
                    profiles = profiles,
                    activeProfile = activeProfile,
                    isPrivateSession = privateSession,
                    videoPreloadEnabled = videoPreloadEnabled,
                    mediaCacheLimitMb = cacheLimitMb,
                    preferences = prefs
                )
            }
            refreshMediaCacheStats()

            launch {
                sourceRegistry.observeSources().collectLatest { sources ->
                    _uiState.update { it.copy(registeredSources = sources) }
                    refreshVisibleCatalogForCurrentState()
                }
            }
            launch {
                db.profileDao().observeAll().collectLatest { entities ->
                    val currentPrivate = _uiState.value.isPrivateSession
                    val mapped = entities.map { it.toUserProfile(isPrivateSession = currentPrivate) }
                    val currentActiveId = _uiState.value.activeProfile.id
                    val updatedActive = mapped.firstOrNull { it.id == currentActiveId }
                        ?: mapped.firstOrNull()
                        ?: _uiState.value.activeProfile
                    _uiState.update {
                        it.copy(profiles = mapped, activeProfile = updatedActive)
                    }
                }
            }
        }
    }

    private suspend fun ensureBundledDefaultSourcesSeeded() = withContext(Dispatchers.IO) {
        val bundled = _uiState.value.bundledDefinitions
        var seededOrUpdated = false
        val hadAnySources = db.sourceRecordDao().getAll().isNotEmpty()

        for (summary in bundled) {
            runCatching {
                val rawText = appContext.assets.open(summary.assetPath)
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
                val validation = definitionImporter.importAndValidate(rawText)
                val definition = validation.definition ?: return@runCatching
                val sourceId = definition.source.id
                val existing = sourceRegistry.getSource(sourceId)

                if (definition.source.enabledByDefault) {
                    when {
                        existing == null -> {
                            val outcome = sourceRegistry.importDefinition(rawText)
                            if (outcome.validationResult.isValid) {
                                sourceRegistry.approveAndEnable(sourceId, userApproved = true)
                                seededOrUpdated = true
                            }
                        }
                        existing.record.version != definition.source.version ||
                            existing.record.rawContent != rawText -> {
                            sourceRegistry.applyUpdate(sourceId, rawText, userApproved = true)
                            seededOrUpdated = true
                        }
                        existing.lifecycleState == DefinitionLifecycleState.AWAITING_APPROVAL -> {
                            sourceRegistry.approveAndEnable(sourceId, userApproved = true)
                            seededOrUpdated = true
                        }
                    }
                } else if (existing != null && (existing.record.version != definition.source.version ||
                        existing.record.rawContent != rawText)
                ) {
                    val wasEnabled = existing.lifecycleState == DefinitionLifecycleState.ENABLED
                    sourceRegistry.applyUpdate(sourceId, rawText, userApproved = true)
                    if (!wasEnabled) {
                        sourceRegistry.setSourceEnabled(sourceId, false)
                    }
                    seededOrUpdated = true
                }
            }
        }

        if (!hadAnySources || seededOrUpdated) {
            if (!settingsStore.isOnboardingCompleted()) {
                settingsStore.setOnboardingCompleted(true)
            }
        }
    }

    fun enableAllBundledSources() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            var enabledCount = 0
            for (summary in _uiState.value.bundledDefinitions) {
                runCatching {
                    val rawText = withContext(Dispatchers.IO) {
                        appContext.assets.open(summary.assetPath)
                            .bufferedReader(StandardCharsets.UTF_8)
                            .use { it.readText() }
                    }
                    val validation = definitionImporter.importAndValidate(rawText)
                    val def = validation.definition ?: return@runCatching
                    val sourceId = def.source.id
                    val existing = sourceRegistry.getSource(sourceId)
                    when {
                        existing == null -> {
                            val outcome = sourceRegistry.importDefinition(rawText)
                            if (outcome.validationResult.isValid) {
                                sourceRegistry.approveAndEnable(sourceId, userApproved = true)
                                enabledCount++
                            }
                        }
                        existing.record.version != def.source.version ||
                            existing.record.rawContent != rawText -> {
                            sourceRegistry.applyUpdate(sourceId, rawText, userApproved = true)
                            enabledCount++
                        }
                        existing.lifecycleState == DefinitionLifecycleState.AWAITING_APPROVAL -> {
                            sourceRegistry.approveAndEnable(sourceId, userApproved = true)
                            enabledCount++
                        }
                        existing.lifecycleState == DefinitionLifecycleState.PENDING_UPDATE -> {
                            sourceRegistry.approvePendingUpdate(sourceId, userApproved = true)
                            enabledCount++
                        }
                        existing.lifecycleState != DefinitionLifecycleState.ENABLED -> {
                            sourceRegistry.setSourceEnabled(sourceId, true)
                            enabledCount++
                        }
                    }
                }
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = "Enabled $enabledCount bundled sources."
                )
            }
            refreshHome()
        }
    }

    private suspend fun ensureDefaultProfileExists() {
        if (db.profileDao().getAll().isEmpty()) {
            db.profileDao().upsert(
                UserProfile(
                    id = HarborSettingsStore.DEFAULT_PROFILE_ID,
                    name = "Default"
                ).toEntity()
            )
        }
    }

    fun recordFocusKey(screen: HarborScreen, key: String) {
        _uiState.update { state ->
            if (state.lastFocusedKeyByScreen[screen] == key) state
            else state.copy(lastFocusedKeyByScreen = state.lastFocusedKeyByScreen + (screen to key))
        }
    }

    fun navigateTo(screen: HarborScreen, clearBackStack: Boolean = false) {
        if (clearBackStack) {
            navBackStack.clear()
        } else {
            val current = _uiState.value.currentScreen
            if (current != screen) {
                navBackStack.addLast(current)
            }
        }
        _uiState.update {
            it.copy(
                currentScreen = screen,
                errorMessage = null,
                statusMessage = null
            )
        }
    }

    fun navigateToSidebarDestination(destination: HarborNavDestination) {
        val target = when (destination) {
            HarborNavDestination.HOME -> HarborScreen.HOME
            HarborNavDestination.CATEGORIES -> HarborScreen.CATEGORIES
            HarborNavDestination.SEARCH -> HarborScreen.SEARCH
            HarborNavDestination.FAVORITES -> HarborScreen.FAVORITES
            HarborNavDestination.SOURCES -> HarborScreen.SOURCES
            HarborNavDestination.SETTINGS -> HarborScreen.SETTINGS
        }
        navBackStack.clear()
        _uiState.update {
            it.copy(
                currentScreen = target,
                errorMessage = null,
                statusMessage = null
            )
        }
        when (target) {
            HarborScreen.HOME -> refreshHome()
            HarborScreen.CATEGORIES -> ensureCategoriesLoaded()
            HarborScreen.FAVORITES -> refreshFavorites()
            HarborScreen.SETTINGS -> refreshMediaCacheStats()
            else -> Unit
        }
    }

    fun navigateBack(): Boolean {
        if (_uiState.value.currentScreen == HarborScreen.PLAYER) {
            playerController.stop()
        }
        if (navBackStack.isEmpty()) {
            val fallback = if (_uiState.value.onboardingCompleted &&
                _uiState.value.currentScreen != HarborScreen.HOME &&
                _uiState.value.currentScreen !in setOf(
                    HarborScreen.WELCOME,
                    HarborScreen.PROFILE_SELECTION,
                    HarborScreen.PREFERENCE_SETUP
                )
            ) {
                HarborScreen.HOME
            } else {
                return false
            }
            _uiState.update { it.copy(currentScreen = fallback) }
            return true
        }
        val prev = navBackStack.removeLast()
        _uiState.update { it.copy(currentScreen = prev, errorMessage = null) }
        return true
    }

    fun completeOnboardingAndOpenHome() {
        viewModelScope.launch {
            settingsStore.setOnboardingCompleted(true)
            _uiState.update { it.copy(onboardingCompleted = true) }
            navigateTo(HarborScreen.HOME, clearBackStack = true)
            refreshHome()
        }
    }

    fun createProfile(name: String, pin: String?) {
        val cleanName = name.trim().ifEmpty { "Profile ${_uiState.value.profiles.size + 1}" }
        val cleanId = cleanName.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
            .ifEmpty { "profile-${System.currentTimeMillis()}" }
        val uniqueId = if (_uiState.value.profiles.any { it.id == cleanId }) {
            "$cleanId-${System.currentTimeMillis() % 1000}"
        } else {
            cleanId
        }
        val hash = pin?.trim()?.takeIf { it.isNotEmpty() }?.let { PinHasher.hashPin(it) }
        val newProfile = UserProfile(id = uniqueId, name = cleanName, pinHash = hash)

        viewModelScope.launch {
            db.profileDao().upsert(newProfile.toEntity())
            selectProfile(newProfile, enteredPin = pin)
        }
    }

    fun verifyProfilePin(profile: UserProfile, pin: String): Boolean {
        val stored = profile.pinHash ?: return true
        return PinHasher.verifyPin(pin, stored)
    }

    fun selectProfile(profile: UserProfile, enteredPin: String? = null, nextScreen: HarborScreen? = null) {
        if (profile.pinHash != null && (enteredPin == null || !PinHasher.verifyPin(enteredPin, profile.pinHash))) {
            _uiState.update { it.copy(errorMessage = "Incorrect profile PIN.") }
            return
        }
        viewModelScope.launch {
            settingsStore.setActiveProfileId(profile.id)
            val privateSession = _uiState.value.isPrivateSession
            val loadedPrefs = settingsStore.getPreferences(profile.id)
                .copy(unknownMetadataMode = profile.unknownMetadataMode)

            // Spec 25.3: Switching profiles clears old visible results immediately
            activePaginationSession = null
            _uiState.update {
                it.copy(
                    activeProfile = profile.copy(isPrivateSession = privateSession),
                    preferences = loadedPrefs,
                    homeRowsBySource = emptyMap(),
                    homeQuickCategories = emptyList(),
                    featuredHomeItem = null,
                    watchHistory = emptyList(),
                    categoriesForSelectedSource = emptyList(),
                    selectedCategory = null,
                    categoryItems = emptyList(),
                    categoryNextCursor = null,
                    categoryPagesLoaded = 0,
                    searchResults = emptyList(),
                    favorites = emptyList(),
                    selectedVideo = null,
                    selectedVideoPlayback = null,
                    errorMessage = null,
                    statusMessage = "Switched to profile '${profile.name}'"
                )
            }
            if (nextScreen != null) {
                navigateTo(nextScreen)
            }
            refreshVisibleCatalogForCurrentState()
        }
    }

    fun togglePrivateSession(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setPrivateSession(enabled)
            val updatedProfile = _uiState.value.activeProfile.copy(isPrivateSession = enabled)
            _uiState.update {
                it.copy(
                    isPrivateSession = enabled,
                    activeProfile = updatedProfile,
                    watchHistory = if (enabled) emptyList() else it.watchHistory,
                    statusMessage = if (enabled) {
                        "Private Session enabled (no history, resume position, or disk cache persisted)"
                    } else {
                        "Private Session disabled"
                    }
                )
            }
            if (enabled) {
                playerController.cancelPreload()
                controlledTransport.clearEphemeralState()
            } else {
                refreshWatchHistory()
            }
        }
    }

    fun cycleTagPreference(tag: String) {
        val clean = tag.trim()
        if (clean.isEmpty()) return
        val currentMap = _uiState.value.preferences.tagPreferences.toMutableMap()
        val nextState = when (currentMap[clean] ?: TagPreferenceState.NEUTRAL) {
            TagPreferenceState.NEUTRAL -> TagPreferenceState.PREFERRED
            TagPreferenceState.PREFERRED -> TagPreferenceState.EXCLUDED
            TagPreferenceState.EXCLUDED -> TagPreferenceState.NEUTRAL
        }
        if (nextState == TagPreferenceState.NEUTRAL) {
            currentMap.remove(clean)
        } else {
            currentMap[clean] = nextState
        }
        savePreferences(_uiState.value.preferences.copy(tagPreferences = currentMap))
    }

    fun setTagPreference(tag: String, state: TagPreferenceState) {
        val clean = tag.trim()
        if (clean.isEmpty()) return
        val currentMap = _uiState.value.preferences.tagPreferences.toMutableMap()
        if (state == TagPreferenceState.NEUTRAL) {
            currentMap.remove(clean)
        } else {
            currentMap[clean] = state
        }
        savePreferences(_uiState.value.preferences.copy(tagPreferences = currentMap))
    }

    fun setUnknownMetadataMode(mode: UnknownMetadataMode) {
        viewModelScope.launch {
            val updatedProfile = _uiState.value.activeProfile.copy(unknownMetadataMode = mode)
            db.profileDao().upsert(updatedProfile.toEntity())
            val updatedPrefs = _uiState.value.preferences.copy(unknownMetadataMode = mode)
            settingsStore.savePreferences(updatedPrefs)
            _uiState.update {
                it.copy(activeProfile = updatedProfile, preferences = updatedPrefs)
            }
            refreshVisibleCatalogForCurrentState()
        }
    }

    fun setQualityPreference(
        preference: QualityPreference,
        preferredResolutionHeight: Int = _uiState.value.activeProfile.preferredResolutionHeight,
        preferredBitrateKbps: Int = _uiState.value.activeProfile.preferredBitrateKbps
    ) {
        viewModelScope.launch {
            val updatedProfile = _uiState.value.activeProfile.copy(
                qualityPreference = preference,
                preferredResolutionHeight = preferredResolutionHeight,
                preferredBitrateKbps = preferredBitrateKbps
            )
            db.profileDao().upsert(updatedProfile.toEntity())
            _uiState.update { it.copy(activeProfile = updatedProfile) }
        }
    }

    fun setAllowLanSources(allow: Boolean) {
        viewModelScope.launch {
            val updatedProfile = _uiState.value.activeProfile.copy(allowLanSources = allow)
            db.profileDao().upsert(updatedProfile.toEntity())
            _uiState.update { it.copy(activeProfile = updatedProfile) }
        }
    }

    fun setHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val updatedProfile = _uiState.value.activeProfile.copy(historyEnabled = enabled)
            db.profileDao().upsert(updatedProfile.toEntity())
            _uiState.update { it.copy(activeProfile = updatedProfile) }
            if (!enabled) {
                _uiState.update { it.copy(watchHistory = emptyList()) }
            } else {
                refreshWatchHistory()
            }
        }
    }

    fun setProfilePin(newPin: String?) {
        viewModelScope.launch {
            val hash = newPin?.trim()?.takeIf { it.isNotEmpty() }?.let { PinHasher.hashPin(it) }
            val updatedProfile = _uiState.value.activeProfile.copy(pinHash = hash)
            db.profileDao().upsert(updatedProfile.toEntity())
            _uiState.update {
                it.copy(
                    activeProfile = updatedProfile,
                    statusMessage = if (hash == null) "Profile PIN removed" else "Profile PIN updated"
                )
            }
        }
    }

    fun setOfflineFixtureMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                offlineFixtureMode = enabled,
                statusMessage = if (enabled) {
                    "Offline Fixture-Backed Mode enabled"
                } else {
                    "Live Network Mode enabled"
                }
            )
        }
        refreshVisibleCatalogForCurrentState()
    }

    fun setVideoPreloadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setVideoPreloadEnabled(enabled)
            if (!enabled) {
                playerController.cancelPreload()
            }
            _uiState.update {
                it.copy(
                    videoPreloadEnabled = enabled,
                    statusMessage = if (enabled) {
                        "Background ROM video preloading enabled"
                    } else {
                        "Background ROM video preloading disabled"
                    }
                )
            }
        }
    }

    fun setMediaCacheLimitMb(limitMb: Int) {
        viewModelScope.launch {
            val clamped = limitMb.coerceIn(
                HarborSettingsStore.MIN_MEDIA_CACHE_LIMIT_MB,
                HarborSettingsStore.MAX_MEDIA_CACHE_LIMIT_MB
            )
            settingsStore.setMediaCacheLimitMb(clamped)
            withContext(Dispatchers.IO) {
                playerController.configureMediaCacheLimit(clamped)
            }
            _uiState.update {
                it.copy(
                    mediaCacheLimitMb = clamped,
                    statusMessage = "Persistent ROM video cache limit set to $clamped MB"
                )
            }
            refreshMediaCacheStats()
        }
    }

    fun refreshMediaCacheStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                playerController.getCacheStats()
            }
            _uiState.update {
                it.copy(
                    mediaCacheUsedBytes = stats.usedBytes,
                    mediaCacheMaxBytes = stats.maxBytes,
                    availableStorageBytes = stats.availableDiskBytes
                )
            }
        }
    }

    fun clearCacheAndHistory() {
        viewModelScope.launch {
            db.catalogCacheDao().clearAll()
            catalogRepository.clearWatchHistory(_uiState.value.activeProfile.id)
            val loader = Coil.imageLoader(appContext)
            loader.memoryCache?.clear()
            withContext(Dispatchers.IO) {
                runCatching { loader.diskCache?.clear() }
                controlledTransport.clearEphemeralState(evictHttpCache = true)
                playerController.clearDiskMediaCache()
            }
            refreshMediaCacheStats()
            _uiState.update {
                it.copy(
                    watchHistory = emptyList(),
                    statusMessage = "Cleared catalog, image, HTTP, and media caches and watch history"
                )
            }
        }
    }

    private fun savePreferences(updated: PreferenceConfig) {
        viewModelScope.launch {
            settingsStore.savePreferences(updated)
            _uiState.update { it.copy(preferences = updated) }
            refreshVisibleCatalogForCurrentState()
        }
    }

    private fun refreshVisibleCatalogForCurrentState() {
        val state = _uiState.value
        val enabledIds = state.registeredSources
            .filter { it.lifecycleState == DefinitionLifecycleState.ENABLED }
            .map { it.record.sourceId }
            .toSet()

        // Immediately filter any in-memory lists before artwork renders (Spec 16.4 / 25.3)
        val filteredRows = state.homeRowsBySource
            .filterKeys { it in enabledIds }
            .mapValues { (_, items) ->
                filterEngine.filterAndRank(items, state.preferences, enabledIds).take(12)
            }
            .filterValues { it.isNotEmpty() }

        val filteredCategoryItems = filterEngine.filterAndRank(
            state.categoryItems,
            state.preferences,
            enabledIds
        ).take(60)
        val filteredSearch = filterEngine.filterAndRank(
            state.searchResults,
            state.preferences,
            enabledIds,
            searchQuery = state.searchQuery
        ).take(60)

        _uiState.update {
            it.copy(
                homeRowsBySource = filteredRows,
                categoryItems = filteredCategoryItems,
                searchResults = filteredSearch
            )
        }

        when (state.currentScreen) {
            HarborScreen.HOME -> refreshHome()
            HarborScreen.CATEGORIES -> ensureCategoriesLoaded()
            HarborScreen.FAVORITES -> refreshFavorites()
            else -> refreshFavorites()
        }
    }

    private data class SourceHomeLoadResult(
        val sourceId: String,
        val categories: List<CategoryItem>,
        val items: List<CatalogItem>
    )

    fun toggleOnly4kFilter() {
        _uiState.update { it.copy(only4kFilter = !it.only4kFilter) }
    }

    fun setFeaturedHomeItem(item: CatalogItem) {
        _uiState.update { it.copy(featuredHomeItem = item) }
    }

    fun quickSearch4k() {
        _uiState.update { it.copy(searchQuery = "4k") }
        navigateTo(HarborScreen.SEARCH)
        executeSearch("4k")
    }

    private fun pickFeaturedItem(rows: Map<String, List<CatalogItem>>): CatalogItem? {
        val all = rows.values.flatten()
        if (all.isEmpty()) return null
        return all.maxWithOrNull(
            compareBy<CatalogItem>(
                { it.maxResolutionHeight ?: 0 },
                { if (it.maxQualityLabel?.contains("4K", ignoreCase = true) == true) 1 else 0 },
                { if (it.title.contains("4k", ignoreCase = true)) 1 else 0 }
            )
        ) ?: all.firstOrNull()
    }

    fun refreshHome() {
        refreshHomeJob?.cancel()
        refreshHomeJob = viewModelScope.launch {
            val state = _uiState.value
            val enabledDefs = sourceRegistry.getEnabledDefinitions()
                .filter { it.source.id !in state.preferences.excludedSources }

            if (enabledDefs.isEmpty()) {
                _uiState.update {
                    it.copy(
                        homeRowsBySource = emptyMap(),
                        homeQuickCategories = emptyList(),
                        featuredHomeItem = null,
                        isLoading = false
                    )
                }
                refreshWatchHistory()
                return@launch
            }

            val enabledIds = enabledDefs.map { it.source.id }.toSet()

            // Instant cold-start hydration from Room cache (<200ms) before live fetches finish
            val cachedRows = withContext(Dispatchers.IO) {
                catalogRepository.getCachedHomeRows(enabledIds.toList(), state.preferences)
                    .mapValues { (_, items) -> items.take(12) }
                    .filterValues { it.isNotEmpty() }
            }

            _uiState.update { current ->
                val initialRows = if (cachedRows.isNotEmpty()) {
                    cachedRows
                } else {
                    current.homeRowsBySource.filterKeys { it in enabledIds }
                }
                val initialFeatured = current.featuredHomeItem
                    ?.takeIf { it.sourceId in enabledIds }
                    ?: pickFeaturedItem(initialRows)
                current.copy(
                    homeRowsBySource = initialRows,
                    homeQuickCategories = current.homeQuickCategories.filter { it.sourceId in enabledIds },
                    featuredHomeItem = initialFeatured,
                    isLoading = initialRows.isEmpty(),
                    errorMessage = null
                )
            }

            coroutineScope {
                for (def in enabledDefs) {
                    launch perSource@{
                        val result = loadSingleSourceForHome(def, state.activeProfile, state.preferences)
                        if (!isActive) return@perSource
                        homeMergeMutex.withLock {
                            _uiState.update { current ->
                                val mergedRows = current.homeRowsBySource.toMutableMap()
                                if (result.items.isNotEmpty()) {
                                    mergedRows[result.sourceId] = result.items.take(12)
                                }
                                val mergedCats = (current.homeQuickCategories + result.categories)
                                    .distinctBy { c -> "${c.sourceId}::${c.id}" }
                                    .take(16)
                                val currentFeaturedIs4k = (current.featuredHomeItem?.maxResolutionHeight ?: 0) >= 2160
                                val nextFeatured = if (current.featuredHomeItem == null || !currentFeaturedIs4k) {
                                    pickFeaturedItem(mergedRows) ?: current.featuredHomeItem
                                } else {
                                    current.featuredHomeItem
                                }
                                val stillLoading = current.isLoading && mergedRows.isEmpty()
                                current.copy(
                                    homeRowsBySource = mergedRows,
                                    homeQuickCategories = mergedCats,
                                    featuredHomeItem = nextFeatured,
                                    isLoading = stillLoading
                                )
                            }
                        }
                    }
                }
            }

            if (isActive) {
                _uiState.update { it.copy(isLoading = false) }
                refreshWatchHistory()
            }
        }
    }

    private suspend fun loadSingleSourceForHome(
        def: SourceDefinition,
        profile: UserProfile,
        preferences: PreferenceConfig
    ): SourceHomeLoadResult = withContext(Dispatchers.IO) {
        val sourceId = def.source.id
        val loaded = withTimeoutOrNull(15_000L) {
            try {
                val cats = if (def.source.capabilities.categories && def.operations.categories != null) {
                    runCatching { catalogRepository.getCategories(sourceId, profile) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                var listingItems = if (cats.isNotEmpty() && def.operations.listing != null) {
                    runCatching {
                        catalogRepository.loadListingPage(
                            sourceId = sourceId,
                            pageUrl = cats.first().url,
                            profile = profile,
                            preferences = preferences,
                            session = CatalogRepository.PaginationSession(sourceId)
                        ).items
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                if (listingItems.isEmpty() && def.source.capabilities.search && def.operations.search != null) {
                    listingItems = runCatching {
                        catalogRepository.search(
                            query = "hd",
                            profile = profile,
                            preferences = preferences,
                            sourceIds = setOf(sourceId)
                        ).items
                    }.getOrDefault(emptyList())
                }

                if (listingItems.isEmpty() && def.operations.listing != null) {
                    val fallbackUrl = def.fixtures.firstOrNull { it.operation == "listing" }?.input?.get("url")
                        ?: def.source.baseUrl
                    listingItems = runCatching {
                        catalogRepository.loadListingPage(
                            sourceId = sourceId,
                            pageUrl = fallbackUrl,
                            profile = profile,
                            preferences = preferences,
                            session = CatalogRepository.PaginationSession(sourceId)
                        ).items
                    }.getOrDefault(emptyList())
                }

                if (listingItems.isEmpty()) {
                    listingItems = catalogRepository.getCachedCatalogItems(sourceId, preferences)
                }

                SourceHomeLoadResult(
                    sourceId = sourceId,
                    categories = cats,
                    items = listingItems
                )
            } catch (_: Exception) {
                val cached = catalogRepository.getCachedCatalogItems(sourceId, preferences)
                SourceHomeLoadResult(sourceId = sourceId, categories = emptyList(), items = cached)
            }
        }

        loaded ?: run {
            val cached = catalogRepository.getCachedCatalogItems(sourceId, preferences)
            SourceHomeLoadResult(sourceId = sourceId, categories = emptyList(), items = cached)
        }
    }

    private fun refreshWatchHistory() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.isPrivateSession || !state.activeProfile.historyEnabled) {
                _uiState.update { it.copy(watchHistory = emptyList()) }
                return@launch
            }
            val history = catalogRepository.getWatchHistory(
                profileId = state.activeProfile.id,
                preferences = state.preferences
            )
            _uiState.update { it.copy(watchHistory = history) }
        }
    }

    fun ensureCategoriesLoaded() {
        val enabledIds = _uiState.value.registeredSources
            .filter { it.lifecycleState == DefinitionLifecycleState.ENABLED }
            .map { it.record.sourceId }
        val targetSource = _uiState.value.selectedCategorySourceId
            ?.takeIf { it in enabledIds }
            ?: enabledIds.firstOrNull()

        if (targetSource == null) {
            _uiState.update {
                it.copy(
                    selectedCategorySourceId = null,
                    categoriesForSelectedSource = emptyList(),
                    categoryItems = emptyList(),
                    categoryNextCursor = null
                )
            }
            return
        }
        if (_uiState.value.selectedCategorySourceId != targetSource ||
            _uiState.value.categoriesForSelectedSource.isEmpty()
        ) {
            selectCategorySource(targetSource)
        }
    }

    fun selectCategorySource(sourceId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    selectedCategorySourceId = sourceId,
                    categoriesForSelectedSource = emptyList(),
                    selectedCategory = null,
                    categoryItems = emptyList(),
                    categoryNextCursor = null,
                    categoryPagesLoaded = 0,
                    isLoading = true,
                    errorMessage = null
                )
            }
            try {
                val profile = _uiState.value.activeProfile
                val def = sourceRegistry.getEnabledDefinition(sourceId)
                val cats = if (def?.source?.capabilities?.categories == true && def.operations.categories != null) {
                    catalogRepository.getCategories(sourceId, profile)
                } else {
                    emptyList()
                }
                _uiState.update {
                    it.copy(
                        categoriesForSelectedSource = cats,
                        isLoading = false
                    )
                }
                if (cats.isNotEmpty()) {
                    selectCategory(cats.first())
                } else if (def?.operations?.listing != null) {
                    val fallbackUrl = def.fixtures.firstOrNull { it.operation == "listing" }?.input?.get("url")
                        ?: def.source.baseUrl
                    selectCategory(
                        CategoryItem(
                            sourceId = sourceId,
                            id = "default",
                            title = "All Videos",
                            url = fallbackUrl
                        )
                    )
                }
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "${e.code.name}: ${CredentialRedactor.redact(e.message)}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = CredentialRedactor.redact(e.message ?: "Failed to load categories")
                    )
                }
            }
        }
    }

    fun openQuickCategory(category: CategoryItem) {
        navigateTo(HarborScreen.CATEGORIES)
        viewModelScope.launch {
            val profile = _uiState.value.activeProfile
            _uiState.update {
                it.copy(
                    selectedCategorySourceId = category.sourceId,
                    isLoading = true
                )
            }
            val cats = runCatching { catalogRepository.getCategories(category.sourceId, profile) }
                .getOrDefault(listOf(category))
            _uiState.update { it.copy(categoriesForSelectedSource = cats) }
            selectCategory(category)
        }
    }

    fun selectCategory(category: CategoryItem) {
        viewModelScope.launch {
            val session = CatalogRepository.PaginationSession(category.sourceId)
            activePaginationSession = session
            _uiState.update {
                it.copy(
                    selectedCategory = category,
                    categoryItems = emptyList(),
                    categoryNextCursor = null,
                    categoryPagesLoaded = 0,
                    isLoading = true,
                    errorMessage = null
                )
            }
            try {
                val state = _uiState.value
                val page = catalogRepository.loadListingPage(
                    sourceId = category.sourceId,
                    pageUrl = category.url,
                    profile = state.activeProfile,
                    preferences = state.preferences,
                    session = session
                )
                _uiState.update {
                    it.copy(
                        categoryItems = page.items.take(60),
                        categoryNextCursor = page.nextCursor,
                        categoryPagesLoaded = session.pagesLoaded,
                        isLoading = false
                    )
                }
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "${e.code.name}: ${CredentialRedactor.redact(e.message)}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = CredentialRedactor.redact(e.message ?: "Failed to load category listing")
                    )
                }
            }
        }
    }

    fun loadNextCategoryPage() {
        val category = _uiState.value.selectedCategory ?: return
        val cursor = _uiState.value.categoryNextCursor ?: return
        val session = activePaginationSession ?: return
        if (session.sourceId != category.sourceId) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val state = _uiState.value
                val page = catalogRepository.loadListingPage(
                    sourceId = category.sourceId,
                    pageUrl = cursor,
                    profile = state.activeProfile,
                    preferences = state.preferences,
                    session = session
                )
                val merged = normalizer.deduplicateItems(_uiState.value.categoryItems + page.items)
                val enabledIds = sourceRegistry.getEnabledSourceIds()
                val filtered = filterEngine.filterAndRank(merged, state.preferences, enabledIds).take(60)
                _uiState.update {
                    it.copy(
                        categoryItems = filtered,
                        categoryNextCursor = page.nextCursor,
                        categoryPagesLoaded = session.pagesLoaded,
                        isLoading = false
                    )
                }
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "${e.code.name}: ${CredentialRedactor.redact(e.message)}"
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSearchSourceFilter(sourceId: String?) {
        _uiState.update { it.copy(searchSourceFilterId = sourceId) }
    }

    fun executeSearch(queryOverride: String? = null) {
        val q = (queryOverride ?: _uiState.value.searchQuery).trim()
        _uiState.update { it.copy(searchQuery = q) }
        if (q.isEmpty()) {
            _uiState.update { it.copy(searchResults = emptyList(), searchErrors = emptyMap()) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, searchErrors = emptyMap()) }
            val state = _uiState.value
            val sourceScope = state.searchSourceFilterId?.let { setOf(it) }
            val result = catalogRepository.search(
                query = q,
                profile = state.activeProfile,
                preferences = state.preferences,
                sourceIds = sourceScope
            )
            val errStrings = result.sourceErrors.mapValues { (_, ex) ->
                "${ex.code.name}: ${CredentialRedactor.redact(ex.message)}"
            }
            _uiState.update {
                it.copy(
                    searchResults = result.items.take(60),
                    searchErrors = errStrings,
                    isLoading = false
                )
            }
        }
    }

    fun refreshFavorites() {
        viewModelScope.launch {
            val state = _uiState.value
            val favs = catalogRepository.getFavorites(
                profileId = state.activeProfile.id,
                preferences = state.preferences
            )
            _uiState.update { it.copy(favorites = favs) }
        }
    }

    fun toggleFavorite(item: CatalogItem) {
        viewModelScope.launch {
            val isFav = catalogRepository.toggleFavorite(_uiState.value.activeProfile.id, item)
            if (_uiState.value.selectedVideo?.compositeId == item.compositeId) {
                _uiState.update { it.copy(selectedVideoIsFavorite = isFav) }
            }
            refreshFavorites()
        }
    }

    fun openVideoDetails(item: CatalogItem) {
        viewModelScope.launch {
            val state = _uiState.value
            val enabledIds = sourceRegistry.getEnabledSourceIds()
            if (!filterEngine.isItemEligible(item, state.preferences, enabledIds)) {
                _uiState.update {
                    it.copy(errorMessage = "Video is excluded by active profile preferences.")
                }
                return@launch
            }
            val def = sourceRegistry.getEnabledDefinition(item.sourceId)
            val isFav = catalogRepository.isFavorite(state.activeProfile.id, item.compositeId)
            val supportsPlayback = def?.source?.capabilities?.playback == true && def.operations.playback != null

            _uiState.update {
                it.copy(
                    selectedVideo = item,
                    selectedVideoIsFavorite = isFav,
                    selectedVideoPlayback = null,
                    selectedVideoSupportsPlayback = supportsPlayback,
                    isLoading = true,
                    errorMessage = null
                )
            }
            navigateTo(HarborScreen.VIDEO_DETAILS)

            // Enrich details and preview playback candidates
            val enriched = runCatching {
                catalogRepository.getVideoDetails(item, state.activeProfile, state.preferences)
            }.getOrElse { item }

            val playbackPreview = if (supportsPlayback && def != null) {
                runCatching {
                    playbackResolver.resolvePlayback(
                        item = enriched,
                        definition = def,
                        profile = state.activeProfile,
                        preferences = state.preferences,
                        enabledSourceIds = enabledIds
                    )
                }.getOrNull()
            } else {
                null
            }

            val maxCandHeight = playbackPreview?.candidates
                ?.maxOfOrNull { it.resolutionHeight ?: 0 } ?: 0
            val finalVideo = if (maxCandHeight > (enriched.maxResolutionHeight ?: 0)) {
                val upgradedLabel = when {
                    maxCandHeight >= 2160 -> "4K"
                    maxCandHeight >= 1440 -> "1440p"
                    maxCandHeight >= 1080 -> "1080p"
                    maxCandHeight >= 720 -> "720p"
                    else -> playbackPreview?.candidates
                        ?.firstOrNull { it.resolutionHeight == maxCandHeight }
                        ?.qualityLabel ?: enriched.maxQualityLabel
                }
                enriched.copy(
                    maxResolutionHeight = maxCandHeight,
                    maxQualityLabel = upgradedLabel
                )
            } else {
                enriched
            }

            if (playbackPreview != null) {
                selectedVideoPlaybackResolvedAtMs = System.currentTimeMillis()
            } else {
                selectedVideoPlaybackResolvedAtMs = 0L
            }

            _uiState.update {
                it.copy(
                    selectedVideo = finalVideo,
                    selectedVideoPlayback = playbackPreview,
                    isLoading = false
                )
            }

            val latestState = _uiState.value
            val preloadCandidate = playbackPreview?.selectedCandidate
                ?: playbackPreview?.candidates?.firstOrNull()
            if (playbackPreview != null &&
                preloadCandidate != null &&
                def != null &&
                !latestState.isPrivateSession &&
                !latestState.offlineFixtureMode &&
                latestState.videoPreloadEnabled
            ) {
                playerController.preloadCandidate(
                    sourceId = finalVideo.sourceId,
                    sourceItemId = finalVideo.sourceItemId,
                    candidate = preloadCandidate,
                    refererUrl = playbackPreview.sourcePageUrl,
                    allowLan = latestState.activeProfile.allowLanSources && def.network.allowLan,
                    maxBytes = 32L * 1024L * 1024L,
                    onFinished = { refreshMediaCacheStats() }
                )
            }
        }
    }

    fun preloadSelectedVideoMore() {
        val state = _uiState.value
        if (state.isPrivateSession) return
        val item = state.selectedVideo ?: return
        val preview = state.selectedVideoPlayback ?: return
        val candidate = preview.selectedCandidate ?: preview.candidates.firstOrNull() ?: return
        viewModelScope.launch {
            val def = sourceRegistry.getEnabledDefinition(item.sourceId) ?: return@launch
            playerController.preloadCandidate(
                sourceId = item.sourceId,
                sourceItemId = item.sourceItemId,
                candidate = candidate,
                refererUrl = preview.sourcePageUrl,
                allowLan = state.activeProfile.allowLanSources && def.network.allowLan,
                maxBytes = 128L * 1024L * 1024L,
                onFinished = { refreshMediaCacheStats() }
            )
        }
    }

    fun refreshSelectedVideoDetails() {
        val current = _uiState.value.selectedVideo ?: return
        openVideoDetails(current)
    }

    fun playVideo(item: CatalogItem, candidateOverride: PlaybackCandidate? = null) {
        viewModelScope.launch {
            val state = _uiState.value
            val enabledIds = sourceRegistry.getEnabledSourceIds()
            if (!filterEngine.isItemEligible(item, state.preferences, enabledIds)) {
                _uiState.update {
                    it.copy(
                        errorMessage = "${HarborErrorCode.POLICY_BLOCKED.name}: Item '${item.compositeId}' is excluded by active profile preferences."
                    )
                }
                return@launch
            }

            val def = sourceRegistry.getEnabledDefinition(item.sourceId)
            if (def == null) {
                _uiState.update { it.copy(errorMessage = "Source '${item.sourceId}' is not enabled.") }
                return@launch
            }
            if (!def.source.capabilities.playback || def.operations.playback == null) {
                _uiState.update {
                    it.copy(
                        errorMessage = "Source '${def.source.name}' provides catalog metadata only and has no declarative playback stream."
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val now = System.currentTimeMillis()
                val cachedPreview = state.selectedVideoPlayback
                val canReusePreview = cachedPreview != null &&
                    cachedPreview.sourceId == item.sourceId &&
                    cachedPreview.sourceItemId == item.sourceItemId &&
                    cachedPreview.candidates.isNotEmpty() &&
                    (now - selectedVideoPlaybackResolvedAtMs) in 0L until (5L * 60L * 1000L)

                val resolved = if (canReusePreview) {
                    cachedPreview!!
                } else {
                    playbackResolver.resolvePlayback(
                        item = item,
                        definition = def,
                        profile = state.activeProfile,
                        preferences = state.preferences,
                        enabledSourceIds = enabledIds
                    ).also {
                        selectedVideoPlaybackResolvedAtMs = System.currentTimeMillis()
                    }
                }
                val finalResolution = if (candidateOverride != null && candidateOverride in resolved.candidates) {
                    resolved.copy(selectedCandidate = candidateOverride)
                } else {
                    resolved
                }

                val resumePos = if (state.isPrivateSession || !state.activeProfile.historyEnabled) {
                    0L
                } else {
                    db.watchHistoryDao().getEntry(state.activeProfile.id, item.compositeId)?.resumePositionMs ?: 0L
                }

                _uiState.update {
                    it.copy(
                        selectedVideoPlayback = finalResolution,
                        isLoading = false
                    )
                }
                navigateTo(HarborScreen.PLAYER)

                playerController.startPlayback(
                    title = item.title,
                    resolution = finalResolution,
                    isPrivateSession = state.isPrivateSession,
                    allowLan = state.activeProfile.allowLanSources && def.network.allowLan,
                    initialPositionMs = resumePos,
                    onResolveExpired = { prevRes, attempt ->
                        val curState = _uiState.value
                        val curDef = sourceRegistry.getEnabledDefinition(item.sourceId) ?: def
                        val curEnabled = sourceRegistry.getEnabledSourceIds()
                        playbackResolver.resolveAgainOnExpired(
                            previousResult = prevRes,
                            item = item,
                            definition = curDef,
                            profile = curState.activeProfile,
                            preferences = curState.preferences,
                            enabledSourceIds = curEnabled,
                            attemptNumber = attempt
                        )
                    },
                    onSaveProgress = { posMs ->
                        viewModelScope.launch {
                            val latestState = _uiState.value
                            catalogRepository.recordWatchProgress(
                                profile = latestState.activeProfile,
                                item = item,
                                resumePositionMs = posMs
                            )
                        }
                    }
                )
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "${e.code.name}: ${CredentialRedactor.redact(e.message)}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = CredentialRedactor.redact(e.message ?: "Playback resolution failed")
                    )
                }
            }
        }
    }

    fun loadBundledDefinitionForApproval(assetPath: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importError = null, importMessage = null) }
            try {
                val rawText = withContext(Dispatchers.IO) {
                    appContext.assets.open(assetPath).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
                importRawDefinitionAndPrepareApproval(rawText)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        importError = "Failed to read bundled asset '$assetPath': ${e.message}"
                    )
                }
            }
        }
    }

    fun quickImportAndEnableExampleDemo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val rawText = withContext(Dispatchers.IO) {
                    appContext.assets.open("definitions/example-library.yaml")
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                }
                val outcome = sourceRegistry.importDefinition(rawText)
                if (!outcome.validationResult.isValid || outcome.record == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Bundled demo failed validation"
                        )
                    }
                    return@launch
                }
                val sourceId = outcome.record.sourceId
                val review = buildApprovalReview(outcome)
                _uiState.update {
                    it.copy(
                        approvalReview = review,
                        isLoading = false
                    )
                }
                navigateTo(HarborScreen.DEFINITION_IMPORT)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load bundled demo: ${e.message}"
                    )
                }
            }
        }
    }

    fun importFromTextOrFilePath(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(importError = "Enter a local file path or paste YAML/JSON definition text.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importError = null, importMessage = null) }
            try {
                val rawText = withContext(Dispatchers.IO) {
                    val potentialFile = if (!trimmed.contains('\n') && trimmed.length < 512) {
                        File(trimmed)
                    } else {
                        null
                    }
                    if (potentialFile != null && potentialFile.exists() && potentialFile.isFile) {
                        if (potentialFile.length() > 524_288L) {
                            throw HarborException(
                                HarborErrorCode.RESPONSE_TOO_LARGE,
                                "Definition file exceeds 512 KiB budget"
                            )
                        }
                        potentialFile.readText(StandardCharsets.UTF_8)
                    } else {
                        trimmed
                    }
                }
                importRawDefinitionAndPrepareApproval(rawText)
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        importError = "${e.code.name}: ${CredentialRedactor.redact(e.message)}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        importError = CredentialRedactor.redact(e.message ?: "Import failed")
                    )
                }
            }
        }
    }

    fun importFromHttpsUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            _uiState.update {
                it.copy(importError = "POLICY_BLOCKED: Definition import URLs must use HTTPS.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importError = null, importMessage = null) }
            try {
                val rawText = withContext(Dispatchers.IO) {
                    val uri = URI(trimmed)
                    val host = uri.host ?: throw HarborException(
                        HarborErrorCode.DESTINATION_BLOCKED,
                        "Definition URL missing hostname"
                    )
                    val allowLan = _uiState.value.activeProfile.allowLanSources
                    val resolved = Dns.SYSTEM.lookup(host)
                    if (!AddressSafetyValidator.isSafePublicAddress(host, resolved, allowLan)) {
                        throw HarborException(
                            HarborErrorCode.DESTINATION_BLOCKED,
                            "Definition URL destination '$host' blocked by address safety policy"
                        )
                    }

                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .followRedirects(false)
                        .dns(object : Dns {
                            override fun lookup(hostname: String): List<InetAddress> {
                                val addrs = Dns.SYSTEM.lookup(hostname)
                                if (!AddressSafetyValidator.isSafePublicAddress(hostname, addrs, allowLan)) {
                                    throw IOException("Destination '$hostname' blocked")
                                }
                                return addrs
                            }
                        })
                        .build()

                    val req = Request.Builder().url(trimmed).get().build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw HarborException(
                                HarborErrorCode.NETWORK_UNAVAILABLE,
                                "HTTP ${resp.code} fetching definition"
                            )
                        }
                        val body = resp.body ?: throw HarborException(
                            HarborErrorCode.DEFINITION_INVALID,
                            "Empty definition response"
                        )
                        val bytes = body.bytes()
                        if (bytes.size > 524_288) {
                            throw HarborException(
                                HarborErrorCode.RESPONSE_TOO_LARGE,
                                "Remote definition exceeds 512 KiB budget"
                            )
                        }
                        String(bytes, StandardCharsets.UTF_8)
                    }
                }
                importRawDefinitionAndPrepareApproval(rawText)
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        importError = "${e.code.name}: ${CredentialRedactor.redact(e.message)}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        importError = CredentialRedactor.redact(e.message ?: "HTTPS definition fetch failed")
                    )
                }
            }
        }
    }

    fun reviewExistingSourceApproval(source: SourceRegistry.RegisteredSource) {
        val def = source.definition
        if (def == null) {
            _uiState.update {
                it.copy(importError = "Source '${source.record.sourceId}' could not be parsed for approval review.")
            }
            return
        }
        val review = ApprovalReviewState(
            sourceId = def.source.id,
            sourceName = def.source.name,
            version = def.source.version,
            author = def.source.author,
            description = def.source.description,
            catalogHosts = def.network.allowedHosts.catalog,
            imageHosts = def.network.allowedHosts.images,
            mediaHosts = def.network.allowedHosts.media,
            authHosts = def.network.allowedHosts.authentication,
            httpsOnly = def.network.httpsOnly,
            allowLan = def.network.allowLan,
            authType = def.authentication.type,
            authProfileId = def.authentication.profileId,
            authScopes = def.authentication.scopes,
            maxConcurrentRequests = def.network.limits.maxConcurrentRequests,
            minRequestIntervalMs = def.network.limits.minRequestIntervalMs,
            maxCatalogResponseBytes = def.network.limits.maxCatalogResponseBytes,
            maxItemsPerPage = def.network.limits.maxItemsPerPage,
            permissionBasis = def.source.permission?.basis,
            permissionReference = def.source.permission?.reference,
            permissionNotes = def.source.permission?.notes,
            isValid = true,
            validationIssues = emptyList(),
            approvalDiff = null,
            isPendingUpdate = source.lifecycleState == DefinitionLifecycleState.PENDING_UPDATE,
            lifecycleState = source.lifecycleState
        )
        _uiState.update { it.copy(approvalReview = review, importError = null, importMessage = null) }
        navigateTo(HarborScreen.DEFINITION_IMPORT)
    }

    private suspend fun importRawDefinitionAndPrepareApproval(rawText: String) {
        val outcome = sourceRegistry.importDefinition(rawText)
        val review = buildApprovalReview(outcome)
        val validationErrors = outcome.validationResult.errors
        _uiState.update {
            it.copy(
                approvalReview = review,
                isLoading = false,
                importError = if (!outcome.validationResult.isValid) {
                    validationErrors.joinToString("\n") { issue ->
                        "[${issue.stage}] ${issue.code.name} at ${issue.path}: ${issue.message}"
                    }
                } else {
                    null
                },
                importMessage = if (outcome.validationResult.isValid) {
                    "Validated offline. Review requested domains & permissions below before approving."
                } else {
                    null
                }
            )
        }
    }

    private fun buildApprovalReview(outcome: SourceRegistry.ImportOutcome): ApprovalReviewState? {
        val def = outcome.validationResult.definition ?: return null
        val state = outcome.record?.let {
            runCatching { DefinitionLifecycleState.valueOf(it.lifecycleState) }.getOrNull()
        } ?: outcome.validationResult.lifecycleState
        return ApprovalReviewState(
            sourceId = def.source.id,
            sourceName = def.source.name,
            version = def.source.version,
            author = def.source.author,
            description = def.source.description,
            catalogHosts = def.network.allowedHosts.catalog,
            imageHosts = def.network.allowedHosts.images,
            mediaHosts = def.network.allowedHosts.media,
            authHosts = def.network.allowedHosts.authentication,
            httpsOnly = def.network.httpsOnly,
            allowLan = def.network.allowLan,
            authType = def.authentication.type,
            authProfileId = def.authentication.profileId,
            authScopes = def.authentication.scopes,
            maxConcurrentRequests = def.network.limits.maxConcurrentRequests,
            minRequestIntervalMs = def.network.limits.minRequestIntervalMs,
            maxCatalogResponseBytes = def.network.limits.maxCatalogResponseBytes,
            maxItemsPerPage = def.network.limits.maxItemsPerPage,
            permissionBasis = def.source.permission?.basis,
            permissionReference = def.source.permission?.reference,
            permissionNotes = def.source.permission?.notes,
            isValid = outcome.validationResult.isValid,
            validationIssues = outcome.validationResult.issues,
            approvalDiff = outcome.approvalDiff,
            isPendingUpdate = state == DefinitionLifecycleState.PENDING_UPDATE,
            lifecycleState = state
        )
    }

    fun approveReviewedSource() {
        val review = _uiState.value.approvalReview ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importError = null) }
            try {
                if (review.isPendingUpdate) {
                    sourceRegistry.approvePendingUpdate(review.sourceId, userApproved = true)
                } else {
                    sourceRegistry.approveAndEnable(review.sourceId, userApproved = true)
                }
                _uiState.update {
                    it.copy(
                        approvalReview = null,
                        isLoading = false,
                        importMessage = "Source '${review.sourceName}' approved and enabled.",
                        statusMessage = "Source '${review.sourceName}' approved and enabled."
                    )
                }
                navigateTo(HarborScreen.SOURCES, clearBackStack = true)
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        importError = "${e.code.name}: ${CredentialRedactor.redact(e.message)}"
                    )
                }
            }
        }
    }

    fun dismissApprovalReview() {
        _uiState.update { it.copy(approvalReview = null, importError = null, importMessage = null) }
    }

    fun toggleSourceEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                sourceRegistry.setSourceEnabled(sourceId, enabled)
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(errorMessage = "${e.code.name}: ${CredentialRedactor.redact(e.message)}")
                }
            }
        }
    }

    fun rollbackSource(sourceId: String) {
        viewModelScope.launch {
            try {
                sourceRegistry.rollback(sourceId)
                _uiState.update { it.copy(statusMessage = "Rolled back '$sourceId' to previous working revision.") }
            } catch (e: HarborException) {
                _uiState.update {
                    it.copy(errorMessage = "${e.code.name}: ${CredentialRedactor.redact(e.message)}")
                }
            }
        }
    }

    fun removeSource(sourceId: String) {
        viewModelScope.launch {
            sourceRegistry.removeSource(sourceId)
            _uiState.update { it.copy(statusMessage = "Removed source '$sourceId'.") }
        }
    }

    fun runDiagnosticsForSource(source: SourceRegistry.RegisteredSource) {
        val def = source.definition
        if (def == null) {
            _uiState.update {
                it.copy(errorMessage = "Source '${source.record.sourceId}' definition could not be parsed.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    diagnosticsSourceName = def.source.name,
                    diagnosticsReport = null
                )
            }
            navigateTo(HarborScreen.DIAGNOSTICS)

            val report = withContext(Dispatchers.IO) {
                val baseReport = fixtureRunner.runFixtures(def) { relativePath ->
                    appContext.assets.open(relativePath)
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                }
                baseReport.copy(
                    referenceRepository = baseReport.referenceRepository ?: MagicPhSettingsAdapter.REPOSITORY_URL,
                    pinnedCommit = baseReport.pinnedCommit ?: MagicPhSettingsAdapter.PINNED_COMMIT,
                    normalizedSourceStatus = baseReport.normalizedSourceStatus
                        ?: MagicPhSettingsAdapter.findById(def.source.id)?.sourceStatus
                        ?: source.lifecycleState.name.lowercase(Locale.ROOT)
                )
            }
            _uiState.update {
                it.copy(
                    diagnosticsReport = report,
                    isLoading = false
                )
            }
        }
    }

    fun rerunDiagnostics() {
        val sourceId = _uiState.value.diagnosticsReport?.sourceId ?: return
        val source = _uiState.value.registeredSources.firstOrNull { it.record.sourceId == sourceId } ?: return
        runDiagnosticsForSource(source)
    }

    private fun discoverBundledDefinitions(): List<BundledDefinitionSummary> {
        val files = runCatching { appContext.assets.list("definitions")?.sorted().orEmpty() }
            .getOrDefault(emptyList())
        return files.filter { it.endsWith(".yaml") || it.endsWith(".yml") || it.endsWith(".json") }
            .map { fileName ->
                val title = fileName.substringBeforeLast('.')
                    .split('-')
                    .joinToString(" ") { part ->
                        part.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString() }
                    }
                BundledDefinitionSummary(
                    assetPath = "definitions/$fileName",
                    fileName = fileName,
                    title = title
                )
            }
    }

    override fun onCleared() {
        super.onCleared()
        playerController.release()
    }
}

/**
 * Serves bundled offline fixture payloads when Offline Fixture Mode is active while still enforcing
 * [RequestPolicyEngine] URL, header, and destination checks. Falls back to [liveTransport] otherwise.
 */
private class AssetFixtureAwareHttpTransport(
    private val appContext: android.content.Context,
    private val sourceRegistry: SourceRegistry,
    private val policyEngine: RequestPolicyEngine,
    private val liveTransport: HttpTransport,
    private val isOfflineMode: () -> Boolean
) : HttpTransport {

    override suspend fun executeGet(
        url: String,
        definition: SourceDefinition,
        purpose: HostPurpose,
        extraHeaders: Map<String, String>,
        userAllowLan: Boolean
    ): HttpResponseData {
        val isExampleHost = runCatching {
            URI(url).host?.endsWith(".example.org", ignoreCase = true) == true
        }.getOrDefault(false)

        if (!isOfflineMode() && !isExampleHost) {
            return liveTransport.executeGet(url, definition, purpose, extraHeaders, userAllowLan)
        }

        policyEngine.mergeAndValidateHeaders(definition = definition, extraHeaders = extraHeaders)
        policyEngine.validateUrl(
            url = url,
            definition = definition,
            purpose = purpose,
            userAllowLan = userAllowLan,
            resolveDns = false
        )

        val matchedAssetPath = resolveFixtureAssetForUrl(url, definition)
        if (matchedAssetPath != null) {
            val body = withContext(Dispatchers.IO) {
                appContext.assets.open(matchedAssetPath)
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            }
            return HttpResponseData(
                requestedUrl = url,
                finalUrl = url,
                statusCode = 200,
                headers = emptyMap(),
                body = body
            )
        }

        return liveTransport.executeGet(url, definition, purpose, extraHeaders, userAllowLan)
    }

    private fun resolveFixtureAssetForUrl(url: String, definition: SourceDefinition): String? {
        val fixtures = definition.fixtures
        if (fixtures.isEmpty()) return null

        // Exact input.url / input.detailUrl match
        fixtures.firstOrNull { f ->
            f.input["url"] == url || f.input["detailUrl"] == url
        }?.let { return it.responseFile }

        // Categories operation URL match
        definition.operations.categories?.let { catOp ->
            val catUrl = runCatching {
                policyEngine.buildInterpolatedUrl(
                    requestDef = catOp.request,
                    definition = definition,
                    variables = emptyMap(),
                    allowedVariables = setOf("source.baseUrl")
                )
            }.getOrNull()
            if (catUrl == url) {
                fixtures.firstOrNull { it.operation == "categories" }?.let { return it.responseFile }
            }
        }

        // Intermediate embed step fixture convention (e.g. /embed/ in intermediate-embed-demo)
        if (url.contains("/embed/", ignoreCase = true)) {
            val embedCandidate = "fixtures/${definition.source.id}/embed.html"
            val exists = runCatching { appContext.assets.open(embedCandidate).use { true } }.getOrDefault(false)
            if (exists) return embedCandidate
        }

        // Search request URL -> reuse search/listing fixture in offline demo mode
        definition.operations.search?.let { searchOp ->
            val searchBase = searchOp.request.url.replace("\${source.baseUrl}", definition.source.baseUrl)
            if (url.startsWith(searchBase, ignoreCase = true)) {
                return fixtures.firstOrNull { it.operation == "search" }?.responseFile
                    ?: fixtures.firstOrNull { it.operation == "listing" }?.responseFile
            }
        }

        // Fallback for additional categories or watch URLs within the same fixture source
        if (url.contains("/watch/", ignoreCase = true) || url.contains("video_by_id", ignoreCase = true)) {
            return fixtures.firstOrNull { it.operation == "details" || it.operation == "playback" }?.responseFile
        }
        if (url.contains("/categories", ignoreCase = true) || url.contains("search", ignoreCase = true)) {
            return fixtures.firstOrNull { it.operation == "listing" }?.responseFile
        }

        return null
    }
}
