package com.harbor.tv.core.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.LiveStatus
import com.harbor.tv.core.model.MetadataCompleteness
import com.harbor.tv.core.model.QualityPreference
import com.harbor.tv.core.model.TagConfidence
import com.harbor.tv.core.model.TagProvenance
import com.harbor.tv.core.model.UnknownMetadataMode
import com.harbor.tv.core.model.UserProfile
import com.harbor.tv.core.security.CredentialRedactor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Entity(tableName = "source_records")
data class SourceRecordEntity(
    @PrimaryKey
    val sourceId: String,
    val version: String,
    val rawContent: String,
    val lifecycleState: String,
    val approvedHostsJson: String,
    val previousRawContent: String? = null,
    val previousVersion: String? = null,
    val updatedAt: Long
)

@Entity(
    tableName = "catalog_cache",
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["expiresAt"])
    ]
)
data class CatalogCacheEntity(
    @PrimaryKey
    val compositeId: String,
    val sourceId: String,
    val definitionVersion: String,
    val itemJson: String,
    val fetchedAt: Long,
    val expiresAt: Long
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["profileId", "compositeId"],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["sourceId"])
    ]
)
data class FavoriteEntity(
    val profileId: String,
    val compositeId: String,
    val sourceId: String,
    val itemJson: String,
    val addedAt: Long
)

@Entity(
    tableName = "watch_history",
    primaryKeys = ["profileId", "compositeId"],
    indices = [
        Index(value = ["profileId"]),
        Index(value = ["watchedAt"])
    ]
)
data class WatchHistoryEntity(
    val profileId: String,
    val compositeId: String,
    val sourceId: String,
    val itemJson: String,
    val resumePositionMs: Long,
    val watchedAt: Long
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val pinHash: String? = null,
    val unknownMetadataMode: String = UnknownMetadataMode.STANDARD.name,
    val qualityPreference: String = QualityPreference.HIGHEST_AVAILABLE.name,
    val allowLanSources: Boolean = false,
    val historyEnabled: Boolean = true
) {
    fun toUserProfile(
        isPrivateSession: Boolean = false,
        preferredResolutionHeight: Int = 1080,
        preferredBitrateKbps: Int = 5000
    ): UserProfile {
        val mode = runCatching { UnknownMetadataMode.valueOf(unknownMetadataMode) }
            .getOrDefault(UnknownMetadataMode.STANDARD)
        val quality = runCatching { QualityPreference.valueOf(qualityPreference) }
            .getOrDefault(QualityPreference.HIGHEST_AVAILABLE)
        return UserProfile(
            id = id,
            name = name,
            pinHash = pinHash,
            isPrivateSession = isPrivateSession,
            unknownMetadataMode = mode,
            qualityPreference = quality,
            preferredResolutionHeight = preferredResolutionHeight,
            preferredBitrateKbps = preferredBitrateKbps,
            allowLanSources = allowLanSources,
            historyEnabled = historyEnabled
        )
    }
}

fun UserProfile.toEntity(): ProfileEntity {
    return ProfileEntity(
        id = id,
        name = name,
        pinHash = pinHash,
        unknownMetadataMode = unknownMetadataMode.name,
        qualityPreference = qualityPreference.name,
        allowLanSources = allowLanSources,
        historyEnabled = historyEnabled
    )
}

@Dao
interface SourceRecordDao {
    @Query("SELECT * FROM source_records ORDER BY sourceId ASC")
    suspend fun getAll(): List<SourceRecordEntity>

    @Query("SELECT * FROM source_records ORDER BY sourceId ASC")
    fun observeAll(): Flow<List<SourceRecordEntity>>

