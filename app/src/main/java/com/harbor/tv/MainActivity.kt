package com.harbor.tv

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.harbor.tv.core.model.DefinitionLifecycleState
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import com.harbor.tv.core.tvui.HarborColors
import com.harbor.tv.core.tvui.HarborNavDestination
import com.harbor.tv.core.tvui.HarborTvTheme
import com.harbor.tv.core.tvui.TvNavSidebar
import com.harbor.tv.feature.catalog.CategoriesScreen
import com.harbor.tv.feature.catalog.FavoritesScreen
import com.harbor.tv.feature.catalog.HomeScreen
import com.harbor.tv.feature.details.VideoDetailsScreen
import com.harbor.tv.feature.onboarding.PreferenceSetupScreen
import com.harbor.tv.feature.onboarding.ProfileSelectionScreen
import com.harbor.tv.feature.onboarding.WelcomeScreen
import com.harbor.tv.feature.player.PlayerScreen
import com.harbor.tv.feature.search.SearchScreen
import com.harbor.tv.feature.settings.SettingsScreen
import com.harbor.tv.feature.sources.DefinitionImportScreen
import com.harbor.tv.feature.sources.DiagnosticsScreen
import com.harbor.tv.feature.sources.SourcesScreen

class MainActivity : ComponentActivity() {

    private val viewModel: HarborViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureCoilImageLoader()
        setContent {
            HarborTvTheme {
                HarborTvApp(viewModel = viewModel)
            }
        }
    }

    private fun configureCoilImageLoader() {
        val userAgent =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val client = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(6, 30, TimeUnit.SECONDS))
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val original = chain.request()
                    val request = if (original.header("User-Agent") == null) {
                        original.newBuilder()
                            .header("User-Agent", userAgent)
                            .build()
                    } else {
                        original
                    }
                    return chain.proceed(request)
                }
            })
            .build()

        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .okHttpClient(client)
                .memoryCache {
                    MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.08)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("harbor_thumb_cache"))
                        .maxSizeBytes(128L * 1024L * 1024L)
                        .build()
                }
                .allowRgb565(true)
                .crossfade(false)
                .build()
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            Coil.imageLoader(applicationContext).memoryCache?.clear()
        }
    }
}

