package com.harbor.tv.feature.player

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.PlaybackCandidate
import com.harbor.tv.core.model.PlaybackResolutionResult
import com.harbor.tv.core.model.PlaybackStreamType
import com.harbor.tv.core.security.AddressSafetyValidator
import com.harbor.tv.core.security.CredentialRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class MediaCacheStats(
    val usedBytes: Long,
    val maxBytes: Long,
    val availableDiskBytes: Long
)

data class PlayerTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val language: String?,
    val isSelected: Boolean
)

data class PlayerUiState(
    val title: String = "",
    val sourceId: String = "",
    val sourceItemId: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isSeekable: Boolean = false,
    val isLive: Boolean = false,
    val isPrivateSession: Boolean = false,
    val decodedWidth: Int = 0,
    val decodedHeight: Int = 0,
    val decodedFrameRate: Float = 0f,
    val videoCodec: String? = null,
    val hasNextInQueue: Boolean = false,
    val hasPreviousInQueue: Boolean = false,
    val nextItemTitle: String? = null,
    val upNextPreloadLabel: String? = null,
    val candidates: List<PlaybackCandidate> = emptyList(),
    val activeCandidate: PlaybackCandidate? = null,
    val audioTracks: List<PlayerTrackOption> = emptyList(),
    val subtitleTracks: List<PlayerTrackOption> = emptyList(),
    val subtitlesDisabled: Boolean = false,
    val expiryRetryAttempted: Boolean = false,
    val isRecoveringExpiry: Boolean = false,
    val errorCode: HarborErrorCode? = null,
    val errorMessage: String? = null
)

