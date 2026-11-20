package com.harbor.tv.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import com.harbor.tv.core.model.PlaybackCandidate
import com.harbor.tv.core.model.PlaybackStreamType
import com.harbor.tv.core.security.CredentialRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets

enum class PreloadState {
    IDLE,
    PRELOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class PreloadStatus(
    val sourceId: String = "",
    val sourceItemId: String = "",
    val candidateUrl: String? = null,
    val bytesCached: Long = 0L,
    val targetBytes: Long = 0L,
    val state: PreloadState = PreloadState.IDLE,
    val message: String? = null
)

@OptIn(UnstableApi::class)
class HarborMediaPreloader(
    private val cacheProvider: () -> SimpleCache,
    private val baseOkHttpClient: OkHttpClient
) {
    companion object {
        private const val BUFFER_SIZE_BYTES = 64 * 1024
        private const val MAX_PLAYLIST_BYTES = 2L * 1024L * 1024L
        private const val MAX_DASH_MANIFEST_BYTES = 4L * 1024L * 1024L
        private const val MAX_HLS_HEIGHT = 1080
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    @Volatile
    private var activeCacheWriter: CacheWriter? = null

    private val _status = MutableStateFlow(PreloadStatus())
    val status: StateFlow<PreloadStatus> = _status.asStateFlow()

    fun preload(
        sourceId: String,
        sourceItemId: String,
        candidate: PlaybackCandidate,
        refererUrl: String,
        allowLan: Boolean,
        maxBytes: Long,
        onFinished: (() -> Unit)? = null
    ) {
        if (maxBytes <= 0L) return
        activeCacheWriter?.cancel()
        activeJob?.cancel()

        _status.value = PreloadStatus(
            sourceId = sourceId,
            sourceItemId = sourceItemId,
            candidateUrl = candidate.url,
            bytesCached = 0L,
            targetBytes = maxBytes,
            state = PreloadState.PRELOADING,
            message = "Preloading..."
        )

        activeJob = scope.launch {
            try {
                val upstreamFactory = HarborOkHttpDataSourceFactory(
                    baseClient = baseOkHttpClient,
                    customHeaders = candidate.headers,
                    refererUrl = refererUrl,
                    allowLan = allowLan
                )
                val cache = cacheProvider()
                val cacheDataSourceFactory = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

                val cachedTotal = when (candidate.streamType) {
                    PlaybackStreamType.PROGRESSIVE -> {
                        cacheByteRange(
                            factory = cacheDataSourceFactory,
                            url = candidate.url,
                            maxBytes = maxBytes,
                            baseOffsetBytes = 0L,
                            totalTargetBytes = maxBytes,
                            sourceId = sourceId,
                            sourceItemId = sourceItemId
                        )
                    }

                    PlaybackStreamType.HLS -> {
                        preloadHls(
                            factory = cacheDataSourceFactory,
                            masterUrl = candidate.url,
                            maxBytes = maxBytes,
                            sourceId = sourceId,
                            sourceItemId = sourceItemId
                        )
                    }

                    PlaybackStreamType.DASH -> {
                        cacheByteRange(
                            factory = cacheDataSourceFactory,
                            url = candidate.url,
                            maxBytes = minOf(maxBytes, MAX_DASH_MANIFEST_BYTES),
                            baseOffsetBytes = 0L,
                            totalTargetBytes = maxBytes,
                            sourceId = sourceId,
                            sourceItemId = sourceItemId
                        )
                    }
                }

                currentCoroutineContext().ensureActive()
                _status.value = PreloadStatus(
                    sourceId = sourceId,
                    sourceItemId = sourceItemId,
                    candidateUrl = candidate.url,
                    bytesCached = cachedTotal,
                    targetBytes = maxBytes,
                    state = PreloadState.COMPLETED,
                    message = "Cached ${formatBytes(cachedTotal)} to ROM"
                )
            } catch (ce: CancellationException) {
                if (_status.value.sourceId == sourceId &&
                    _status.value.sourceItemId == sourceItemId &&
                    _status.value.state == PreloadState.PRELOADING
                ) {
                    _status.value = _status.value.copy(
                        state = PreloadState.CANCELLED,
                        message = "Preload cancelled"
                    )
                }
                throw ce
            } catch (e: Exception) {
                _status.value = PreloadStatus(
                    sourceId = sourceId,
                    sourceItemId = sourceItemId,
                    candidateUrl = candidate.url,
                    bytesCached = _status.value.bytesCached,
                    targetBytes = maxBytes,
                    state = PreloadState.FAILED,
                    message = CredentialRedactor.redact(e.message ?: "Preload failed")
                )
            } finally {
                activeCacheWriter = null
                onFinished?.invoke()
            }
        }
    }

    fun cancelPreload() {
        activeCacheWriter?.cancel()
        activeJob?.cancel()
        activeJob = null
        val cur = _status.value
        if (cur.state == PreloadState.PRELOADING) {
            _status.value = cur.copy(
                state = PreloadState.CANCELLED,
                message = "Preload cancelled"
            )
        }
    }

    fun release() {
        cancelPreload()
        scope.cancel()
    }

    private suspend fun preloadHls(
        factory: CacheDataSource.Factory,
        masterUrl: String,
        maxBytes: Long,
        sourceId: String,
        sourceItemId: String
    ): Long {
        var totalCached = 0L
        val masterText = cacheAndReadText(factory, masterUrl, MAX_PLAYLIST_BYTES)
        totalCached += masterText.toByteArray(StandardCharsets.UTF_8).size.toLong()
        updateProgress(sourceId, sourceItemId, masterUrl, totalCached, maxBytes)

        currentCoroutineContext().ensureActive()
        val variantUrl = selectHlsVariantUrl(masterUrl, masterText)
        val mediaPlaylistUrl: String
        val mediaPlaylistText: String

        if (variantUrl != null && variantUrl != masterUrl) {
            mediaPlaylistUrl = variantUrl
            mediaPlaylistText = cacheAndReadText(factory, variantUrl, MAX_PLAYLIST_BYTES)
            totalCached += mediaPlaylistText.toByteArray(StandardCharsets.UTF_8).size.toLong()
            updateProgress(sourceId, sourceItemId, masterUrl, totalCached, maxBytes)
        } else {
            mediaPlaylistUrl = masterUrl
            mediaPlaylistText = masterText
        }

        // Cache optional #EXT-X-MAP init segment first
        val mapUri = extractExtXMapUri(mediaPlaylistUrl, mediaPlaylistText)
        if (mapUri != null && totalCached < maxBytes) {
            currentCoroutineContext().ensureActive()
            val added = cacheByteRange(
                factory = factory,
                url = mapUri,
                maxBytes = null,
                baseOffsetBytes = totalCached,
                totalTargetBytes = maxBytes,
                sourceId = sourceId,
                sourceItemId = sourceItemId
            )
            totalCached += added
        }

        // Cache initial HLS media segments sequentially until maxBytes reached
        val segmentUrls = extractHlsSegmentUrls(mediaPlaylistUrl, mediaPlaylistText)
        for (segmentUrl in segmentUrls) {
            currentCoroutineContext().ensureActive()
            if (totalCached >= maxBytes) break
            val added = cacheByteRange(
                factory = factory,
                url = segmentUrl,
                maxBytes = null,
                baseOffsetBytes = totalCached,
                totalTargetBytes = maxBytes,
                sourceId = sourceId,
                sourceItemId = sourceItemId
            )
            totalCached += added
            if (added == 0L) break
        }
        return totalCached
    }

    private suspend fun cacheByteRange(
        factory: CacheDataSource.Factory,
        url: String,
        maxBytes: Long?,
        baseOffsetBytes: Long,
        totalTargetBytes: Long,
        sourceId: String,
        sourceItemId: String
    ): Long {
        currentCoroutineContext().ensureActive()
        val dataSource = factory.createDataSourceForDownloading()
        val specBuilder = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setPosition(0L)
        if (maxBytes != null && maxBytes > 0L) {
            specBuilder.setLength(maxBytes)
        }
        val dataSpec = specBuilder.build()
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        var writtenForSpec = 0L

        val writer = CacheWriter(
            dataSource,
            dataSpec,
            buffer
        ) { _, bytesCached, _ ->
            writtenForSpec = bytesCached
            updateProgress(
                sourceId = sourceId,
                sourceItemId = sourceItemId,
                candidateUrl = _status.value.candidateUrl ?: url,
                bytesCached = (baseOffsetBytes + bytesCached).coerceAtMost(totalTargetBytes),
                targetBytes = totalTargetBytes
            )
        }
        activeCacheWriter = writer
        return try {
            writer.cache()
            writtenForSpec
        } finally {
            if (activeCacheWriter === writer) {
                activeCacheWriter = null
            }
        }
    }

    private suspend fun cacheAndReadText(
        factory: CacheDataSource.Factory,
        url: String,
        maxBytes: Long
    ): String {
        currentCoroutineContext().ensureActive()
        val dataSource = factory.createDataSourceForDownloading()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setPosition(0L)
            .build()
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        val writer = CacheWriter(dataSource, dataSpec, buffer, null)
        activeCacheWriter = writer
        try {
            writer.cache()
        } finally {
            if (activeCacheWriter === writer) {
                activeCacheWriter = null
            }
        }

        // Read back from CacheDataSource (now served directly from SimpleCache)
        val readSource = factory.createDataSource()
        val out = ByteArrayOutputStream()
        return try {
            readSource.open(DataSpec(Uri.parse(url)))
            val readBuf = ByteArray(16 * 1024)
            while (out.size() < maxBytes) {
                val n = readSource.read(readBuf, 0, readBuf.size)
                if (n <= 0) break
                out.write(readBuf, 0, n)
            }
            out.toString(StandardCharsets.UTF_8.name())
        } finally {
            runCatching { readSource.close() }
        }
    }

    private fun selectHlsVariantUrl(masterUrl: String, playlistText: String): String? {
        val lines = playlistText.lines().map { it.trim() }
        if (lines.none { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            return null
        }

        data class Variant(val url: String, val height: Int, val bandwidth: Int)
        val variants = mutableListOf<Variant>()

        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) continue
            var uriLine: String? = null
            for (j in (i + 1) until lines.size) {
                val next = lines[j]
                if (next.isEmpty()) continue
                if (next.startsWith("#")) continue
                uriLine = next
                break
            }
            if (uriLine == null) continue
            val resolved = resolveUrl(masterUrl, uriLine) ?: continue
            val height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val bandwidth = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            variants.add(Variant(resolved, height, bandwidth))
        }

        if (variants.isEmpty()) return null
        val capped = variants.filter { it.height in 1..MAX_HLS_HEIGHT }
        return when {
            capped.isNotEmpty() -> capped.maxWithOrNull(compareBy<Variant>({ it.height }, { it.bandwidth }))?.url
            variants.any { it.height > MAX_HLS_HEIGHT } -> variants.minByOrNull { it.height }?.url
            else -> variants.maxByOrNull { it.bandwidth }?.url
        }
    }

    private fun extractExtXMapUri(baseUrl: String, playlistText: String): String? {
        val match = Regex("""#EXT-X-MAP:[^\r\n]*URI="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(playlistText) ?: return null
        val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
        return if (raw.isEmpty()) null else resolveUrl(baseUrl, raw)
    }

    private fun extractHlsSegmentUrls(baseUrl: String, playlistText: String): List<String> {
        val urls = mutableListOf<String>()
        for (rawLine in playlistText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            resolveUrl(baseUrl, line)?.let { urls.add(it) }
        }
        return urls
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String? {
        return runCatching {
            URI(baseUrl).resolve(relativeOrAbsolute).toString()
        }.getOrNull()
    }

    private fun updateProgress(
        sourceId: String,
        sourceItemId: String,
        candidateUrl: String?,
        bytesCached: Long,
        targetBytes: Long
    ) {
        val cur = _status.value
        if (cur.state == PreloadState.PRELOADING &&
            cur.sourceId == sourceId &&
            cur.sourceItemId == sourceItemId
        ) {
            _status.value = cur.copy(
                candidateUrl = candidateUrl,
                bytesCached = bytesCached,
                targetBytes = targetBytes
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format("%.1f MB", mb) else "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}
