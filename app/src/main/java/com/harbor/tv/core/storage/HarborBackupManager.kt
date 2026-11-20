package com.harbor.tv.core.storage

import android.content.Context
import android.os.StatFs
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.PreferenceConfig
import com.harbor.tv.core.model.UserTasteProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets

data class HarborBackupPayload(
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val preferences: PreferenceConfig,
    val tasteProfile: UserTasteProfile,
    val favorites: List<CatalogItem>,
    val watchLater: List<CatalogItem>
)

data class HarborStorageBreakdown(
    val mediaCacheBytes: Long,
    val thumbCacheBytes: Long,
    val httpCacheBytes: Long,
    val availableDiskBytes: Long
)

/**
 * Local-only, non-secret JSON profile backup and restore manager.
 * Never serializes PIN hashes, session cookies, or authorization credentials.
 */
object HarborBackupManager {
    const val BACKUP_FILE_NAME = "harbor_profile_backup.json"
    const val MAX_BACKUP_BYTES = 2 * 1024 * 1024
    private const val SUPPORTED_SCHEMA_VERSION = 1
    private const val MAX_FAVORITES = 200
    private const val MAX_WATCH_LATER = 50

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encodeBackupJson(payload: HarborBackupPayload): String {
        val prefsElement = json.parseToJsonElement(
            PreferenceConfigJsonCodec.encode(payload.preferences)
        )
        val tasteElement = json.parseToJsonElement(
            UserTasteProfileJsonCodec.encode(payload.tasteProfile)
        )
        val favoritesArray = buildJsonArray {
            payload.favorites
                .distinctBy { it.compositeId }
                .take(MAX_FAVORITES)
                .forEach { item ->
                    add(json.parseToJsonElement(CatalogItemJsonCodec.encode(item)))
                }
        }
        val watchLaterArray = buildJsonArray {
            payload.watchLater
                .distinctBy { it.compositeId }
                .take(MAX_WATCH_LATER)
                .forEach { item ->
                    add(json.parseToJsonElement(CatalogItemJsonCodec.encode(item)))
                }
        }

        val root = buildJsonObject {
            put("schemaVersion", payload.schemaVersion)
            put("exportedAtEpochMs", payload.exportedAtEpochMs)
            put("preferences", prefsElement)
            put("tasteProfile", tasteElement)
            put("favorites", favoritesArray)
            put("watchLater", watchLaterArray)
        }
        return root.toString()
    }

    fun decodeBackupJson(rawJson: String, targetProfileId: String): HarborBackupPayload {
        require(rawJson.isNotBlank()) { "Backup JSON is blank" }
        require(rawJson.length <= MAX_BACKUP_BYTES) {
            "Backup JSON exceeds maximum size of $MAX_BACKUP_BYTES characters"
        }
        require(rawJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "Backup JSON exceeds maximum size of $MAX_BACKUP_BYTES bytes"
        }

        val obj = json.parseToJsonElement(rawJson).jsonObject
        val schemaVersion = obj["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("Missing backup schemaVersion")
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported backup schemaVersion: $schemaVersion"
        }

        val exportedAtEpochMs = obj["exportedAtEpochMs"]?.jsonPrimitive?.longOrNull
            ?: System.currentTimeMillis()

        val prefsObj = obj["preferences"] as? JsonObject
        val preferences = if (prefsObj != null) {
            PreferenceConfigJsonCodec.decode(
                rawJson = prefsObj.toString(),
                fallbackProfileId = targetProfileId
            ).copy(profileId = targetProfileId)
        } else {
            PreferenceConfig(profileId = targetProfileId)
        }

        val tasteObj = obj["tasteProfile"] as? JsonObject
        val tasteProfile = if (tasteObj != null) {
            UserTasteProfileJsonCodec.decode(
                rawJson = tasteObj.toString(),
                fallbackProfileId = targetProfileId
            ).copy(profileId = targetProfileId)
        } else {
            UserTasteProfile(profileId = targetProfileId)
        }

        val favorites = decodeCatalogItemArray(
            array = obj["favorites"] as? JsonArray,
            limit = MAX_FAVORITES
        )
        val watchLater = decodeCatalogItemArray(
            array = obj["watchLater"] as? JsonArray,
            limit = MAX_WATCH_LATER
        )

        return HarborBackupPayload(
            schemaVersion = schemaVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            preferences = preferences,
            tasteProfile = tasteProfile,
            favorites = favorites,
            watchLater = watchLater
        )
    }

    fun backupFile(context: Context): File =
        File(context.applicationContext.filesDir, BACKUP_FILE_NAME)

    fun hasBackup(context: Context): Boolean {
        val file = backupFile(context)
        return file.exists() && file.isFile && file.length() > 0L
    }

    fun writeBackup(context: Context, payload: HarborBackupPayload): File {
        val encoded = encodeBackupJson(payload)
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_BACKUP_BYTES) {
            "Encoded backup exceeds maximum size of $MAX_BACKUP_BYTES bytes"
        }
        val target = backupFile(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$BACKUP_FILE_NAME.tmp")
        tmp.writeText(encoded, StandardCharsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(encoded, StandardCharsets.UTF_8)
            tmp.delete()
        }
        return target
    }

    fun readBackup(context: Context, targetProfileId: String): HarborBackupPayload? {
        val file = backupFile(context)
        if (!file.exists() || !file.isFile) return null
        if (file.length() <= 0L || file.length() > MAX_BACKUP_BYTES.toLong()) return null
        return runCatching {
            val raw = file.readText(StandardCharsets.UTF_8)
            decodeBackupJson(raw, targetProfileId)
        }.getOrNull()
    }

    private fun decodeCatalogItemArray(array: JsonArray?, limit: Int): List<CatalogItem> {
        if (array == null) return emptyList()
        return array.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            CatalogItemJsonCodec.decode(obj.toString())
        }
            .distinctBy { it.compositeId }
            .take(limit)
    }
}

/**
 * Computes safe, non-throwing disk usage breakdowns for Harbor's media, thumbnail, and HTTP caches.
 */
object HarborStorageStats {
    fun computeBreakdown(context: Context): HarborStorageBreakdown {
        val appContext = context.applicationContext
        val mediaCacheBytes = computeDirectoryBytes(
            File(appContext.noBackupFilesDir, "harbor_media_cache")
        )
        val thumbCacheBytes = computeDirectoryBytes(
            File(appContext.cacheDir, "harbor_thumb_cache")
        )
        val httpCacheBytes = computeDirectoryBytes(
            File(appContext.cacheDir, "harbor_http_cache")
        )
        val availableDiskBytes = runCatching {
            StatFs(appContext.noBackupFilesDir.absolutePath).availableBytes.coerceAtLeast(0L)
        }.getOrDefault(0L)

        return HarborStorageBreakdown(
            mediaCacheBytes = mediaCacheBytes,
            thumbCacheBytes = thumbCacheBytes,
            httpCacheBytes = httpCacheBytes,
            availableDiskBytes = availableDiskBytes
        )
    }

    private fun computeDirectoryBytes(root: File): Long {
        return runCatching {
            if (!root.exists()) return@runCatching 0L
            if (root.isFile) return@runCatching root.length().coerceAtLeast(0L)
            var total = 0L
            val stack = ArrayDeque<File>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                val children = current.listFiles() ?: continue
                for (child in children) {
                    if (child.isDirectory) {
                        stack.addLast(child)
                    } else if (child.isFile) {
                        total += child.length().coerceAtLeast(0L)
                    }
                }
            }
            total
        }.getOrDefault(0L)
    }
}