@OptIn(UnstableApi::class)
class HarborPlayerController(
    context: Context,
    private val baseOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        const val DEFAULT_MEDIA_CACHE_LIMIT_MB = 1536
        private const val MIN_MEDIA_CACHE_BYTES = 256L * 1024L * 1024L
        private const val MAX_MEDIA_CACHE_BYTES = 2048L * 1024L * 1024L
        private const val KEEP_FREE_DISK_BYTES = 1024L * 1024L * 1024L

        @Volatile
        private var sharedMediaCache: SimpleCache? = null

        @Volatile
        private var sharedDbProvider: StandaloneDatabaseProvider? = null

        @Volatile
        private var activeMaxCacheBytes: Long = DEFAULT_MEDIA_CACHE_LIMIT_MB.toLong() * 1024L * 1024L

        @Volatile
        private var configuredLimitMb: Int = DEFAULT_MEDIA_CACHE_LIMIT_MB

        fun getOrCreateMediaCache(
            context: Context,
            requestedLimitMb: Int = configuredLimitMb
        ): SimpleCache {
            return sharedMediaCache ?: synchronized(this) {
                sharedMediaCache ?: createMediaCacheLocked(context.applicationContext, requestedLimitMb)
            }
        }

        private fun createMediaCacheLocked(appCtx: Context, requestedLimitMb: Int): SimpleCache {
            // Delete legacy cacheDir media cache if present
            val legacyDir = appCtx.cacheDir.resolve("harbor_media_cache")
            if (legacyDir.exists()) {
                runCatching { legacyDir.deleteRecursively() }
            }

            val cacheDir = appCtx.noBackupFilesDir.resolve("harbor_media_cache").apply {
                if (!exists()) mkdirs()
            }
            val effectiveMaxBytes = computeEffectiveCacheMaxBytes(cacheDir, requestedLimitMb)
            activeMaxCacheBytes = effectiveMaxBytes
            configuredLimitMb = requestedLimitMb

            val dbProvider = sharedDbProvider ?: StandaloneDatabaseProvider(appCtx).also {
                sharedDbProvider = it
            }
            return SimpleCache(
                cacheDir,
                LeastRecentlyUsedCacheEvictor(effectiveMaxBytes),
                dbProvider
            ).also { sharedMediaCache = it }
        }

        private fun computeEffectiveCacheMaxBytes(cacheDir: File, requestedLimitMb: Int): Long {
            val requestedBytes = (requestedLimitMb.coerceIn(256, 2048).toLong() * 1024L * 1024L)
                .coerceIn(MIN_MEDIA_CACHE_BYTES, MAX_MEDIA_CACHE_BYTES)
            val stat = runCatching { StatFs(cacheDir.absolutePath) }.getOrNull() ?: return requestedBytes
            val availableDisk = stat.availableBytes
            val currentUsed = calculateDirSize(cacheDir)
            val maxSafeBudget = (availableDisk + currentUsed - KEEP_FREE_DISK_BYTES)
                .coerceAtLeast(MIN_MEDIA_CACHE_BYTES)
            return minOf(requestedBytes, maxSafeBudget).coerceAtLeast(MIN_MEDIA_CACHE_BYTES)
        }

        private fun calculateDirSize(dir: File): Long {
            if (!dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    private val loadControl = DefaultLoadControl.Builder()
        .setTargetBufferBytes(18 * 1024 * 1024)
        .setBufferDurationsMs(8_000, 20_000, 1_500, 3_000)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(2_000, false)
        .build()

    private val trackSelector = DefaultTrackSelector(appContext).apply {
        parameters = buildUponParameters()
            .setMaxVideoSize(3840, 2160)
            .build()
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLoadControl(loadControl)
        .setTrackSelector(trackSelector)
        .build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val preloader = HarborMediaPreloader(
        cacheProvider = { getOrCreateMediaCache(appContext) },
        baseOkHttpClient = baseOkHttpClient
    )
    val preloadStatus: StateFlow<PreloadStatus> = preloader.status

    private var currentResolution: PlaybackResolutionResult? = null
    private var currentAllowLan: Boolean = false
    private var onResolveExpiredCallback: (suspend (PlaybackResolutionResult, Int) -> PlaybackResolutionResult)? = null
    private var onSaveProgressCallback: ((Long) -> Unit)? = null
    private var onQuarterProgressReachedCallback: (() -> Unit)? = null
    private var quarterProgressFired: Boolean = false
    private var positionTickerJob: Job? = null
    private var lastSavedProgressMs: Long = -1L

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) {
                persistProgressIfAllowed(force = true)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            val ended = playbackState == Player.STATE_ENDED
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L } ?: 0L
            _uiState.update {
                it.copy(
                    isBuffering = buffering,
                    isEnded = ended,
                    durationMs = duration,
                    isSeekable = player.isCurrentMediaItemSeekable,
                    isLive = player.isCurrentMediaItemLive
                )
            }
            if (ended) {
                persistProgressIfAllowed(force = true)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateTracksFromPlayer(tracks)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _uiState.update {
                    it.copy(
                        decodedWidth = videoSize.width,
                        decodedHeight = videoSize.height
                    )
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    init {
        player.addListener(playerListener)
        startPositionTicker()
    }

    fun startPlayback(
        title: String,
        resolution: PlaybackResolutionResult,
        isPrivateSession: Boolean,
        allowLan: Boolean,
        initialPositionMs: Long = 0L,
        onResolveExpired: suspend (PlaybackResolutionResult, Int) -> PlaybackResolutionResult,
        onSaveProgress: (Long) -> Unit,
        onQuarterProgressReached: (() -> Unit)? = null
    ) {
        cancelPreload()
        currentResolution = resolution
        currentAllowLan = allowLan
        onResolveExpiredCallback = onResolveExpired
        onSaveProgressCallback = onSaveProgress
        onQuarterProgressReachedCallback = onQuarterProgressReached
        quarterProgressFired = false
        lastSavedProgressMs = -1L

        val initialCandidate = resolution.selectedCandidate ?: resolution.candidates.firstOrNull()
        val prevQueueState = _uiState.value
        _uiState.value = PlayerUiState(
            title = title,
            sourceId = resolution.sourceId,
            sourceItemId = resolution.sourceItemId,
            isPrivateSession = isPrivateSession,
            hasNextInQueue = prevQueueState.hasNextInQueue,
            hasPreviousInQueue = prevQueueState.hasPreviousInQueue,
            nextItemTitle = prevQueueState.nextItemTitle,
            upNextPreloadLabel = prevQueueState.upNextPreloadLabel,
            candidates = resolution.candidates,
            activeCandidate = initialCandidate,
            expiryRetryAttempted = false,
            errorCode = if (initialCandidate == null) HarborErrorCode.PLAYBACK_NOT_FOUND else null,
            errorMessage = if (initialCandidate == null) "No playable stream candidates found." else null
        )

        if (initialCandidate != null) {
            val startPos = if (isPrivateSession) 0L else initialPositionMs.coerceAtLeast(0L)
            prepareCandidate(initialCandidate, startPositionMs = startPos)
        }
    }

    fun selectCandidate(candidate: PlaybackCandidate) {
        val currentState = _uiState.value
        val resumePos = if (currentState.isSeekable) player.currentPosition.coerceAtLeast(0L) else 0L
        _uiState.update {
            it.copy(
                activeCandidate = candidate,
                errorCode = null,
                errorMessage = null
            )
        }
        prepareCandidate(candidate, startPositionMs = resumePos)
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            _uiState.update { it.copy(isEnded = false) }
            player.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        _uiState.update { it.copy(currentPositionMs = target, isEnded = false) }
    }

    fun seekTo(positionMs: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, duration)
        player.seekTo(target)
        _uiState.update { it.copy(currentPositionMs = target, isEnded = false) }
    }

    fun selectAudioTrack(option: PlayerTrackOption) {
        val groups = player.currentTracks.groups
        val group = groups.getOrNull(option.groupIndex) ?: return
        val params = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
        player.trackSelectionParameters = params
    }

    fun selectSubtitleTrack(option: PlayerTrackOption?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
    }

    fun setQueueMetadata(
        hasPrev: Boolean,
        hasNext: Boolean,
        nextTitle: String?,
        upNextPreloadLabel: String? = null
    ) {
        _uiState.update {
            it.copy(
                hasPreviousInQueue = hasPrev,
                hasNextInQueue = hasNext,
                nextItemTitle = nextTitle,
                upNextPreloadLabel = upNextPreloadLabel
            )
        }
    }

    fun stop() {
        cancelPreload()
        persistProgressIfAllowed(force = true)
        player.stop()
        player.clearMediaItems()
        currentResolution = null
        onResolveExpiredCallback = null
        onSaveProgressCallback = null
        onQuarterProgressReachedCallback = null
        quarterProgressFired = false
        _uiState.update { it.copy(isEnded = false, isPlaying = false, isBuffering = false) }
    }

    fun release() {
        stop()
        preloader.release()
        positionTickerJob?.cancel()
        player.removeListener(playerListener)
        player.release()
        scope.cancel()
    }

    fun configureMediaCacheLimit(limitMb: Int) {
        val clamped = limitMb.coerceIn(256, 2048)
        configuredLimitMb = clamped
        synchronized(HarborPlayerController) {
            val cacheDir = appContext.noBackupFilesDir.resolve("harbor_media_cache").apply {
                if (!exists()) mkdirs()
            }
            val newEffective = computeEffectiveCacheMaxBytes(cacheDir, clamped)
            if (sharedMediaCache != null && newEffective != activeMaxCacheBytes &&
                player.playbackState == Player.STATE_IDLE &&
                preloader.status.value.state != PreloadState.PRELOADING
            ) {
                runCatching { sharedMediaCache?.release() }
                sharedMediaCache = null
                createMediaCacheLocked(appContext, clamped)
            } else {
                activeMaxCacheBytes = newEffective
            }
        }
    }

    fun getCacheStats(): MediaCacheStats {
        val cacheDir = appContext.noBackupFilesDir.resolve("harbor_media_cache").apply {
            if (!exists()) mkdirs()
        }
        val cache = sharedMediaCache ?: getOrCreateMediaCache(appContext)
        val used = runCatching { cache.cacheSpace }.getOrElse { calculateDirSize(cacheDir) }
        val available = runCatching { StatFs(cacheDir.absolutePath).availableBytes }.getOrDefault(0L)
        return MediaCacheStats(
            usedBytes = used,
            maxBytes = activeMaxCacheBytes,
            availableDiskBytes = available
        )
    }

    fun preloadCandidate(
        sourceId: String,
        sourceItemId: String,
        candidate: PlaybackCandidate,
        refererUrl: String,
        allowLan: Boolean,
        maxBytes: Long,
        onFinished: (() -> Unit)? = null
    ) {
        preloader.preload(
            sourceId = sourceId,
            sourceItemId = sourceItemId,
            candidate = candidate,
            refererUrl = refererUrl,
            allowLan = allowLan,
            maxBytes = maxBytes,
            onFinished = onFinished
        )
    }

    fun cancelPreload() {
        preloader.cancelPreload()
    }

    fun clearDiskMediaCache() {
        cancelPreload()
        runCatching {
            val cache = sharedMediaCache ?: getOrCreateMediaCache(appContext)
            for (key in cache.keys.toList()) {
                cache.removeResource(key)
            }
        }
    }

    private fun prepareCandidate(candidate: PlaybackCandidate, startPositionMs: Long) {
        _uiState.update { it.copy(isEnded = false) }
        val httpFactory = HarborOkHttpDataSourceFactory(
            baseClient = baseOkHttpClient,
            customHeaders = candidate.headers,
            refererUrl = currentResolution?.sourcePageUrl.orEmpty(),
            allowLan = currentAllowLan
        )
        val dataSourceFactory: DataSource.Factory = if (_uiState.value.isPrivateSession) {
            httpFactory
        } else {
            CacheDataSource.Factory()
                .setCache(getOrCreateMediaCache(appContext))
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(candidate.url)

        val subtitleConfigs = candidate.subtitleUrls.mapIndexed { idx, subUrl ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                .setMimeType(guessSubtitleMime(subUrl))
                .setLabel("Subtitle ${idx + 1}")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }
        if (subtitleConfigs.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
        }

        val mediaItem = mediaItemBuilder.build()
        val mediaSource: MediaSource = when (candidate.streamType) {
            PlaybackStreamType.HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            PlaybackStreamType.DASH -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            PlaybackStreamType.PROGRESSIVE -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        // Subtitle configs require DefaultMediaSourceFactory if sideloaded alongside HLS/DASH;
        // if sideloaded subtitles are present, wrap via DefaultMediaSourceFactory so Media3 merges them.
        val finalSource = if (subtitleConfigs.isNotEmpty()) {
            DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            mediaSource
        }

        player.setMediaSource(finalSource)
        player.prepare()
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.playWhenReady = true
    }

    private fun handlePlayerError(error: PlaybackException) {
        val state = _uiState.value
        val previousResolution = currentResolution
        val resolverCallback = onResolveExpiredCallback
        val isLikelyExpired = isLikelyExpiredUrlError(error)

        if (isLikelyExpired && !state.expiryRetryAttempted && previousResolution != null && resolverCallback != null) {
            val savedPosition = if (player.isCurrentMediaItemSeekable) {
                player.currentPosition.coerceAtLeast(0L)
            } else {
                0L
            }
            _uiState.update {
                it.copy(
                    expiryRetryAttempted = true,
                    isRecoveringExpiry = true,
                    errorCode = null,
                    errorMessage = null
                )
            }
            scope.launch {
                try {
                    val refreshed = resolverCallback(previousResolution, 1)
                    if (refreshed.sourceId != previousResolution.sourceId ||
                        refreshed.sourceItemId != previousResolution.sourceItemId
                    ) {
                        throw HarborException(
                            HarborErrorCode.POLICY_BLOCKED,
                            "Expired URL recovery refused because item identity changed"
                        )
                    }
                    val nextCandidate = refreshed.selectedCandidate ?: refreshed.candidates.firstOrNull()
                        ?: throw HarborException(
                            HarborErrorCode.PLAYBACK_NOT_FOUND,
                            "Expired URL re-resolution returned no valid stream candidates"
                        )
                    currentResolution = refreshed
                    _uiState.update {
                        it.copy(
                            isRecoveringExpiry = false,
                            candidates = refreshed.candidates,
                            activeCandidate = nextCandidate,
                            errorCode = null,
                            errorMessage = null
                        )
                    }
                    prepareCandidate(nextCandidate, startPositionMs = savedPosition)
                } catch (e: HarborException) {
                    _uiState.update {
                        it.copy(
                            isRecoveringExpiry = false,
                            errorCode = e.code,
                            errorMessage = CredentialRedactor.redact(e.message)
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isRecoveringExpiry = false,
                            errorCode = HarborErrorCode.STREAM_EXPIRED,
                            errorMessage = CredentialRedactor.redact(
                                e.message ?: "Stream URL expired and could not be refreshed"
                            )
                        )
                    }
                }
            }
            return
        }

        val mappedCode = mapPlaybackExceptionToErrorCode(error)
        _uiState.update {
            it.copy(
                isBuffering = false,
                isRecoveringExpiry = false,
                errorCode = mappedCode,
                errorMessage = CredentialRedactor.redact(
                    error.localizedMessage ?: "Playback failed (${mappedCode.name})"
                )
            )
        }
    }

    private fun isLikelyExpiredUrlError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                if (cause.responseCode in setOf(401, 403, 410)) return true
            }
            cause = cause.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    }

    private fun mapPlaybackExceptionToErrorCode(error: PlaybackException): HarborErrorCode {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    401, 403, 410 -> HarborErrorCode.STREAM_EXPIRED
                    429 -> HarborErrorCode.RATE_LIMITED
                    else -> HarborErrorCode.NETWORK_UNAVAILABLE
                }
            }
            cause = cause.cause
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> HarborErrorCode.CODEC_UNSUPPORTED
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> HarborErrorCode.FORMAT_UNSUPPORTED
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> HarborErrorCode.NETWORK_UNAVAILABLE
            else -> HarborErrorCode.PLAYBACK_NOT_FOUND
        }
    }

    private fun updateTracksFromPlayer(tracks: Tracks) {
        val audio = mutableListOf<PlayerTrackOption>()
        val subtitles = mutableListOf<PlayerTrackOption>()
        val textDisabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        var selectedVideoWidth = 0
        var selectedVideoHeight = 0
        var selectedFrameRate = 0f
        var selectedCodec: String? = null

        tracks.groups.forEachIndexed { groupIdx, group ->
            for (trackIdx in 0 until group.length) {
                if (!group.isTrackSupported(trackIdx)) continue
                val format = group.getTrackFormat(trackIdx)
                if (group.type == C.TRACK_TYPE_VIDEO && group.isTrackSelected(trackIdx)) {
                    if (format.width > 0) selectedVideoWidth = format.width
                    if (format.height > 0) selectedVideoHeight = format.height
                    if (format.frameRate > 0f) selectedFrameRate = format.frameRate
                    selectedCodec = format.codecs ?: format.sampleMimeType
                }
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeIf { it.isNotBlank() }
                    ?: "Track ${trackIdx + 1}"
                val option = PlayerTrackOption(
                    groupIndex = groupIdx,
                    trackIndex = trackIdx,
                    label = label,
                    language = format.language,
                    isSelected = group.isTrackSelected(trackIdx)
                )
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio.add(option)
                    C.TRACK_TYPE_TEXT -> subtitles.add(option)
                }
            }
        }

        _uiState.update { current ->
            current.copy(
                audioTracks = audio,
                subtitleTracks = subtitles,
                subtitlesDisabled = textDisabled,
                decodedWidth = if (selectedVideoWidth > 0) selectedVideoWidth else current.decodedWidth,
                decodedHeight = if (selectedVideoHeight > 0) selectedVideoHeight else current.decodedHeight,
                decodedFrameRate = if (selectedFrameRate > 0f) selectedFrameRate else current.decodedFrameRate,
                videoCodec = selectedCodec ?: current.videoCodec
            )
        }
    }

    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = scope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L } ?: 0L
                    _uiState.update {
                        it.copy(
                            currentPositionMs = pos,
                            durationMs = dur,
                            isSeekable = player.isCurrentMediaItemSeekable,
                            isLive = player.isCurrentMediaItemLive
                        )
                    }
                    if (!quarterProgressFired &&
                        player.isPlaying &&
                        !_uiState.value.isBuffering &&
                        dur > 0L &&
                        pos >= minOf(dur / 4L, 30_000L)
                    ) {
                        quarterProgressFired = true
                        onQuarterProgressReachedCallback?.invoke()
                    }
                    persistProgressIfAllowed(force = false)
                }
                delay(1000L)
            }
        }
    }

    private fun persistProgressIfAllowed(force: Boolean) {
        val state = _uiState.value
        if (state.isPrivateSession || state.isLive) return
        val callback = onSaveProgressCallback ?: return
        val pos = player.currentPosition.coerceAtLeast(0L)
        if (pos <= 0L) return
        if (force || kotlin.math.abs(pos - lastSavedProgressMs) >= 5_000L) {
            lastSavedProgressMs = pos
            callback(pos)
        }
    }

    private fun guessSubtitleMime(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            clean.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            clean.endsWith(".ttml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.TEXT_VTT
        }
    }
}