    @Query("SELECT * FROM source_records WHERE sourceId = :sourceId LIMIT 1")
    suspend fun getById(sourceId: String): SourceRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: SourceRecordEntity)

    @Query("UPDATE source_records SET lifecycleState = :lifecycleState, updatedAt = :updatedAt WHERE sourceId = :sourceId")
    suspend fun updateLifecycleState(sourceId: String, lifecycleState: String, updatedAt: Long)

    @Query("DELETE FROM source_records WHERE sourceId = :sourceId")
    suspend fun deleteById(sourceId: String)
}

@Dao
interface CatalogCacheDao {
    @Query("SELECT * FROM catalog_cache WHERE compositeId = :compositeId AND expiresAt > :nowMs LIMIT 1")
    suspend fun getValidByCompositeId(compositeId: String, nowMs: Long): CatalogCacheEntity?

    @Query("SELECT * FROM catalog_cache WHERE sourceId = :sourceId AND definitionVersion = :definitionVersion AND expiresAt > :nowMs ORDER BY fetchedAt DESC")
    suspend fun getValidForSource(sourceId: String, definitionVersion: String, nowMs: Long): List<CatalogCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CatalogCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<CatalogCacheEntity>)

    @Query("DELETE FROM catalog_cache WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("DELETE FROM catalog_cache WHERE sourceId = :sourceId AND definitionVersion != :currentVersion")
    suspend fun deleteForSourceExceptVersion(sourceId: String, currentVersion: String)

    @Query("DELETE FROM catalog_cache WHERE expiresAt <= :nowMs")
    suspend fun deleteExpired(nowMs: Long)

    @Query("DELETE FROM catalog_cache")
    suspend fun clearAll()
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY addedAt DESC")
    suspend fun getForProfile(profileId: String): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun observeForProfile(profileId: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE profileId = :profileId AND compositeId = :compositeId)")
    suspend fun isFavorite(profileId: String, compositeId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE profileId = :profileId AND compositeId = :compositeId")
    suspend fun delete(profileId: String, compositeId: String)

    @Query("DELETE FROM favorites WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY watchedAt DESC")
    suspend fun getForProfile(profileId: String): List<WatchHistoryEntity>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId ORDER BY watchedAt DESC")
    fun observeForProfile(profileId: String): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE profileId = :profileId AND compositeId = :compositeId LIMIT 1")
    suspend fun getEntry(profileId: String, compositeId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId AND compositeId = :compositeId")
    suspend fun deleteEntry(profileId: String, compositeId: String)

    @Query("DELETE FROM watch_history WHERE profileId = :profileId")
    suspend fun clearForProfile(profileId: String)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id ASC")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles ORDER BY id ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :profileId LIMIT 1")
    suspend fun getById(profileId: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :profileId")
    suspend fun deleteById(profileId: String)
}

@Database(
    entities = [
        SourceRecordEntity::class,
        CatalogCacheEntity::class,
        FavoriteEntity::class,
        WatchHistoryEntity::class,
        ProfileEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class HarborDatabase : RoomDatabase() {
    abstract fun sourceRecordDao(): SourceRecordDao
    abstract fun catalogCacheDao(): CatalogCacheDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun profileDao(): ProfileDao

    companion object {
        private const val DATABASE_NAME = "harbor_tv.db"

        @Volatile
        private var instance: HarborDatabase? = null

        fun getInstance(context: Context): HarborDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HarborDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
        }
    }
}

/**
 * Pure-Kotlin JSON codec for persisting canonical [CatalogItem] records without storing
 * expiring playback URLs, cookies, or active authorization headers.
 */
object CatalogItemJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encode(item: CatalogItem): String {
        val obj = buildJsonObject {
            put("sourceId", item.sourceId)
            put("sourceItemId", item.sourceItemId)
            put("sourcePageUrl", CredentialRedactor.redact(item.sourcePageUrl))
            put("title", item.title)
            if (item.description != null) {
                put("description", item.description)
            }
            if (item.durationSeconds != null) {
                put("durationSeconds", item.durationSeconds)
            }
            put("liveStatus", item.liveStatus.name)
            put("sourceTags", buildJsonArray {
                item.sourceTags.forEach { add(JsonPrimitive(it)) }
            })
            put("canonicalTags", buildJsonArray {
                item.canonicalTags.forEach { add(JsonPrimitive(it)) }
            })
            put("tagProvenance", buildJsonArray {
                item.tagProvenance.forEach { prov ->
                    add(
                        buildJsonObject {
                            put("canonicalTag", prov.canonicalTag)
                            put("sourceField", prov.sourceField)
                            put("originalValue", prov.originalValue)
                            put("mappingVersion", prov.mappingVersion)
                            put("confidence", prov.confidence.name)
                        }
                    )
                }
            })
            put("languages", buildJsonArray {
                item.languages.forEach { add(JsonPrimitive(it)) }
            })
            if (item.contentRating != null) {
                put("contentRating", item.contentRating)
            }
            put("thumbnailReferences", buildJsonArray {
                item.thumbnailReferences.forEach { add(JsonPrimitive(it)) }
            })
            put("metadataCompleteness", item.metadataCompleteness.name)
            put("metadataProvenance", item.metadataProvenance)
            put("fetchedAt", item.fetchedAt)
        }
        return obj.toString()
    }

    fun decode(rawJson: String): CatalogItem? {
        return runCatching {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val sourceId = obj["sourceId"]?.jsonPrimitive?.contentOrNull ?: return null
            val sourceItemId = obj["sourceItemId"]?.jsonPrimitive?.contentOrNull ?: return null
            val sourcePageUrl = obj["sourcePageUrl"]?.jsonPrimitive?.contentOrNull ?: return null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return null

            val liveStatus = obj["liveStatus"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { LiveStatus.valueOf(it) }.getOrNull() }
                ?: LiveStatus.ON_DEMAND

            val completeness = obj["metadataCompleteness"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { MetadataCompleteness.valueOf(it) }.getOrNull() }
                ?: MetadataCompleteness.PARTIAL

            val provenance = obj["tagProvenance"]?.jsonArray?.mapNotNull { elem ->
                val p = elem as? JsonObject ?: return@mapNotNull null
                val canonicalTag = p["canonicalTag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val sourceField = p["sourceField"]?.jsonPrimitive?.contentOrNull ?: "sourceTags"
                val originalValue = p["originalValue"]?.jsonPrimitive?.contentOrNull ?: canonicalTag
                val mappingVersion = p["mappingVersion"]?.jsonPrimitive?.contentOrNull ?: "1.0"
                val confidence = p["confidence"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { TagConfidence.valueOf(it) }.getOrNull() }
                    ?: TagConfidence.DERIVED
                TagProvenance(
                    canonicalTag = canonicalTag,
                    sourceField = sourceField,
                    originalValue = originalValue,
                    mappingVersion = mappingVersion,
                    confidence = confidence
                )
            }.orEmpty()

            CatalogItem(
                sourceId = sourceId,
                sourceItemId = sourceItemId,
                sourcePageUrl = sourcePageUrl,
                title = title,
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                durationSeconds = obj["durationSeconds"]?.jsonPrimitive?.intOrNull,
                liveStatus = liveStatus,
                sourceTags = decodeStringList(obj["sourceTags"] as? JsonArray),
                canonicalTags = decodeStringList(obj["canonicalTags"] as? JsonArray),
                tagProvenance = provenance,
                languages = decodeStringList(obj["languages"] as? JsonArray),
                contentRating = obj["contentRating"]?.jsonPrimitive?.contentOrNull,
                thumbnailReferences = decodeStringList(obj["thumbnailReferences"] as? JsonArray),
                metadataCompleteness = completeness,
                metadataProvenance = obj["metadataProvenance"]?.jsonPrimitive?.contentOrNull ?: "cache",
                fetchedAt = obj["fetchedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            )
        }.getOrNull()
    }

    private fun decodeStringList(array: JsonArray?): List<String> {
        return array?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    }
}