@Composable
private fun HarborTvApp(viewModel: HarborViewModel) {
    val state by viewModel.uiState.collectAsState()
    val preloadStatus by viewModel.playerController.preloadStatus.collectAsState()

    val enabledSourceIds = state.registeredSources
        .filter { it.lifecycleState == DefinitionLifecycleState.ENABLED }
        .map { it.record.sourceId }

    val showSidebar = state.currentScreen in setOf(
        HarborScreen.HOME,
        HarborScreen.CATEGORIES,
        HarborScreen.SEARCH,
        HarborScreen.FAVORITES,
        HarborScreen.SOURCES,
        HarborScreen.SETTINGS
    )

    val activeNavDest = when (state.currentScreen) {
        HarborScreen.HOME -> HarborNavDestination.HOME
        HarborScreen.CATEGORIES -> HarborNavDestination.CATEGORIES
        HarborScreen.SEARCH -> HarborNavDestination.SEARCH
        HarborScreen.FAVORITES -> HarborNavDestination.FAVORITES
        HarborScreen.SOURCES -> HarborNavDestination.SOURCES
        HarborScreen.SETTINGS -> HarborNavDestination.SETTINGS
        else -> HarborNavDestination.HOME
    }

    BackHandler(enabled = state.currentScreen != HarborScreen.WELCOME && state.currentScreen != HarborScreen.HOME) {
        viewModel.navigateBack()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(HarborColors.Background)
    ) {
        if (showSidebar) {
            TvNavSidebar(
                currentDestination = activeNavDest,
                activeProfileName = state.activeProfile.name,
                isPrivateSession = state.isPrivateSession,
                offlineFixtureMode = state.offlineFixtureMode,
                onNavigate = { dest -> viewModel.navigateToSidebarDestination(dest) },
                onSwitchProfile = { viewModel.navigateTo(HarborScreen.PROFILE_SELECTION) }
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (state.currentScreen) {
                HarborScreen.WELCOME -> {
                    WelcomeScreen(
                        onContinueToProfiles = { viewModel.navigateTo(HarborScreen.PROFILE_SELECTION) },
                        onSkipToHome = { viewModel.completeOnboardingAndOpenHome() }
                    )
                }

                HarborScreen.PROFILE_SELECTION -> {
                    ProfileSelectionScreen(
                        profiles = state.profiles,
                        activeProfileId = state.activeProfile.id,
                        isPrivateSession = state.isPrivateSession,
                        errorMessage = state.errorMessage,
                        onSelectProfile = { profile, pin ->
                            viewModel.selectProfile(profile, enteredPin = pin)
                        },
                        onVerifyPin = { profile, pin ->
                            viewModel.verifyProfilePin(profile, pin)
                        },
                        onCreateProfile = { name, pin ->
                            viewModel.createProfile(name, pin)
                        },
                        onTogglePrivateSession = { enabled ->
                            viewModel.togglePrivateSession(enabled)
                        },
                        onContinueToPreferences = {
                            viewModel.navigateTo(HarborScreen.PREFERENCE_SETUP)
                        },
                        onBack = {
                            if (!viewModel.navigateBack()) {
                                viewModel.completeOnboardingAndOpenHome()
                            }
                        }
                    )
                }

                HarborScreen.PREFERENCE_SETUP -> {
                    PreferenceSetupScreen(
                        activeProfile = state.activeProfile,
                        preferences = state.preferences,
                        onCycleTag = { tag -> viewModel.cycleTagPreference(tag) },
                        onSetUnknownMetadataMode = { mode -> viewModel.setUnknownMetadataMode(mode) },
                        onSetQualityPreference = { quality -> viewModel.setQualityPreference(quality) },
                        onFinish = { viewModel.completeOnboardingAndOpenHome() },
                        onBack = { viewModel.navigateBack() }
                    )
                }

                HarborScreen.HOME -> {
                    HomeScreen(
                        activeProfile = state.activeProfile,
                        isPrivateSession = state.isPrivateSession,
                        offlineFixtureMode = state.offlineFixtureMode,
                        enabledSourceIds = enabledSourceIds,
                        quickCategories = state.homeQuickCategories,
                        rowsBySource = state.homeRowsBySource,
                        featuredHomeItem = state.featuredHomeItem,
                        only4kFilter = state.only4kFilter,
                        previewEnabled = state.focusPreviewEnabled,
                        watchHistory = state.watchHistory,
                        topLabelCombos = state.topLabelCombos,
                        personalizedFreshItems = state.personalizedFreshItems,
                        isLoading = state.isLoading,
                        errorMessage = state.errorMessage,
                        lastFocusedKey = state.lastFocusedKeyByScreen[HarborScreen.HOME],
                        onFocusKeyChanged = { key -> viewModel.recordFocusKey(HarborScreen.HOME, key) },
                        onFeaturedItemFocused = { item -> viewModel.setFeaturedHomeItem(item) },
                        onToggleOnly4kFilter = { viewModel.toggleOnly4kFilter() },
                        onQuickBrowse4k = { viewModel.quickSearch4k() },
                        onApplyLabelCombo = { combo -> viewModel.applyLabelCombo(combo) },
                        onOpenVideo = { item -> viewModel.openVideoDetails(item) },
                        onPlayVideo = { item -> viewModel.playVideo(item) },
                        onOpenCategory = { cat -> viewModel.openQuickCategory(cat) },
                        onOpenSourceCategories = { sourceId -> viewModel.openSourceInCategories(sourceId) },
                        onRefresh = { viewModel.refreshHome() },
                        onOpenSources = { viewModel.navigateToSidebarDestination(HarborNavDestination.SOURCES) },
                        onQuickLoadDemo = { viewModel.quickImportAndEnableExampleDemo() },
                        onEnableAllBundledSources = { viewModel.enableAllBundledSources() }
                    )
                }

                HarborScreen.CATEGORIES -> {
                    CategoriesScreen(
                        enabledSourceIds = enabledSourceIds,
                        selectedSourceId = state.selectedCategorySourceId,
                        categories = state.categoriesForSelectedSource,
                        selectedCategory = state.selectedCategory,
                        items = state.categoryItems,
                        only4kFilter = state.only4kFilter,
                        sortMode = state.categorySortMode,
                        previewEnabled = state.focusPreviewEnabled,
                        nextCursor = state.categoryNextCursor,
                        pagesLoaded = state.categoryPagesLoaded,
                        isLoading = state.isLoading,
                        errorMessage = state.errorMessage,
                        lastFocusedKey = state.lastFocusedKeyByScreen[HarborScreen.CATEGORIES],
                        onFocusKeyChanged = { key -> viewModel.recordFocusKey(HarborScreen.CATEGORIES, key) },
                        onToggleOnly4kFilter = { viewModel.toggleOnly4kFilter() },
                        onSelectSortMode = { mode -> viewModel.setCategorySortMode(mode) },
                        onSelectSource = { sourceId -> viewModel.selectCategorySource(sourceId) },
                        onSelectCategory = { cat -> viewModel.selectCategory(cat) },
                        onLoadNextPage = { viewModel.loadNextCategoryPage() },
                        onOpenVideo = { item -> viewModel.openVideoDetails(item) },
                        onOpenSources = { viewModel.navigateToSidebarDestination(HarborNavDestination.SOURCES) }
                    )
                }

                HarborScreen.SEARCH -> {
                    SearchScreen(
                        query = state.searchQuery,
                        enabledSourceIds = enabledSourceIds,
                        selectedSourceFilterId = state.searchSourceFilterId,
                        results = state.searchResults,
                        only4kFilter = state.only4kFilter,
                        sortMode = state.searchSortMode,
                        previewEnabled = state.focusPreviewEnabled,
                        topLabelCombos = state.topLabelCombos,
                        recentSearches = state.recentSearches,
                        sourceErrors = state.searchErrors,
                        isLoading = state.isLoading,
                        lastFocusedKey = state.lastFocusedKeyByScreen[HarborScreen.SEARCH],
                        onFocusKeyChanged = { key -> viewModel.recordFocusKey(HarborScreen.SEARCH, key) },
                        onToggleOnly4kFilter = { viewModel.toggleOnly4kFilter() },
                        onSelectSortMode = { mode -> viewModel.setSearchSortMode(mode) },
                        onApplyLabelCombo = { combo -> viewModel.applyLabelCombo(combo) },
                        onClearRecentSearches = { viewModel.clearRecentSearches() },
                        onQueryChange = { q -> viewModel.updateSearchQuery(q) },
                        onSelectSourceFilter = { id -> viewModel.setSearchSourceFilter(id) },
                        onSearch = { q -> viewModel.executeSearch(q) },
                        onOpenVideo = { item -> viewModel.openVideoDetails(item) }
                    )
                }

                HarborScreen.VIDEO_DETAILS -> {
                    VideoDetailsScreen(
                        item = state.selectedVideo,
                        isFavorite = state.selectedVideoIsFavorite,
                        supportsPlayback = state.selectedVideoSupportsPlayback,
                        playbackPreview = state.selectedVideoPlayback,
                        preloadStatus = preloadStatus,
                        isPrivateSession = state.isPrivateSession,
                        isLoading = state.isLoading,
                        errorMessage = state.errorMessage,
                        onPlayNow = { candidate ->
                            state.selectedVideo?.let { viewModel.playVideo(it, candidate) }
                        },
                        onPreloadMore = { viewModel.preloadSelectedVideoMore() },
                        onToggleFavorite = {
                            state.selectedVideo?.let { viewModel.toggleFavorite(it) }
                        },
                        onHideVideo = {
                            state.selectedVideo?.let { viewModel.hideVideo(it) }
                        },
                        onSearchTag = { tag -> viewModel.searchByTag(tag) },
                        onRefreshDetails = { viewModel.refreshSelectedVideoDetails() },
                        onBack = { viewModel.navigateBack() }
                    )
                }

                HarborScreen.PLAYER -> {
                    PlayerScreen(
                        controller = viewModel.playerController,
                        onExitPlayback = { viewModel.navigateBack() },
                        onPlayPrevious = { viewModel.playPreviousInQueue() },
                        onPlayNext = { viewModel.playNextInQueue() },
                        autoPlayNextEnabled = state.autoPlayNextEnabled,
                        isFavorite = state.selectedVideoIsFavorite,
                        onToggleFavorite = {
                            state.selectedVideo?.let { viewModel.toggleFavorite(it) }
                        }
                    )
                }

                HarborScreen.FAVORITES -> {
                    FavoritesScreen(
                        favorites = state.favorites,
                        watchHistory = state.watchHistory,
                        isPrivateSession = state.isPrivateSession,
                        previewEnabled = state.focusPreviewEnabled,
                        lastFocusedKey = state.lastFocusedKeyByScreen[HarborScreen.FAVORITES],
                        onFocusKeyChanged = { key -> viewModel.recordFocusKey(HarborScreen.FAVORITES, key) },
                        onOpenVideo = { item -> viewModel.openVideoDetails(item) },
                        onRemoveFavorite = { item -> viewModel.toggleFavorite(item) },
                        onRemoveHistoryItem = { compositeId -> viewModel.removeWatchHistoryItem(compositeId) }
                    )
                }

                HarborScreen.SOURCES -> {
                    SourcesScreen(
                        registeredSources = state.registeredSources,
                        bundledDefinitions = state.bundledDefinitions,
                        magicPhProfiles = state.magicPhCatalog,
                        statusMessage = state.statusMessage,
                        errorMessage = state.errorMessage,
                        lastFocusedKey = state.lastFocusedKeyByScreen[HarborScreen.SOURCES],
                        onFocusKeyChanged = { key -> viewModel.recordFocusKey(HarborScreen.SOURCES, key) },
                        onOpenImport = {
                            viewModel.dismissApprovalReview()
                            viewModel.navigateTo(HarborScreen.DEFINITION_IMPORT)
                        },
                        onLoadBundledDefinition = { assetPath ->
                            viewModel.loadBundledDefinitionForApproval(assetPath)
                            viewModel.navigateTo(HarborScreen.DEFINITION_IMPORT)
                        },
                        onReviewApproval = { source -> viewModel.reviewExistingSourceApproval(source) },
                        onToggleEnabled = { id, enabled -> viewModel.toggleSourceEnabled(id, enabled) },
                        onRollback = { id -> viewModel.rollbackSource(id) },
                        onDiagnose = { source -> viewModel.runDiagnosticsForSource(source) },
                        onRemove = { id -> viewModel.removeSource(id) },
                        onEnableAllBundled = { viewModel.enableAllBundledSources() }
                    )
                }

                HarborScreen.DEFINITION_IMPORT -> {
                    DefinitionImportScreen(
                        bundledDefinitions = state.bundledDefinitions,
                        approvalReview = state.approvalReview,
                        importMessage = state.importMessage,
                        importError = state.importError,
                        isLoading = state.isLoading,
                        onLoadBundled = { assetPath -> viewModel.loadBundledDefinitionForApproval(assetPath) },
                        onImportTextOrPath = { input -> viewModel.importFromTextOrFilePath(input) },
                        onImportHttpsUrl = { url -> viewModel.importFromHttpsUrl(url) },
                        onApproveSource = { viewModel.approveReviewedSource() },
                        onCancelApproval = { viewModel.dismissApprovalReview() },
                        onBack = { viewModel.navigateBack() }
                    )
                }

                HarborScreen.DIAGNOSTICS -> {
                    DiagnosticsScreen(
                        sourceName = state.diagnosticsSourceName,
                        report = state.diagnosticsReport,
                        isLoading = state.isLoading,
                        onRerunFixtures = { viewModel.rerunDiagnostics() },
                        onBack = { viewModel.navigateBack() }
                    )
                }

                HarborScreen.SETTINGS -> {
                    SettingsScreen(
                        activeProfile = state.activeProfile,
                        isPrivateSession = state.isPrivateSession,
                        offlineFixtureMode = state.offlineFixtureMode,
                        videoPreloadEnabled = state.videoPreloadEnabled,
                        autoPlayNextEnabled = state.autoPlayNextEnabled,
                        focusPreviewEnabled = state.focusPreviewEnabled,
                        minResolutionHeight = state.preferences.minResolutionHeight,
                        minDurationSeconds = state.preferences.minDurationSeconds,
                        avoidRepeatsEnabled = state.preferences.avoidRepeatsEnabled,
                        hiddenVideosCount = state.preferences.hiddenItemIds.size,
                        tasteProfile = state.tasteProfile,
                        topLabelCombos = state.topLabelCombos,
                        mediaCacheLimitMb = state.mediaCacheLimitMb,
                        mediaCacheUsedBytes = state.mediaCacheUsedBytes,
                        mediaCacheMaxBytes = state.mediaCacheMaxBytes,
                        availableStorageBytes = state.availableStorageBytes,
                        hwCodecSummary = state.hwCodecSummary,
                        statusMessage = state.statusMessage,
                        onTogglePrivateSession = { enabled -> viewModel.togglePrivateSession(enabled) },
                        onSetUnknownMetadataMode = { mode -> viewModel.setUnknownMetadataMode(mode) },
                        onSetQualityPreference = { quality, res, kbps ->
                            viewModel.setQualityPreference(quality, res, kbps)
                        },
                        onSetMinResolutionHeight = { minH -> viewModel.setMinResolutionHeight(minH) },
                        onSetMinDurationSeconds = { sec -> viewModel.setMinDurationSeconds(sec) },
                        onToggleAvoidRepeats = { enabled -> viewModel.setAvoidRepeatsEnabled(enabled) },
                        onClearHiddenVideos = { viewModel.clearHiddenVideos() },
                        onResetTasteStats = { viewModel.resetTasteStats() },
                        onToggleAllowLan = { allow -> viewModel.setAllowLanSources(allow) },
                        onToggleHistoryEnabled = { enabled -> viewModel.setHistoryEnabled(enabled) },
                        onToggleOfflineFixtureMode = { enabled -> viewModel.setOfflineFixtureMode(enabled) },
                        onToggleVideoPreload = { enabled -> viewModel.setVideoPreloadEnabled(enabled) },
                        onToggleAutoPlayNext = { enabled -> viewModel.setAutoPlayNextEnabled(enabled) },
                        onToggleFocusPreview = { enabled -> viewModel.setFocusPreviewEnabled(enabled) },
                        onSetMediaCacheLimitMb = { mb -> viewModel.setMediaCacheLimitMb(mb) },
                        onSetProfilePin = { pin -> viewModel.setProfilePin(pin) },
                        onClearCacheAndHistory = { viewModel.clearCacheAndHistory() },
                        onOpenPreferenceSetup = { viewModel.navigateTo(HarborScreen.PREFERENCE_SETUP) },
                        onOpenProfileSelection = { viewModel.navigateTo(HarborScreen.PROFILE_SELECTION) },
                        onOpenSources = { viewModel.navigateToSidebarDestination(HarborNavDestination.SOURCES) }
                    )
                }
            }
        }
    }
}