@OptIn(UnstableApi::class)
internal class HarborOkHttpDataSourceFactory(
    private val baseClient: OkHttpClient,
    private val customHeaders: Map<String, String>,
    private val refererUrl: String,
    private val allowLan: Boolean
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val safeDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved = Dns.SYSTEM.lookup(hostname)
                if (!AddressSafetyValidator.isSafePublicAddress(hostname, resolved, allowLan)) {
                    throw IOException("Media destination '$hostname' blocked by address safety policy")
                }
                return resolved
            }
        }
        val client = baseClient.newBuilder()
            .dns(safeDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        return HarborOkHttpDataSource(client, customHeaders, refererUrl)
    }
}

@OptIn(UnstableApi::class)
private class HarborOkHttpDataSource(
    private val client: OkHttpClient,
    private val defaultHeaders: Map<String, String>,
    private val refererUrl: String
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var responseStream: InputStream? = null
    private var bytesToRead: Long = C.LENGTH_UNSET.toLong()
    private var bytesRead: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val headersBuilder = Headers.Builder()
        defaultHeaders.forEach { (k, v) -> headersBuilder[k] = v }
        dataSpec.httpRequestHeaders.forEach { (k, v) -> headersBuilder[k] = v }

        val hasUserAgent = defaultHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) } ||
            dataSpec.httpRequestHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }
        if (!hasUserAgent) {
            headersBuilder["User-Agent"] = DEFAULT_USER_AGENT
        }

        val hasReferer = defaultHeaders.keys.any { it.equals("Referer", ignoreCase = true) } ||
            dataSpec.httpRequestHeaders.keys.any { it.equals("Referer", ignoreCase = true) }
        if (!hasReferer && refererUrl.isNotBlank()) {
            headersBuilder["Referer"] = refererUrl
        }

        val start = dataSpec.position
        val length = dataSpec.length
        if (start != 0L || length != C.LENGTH_UNSET.toLong()) {
            val rangeEnd = if (length != C.LENGTH_UNSET.toLong()) (start + length - 1).toString() else ""
            headersBuilder["Range"] = "bytes=$start-$rangeEnd"
        }

        val request = Request.Builder()
            .url(dataSpec.uri.toString())
            .headers(headersBuilder.build())
            .get()
            .build()

        val execResponse = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        response = execResponse
        val code = execResponse.code
        if (!execResponse.isSuccessful) {
            val headerMap = execResponse.headers.toMultimap()
            val errorBody = execResponse.body?.bytes() ?: ByteArray(0)
            closeConnectionQuietly()
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                execResponse.message,
                null,
                headerMap,
                dataSpec,
                errorBody
            )
        }

        val body = execResponse.body ?: run {
            closeConnectionQuietly()
            throw HttpDataSource.HttpDataSourceException(
                "Empty HTTP media response body",
                dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }

        responseStream = body.byteStream()
        val contentLength = body.contentLength()
        bytesToRead = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() && contentLength >= 0L ->
                minOf(dataSpec.length, contentLength)
            dataSpec.length != C.LENGTH_UNSET.toLong() ->
                dataSpec.length
            contentLength >= 0L ->
                contentLength
            else ->
                C.LENGTH_UNSET.toLong()
        }
        bytesRead = 0L
        opened = true
        transferStarted(dataSpec)
        return if (contentLength >= 0L) bytesToRead else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val stream = responseStream ?: return C.RESULT_END_OF_INPUT
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0L) return C.RESULT_END_OF_INPUT
            val bounded = minOf(length.toLong(), remaining).toInt()
            val read = readInternal(stream, buffer, offset, bounded)
            if (read == -1) {
                return C.RESULT_END_OF_INPUT
            }
            bytesRead += read
            bytesTransferred(read)
            return read
        }
        val read = readInternal(stream, buffer, offset, length)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    private fun readInternal(stream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        return try {
            stream.read(buffer, offset, length)
        } catch (e: IOException) {
            val spec = dataSpec ?: DataSpec(Uri.EMPTY)
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                spec,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        }
    }

    override fun getUri(): Uri? = response?.request?.url?.toString()?.let { Uri.parse(it) } ?: dataSpec?.uri

    override fun close() {
        try {
            closeConnectionQuietly()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
            dataSpec = null
        }
    }

    private fun closeConnectionQuietly() {
        runCatching { responseStream?.close() }
        runCatching { response?.close() }
        responseStream = null
        response = null
    }
}
