package com.harbor.tv.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.harbor.tv.core.model.PreferenceConfig
import com.harbor.tv.core.model.TagPreferenceState
import com.harbor.tv.core.model.UnknownMetadataMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val Context.harborSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "harbor_settings"
)

/**
 * DataStore-backed local settings store managing the active profile ID, onboarding completion
 * state, private session toggle, and serialized per-profile [PreferenceConfig].
 */
class HarborSettingsStore(
    private val dataStore: DataStore<Preferences>
) {

    constructor(context: Context) : this(context.applicationContext.harborSettingsDataStore)

    companion object {
        const val DEFAULT_PROFILE_ID = "default"
        const val DEFAULT_MEDIA_CACHE_LIMIT_MB = 1536
        const val MIN_MEDIA_CACHE_LIMIT_MB = 256
        const val MAX_MEDIA_CACHE_LIMIT_MB = 2048

        private val KEY_ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_PRIVATE_SESSION = booleanPreferencesKey("private_session_enabled")
        private val KEY_VIDEO_PRELOAD_ENABLED = booleanPreferencesKey("video_preload_enabled")
        private val KEY_MEDIA_CACHE_LIMIT_MB = intPreferencesKey("media_cache_limit_mb")

        private fun preferenceConfigKey(profileId: String): Preferences.Key<String> {
            return stringPreferencesKey("profile_preferences_${profileId.trim()}")
        }
    }

    val activeProfileIdFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_PROFILE_ID]?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE_ID
    }

    val onboardingCompletedFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val privateSessionFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PRIVATE_SESSION] ?: false
    }

    val videoPreloadEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_VIDEO_PRELOAD_ENABLED] ?: true
    }

    val mediaCacheLimitMbFlow: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[KEY_MEDIA_CACHE_LIMIT_MB] ?: DEFAULT_MEDIA_CACHE_LIMIT_MB)
            .coerceIn(MIN_MEDIA_CACHE_LIMIT_MB, MAX_MEDIA_CACHE_LIMIT_MB)
    }

    suspend fun getActiveProfileId(): String = activeProfileIdFlow.first()

    suspend fun setActiveProfileId(profileId: String) {
        val sanitized = profileId.trim().ifEmpty { DEFAULT_PROFILE_ID }
        dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PROFILE_ID] = sanitized
        }
    }

    suspend fun isOnboardingCompleted(): Boolean = onboardingCompletedFlow.first()

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun isPrivateSession(): Boolean = privateSessionFlow.first()

    suspend fun setPrivateSession(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_PRIVATE_SESSION] = enabled
        }
    }

    suspend fun isVideoPreloadEnabled(): Boolean = videoPreloadEnabledFlow.first()

    suspend fun setVideoPreloadEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_VIDEO_PRELOAD_ENABLED] = enabled
        }
    }

    suspend fun getMediaCacheLimitMb(): Int = mediaCacheLimitMbFlow.first()

    suspend fun setMediaCacheLimitMb(limitMb: Int) {
        val clamped = limitMb.coerceIn(MIN_MEDIA_CACHE_LIMIT_MB, MAX_MEDIA_CACHE_LIMIT_MB)
        dataStore.edit { prefs ->
            prefs[KEY_MEDIA_CACHE_LIMIT_MB] = clamped
        }
    }

    fun observePreferences(profileId: String): Flow<PreferenceConfig> {
        val key = preferenceConfigKey(profileId)
        return dataStore.data.map { prefs ->
            val raw = prefs[key]
            if (raw.isNullOrBlank()) {
                PreferenceConfig(profileId = profileId)
            } else {
                PreferenceConfigJsonCodec.decode(raw, fallbackProfileId = profileId)
            }
        }
    }

    suspend fun getPreferences(profileId: String): PreferenceConfig {
        return observePreferences(profileId).first()
    }

    suspend fun savePreferences(config: PreferenceConfig) {
        val key = preferenceConfigKey(config.profileId)
        val serialized = PreferenceConfigJsonCodec.encode(config)
        dataStore.edit { prefs ->
            prefs[key] = serialized
        }
    }

    suspend fun deletePreferences(profileId: String) {
        val key = preferenceConfigKey(profileId)
        dataStore.edit { prefs ->
            prefs.remove(key)
        }
    }
}

object PreferenceConfigJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encode(config: PreferenceConfig): String {
        val obj = buildJsonObject {
            put("profileId", config.profileId)
            put("tagPreferences", buildJsonObject {
                config.tagPreferences.forEach { (tag, state) ->
                    put(tag, state.name)
                }
            })
            put("excludedSources", buildJsonArray {
                config.excludedSources.forEach { add(JsonPrimitive(it)) }
            })
            put("allowedLanguages", buildJsonArray {
                config.allowedLanguages.forEach { add(JsonPrimitive(it)) }
            })
            if (config.minDurationSeconds != null) {
                put("minDurationSeconds", config.minDurationSeconds)
            }
            if (config.maxDurationSeconds != null) {
                put("maxDurationSeconds", config.maxDurationSeconds)
            }
            put("includeLive", config.includeLive)
            put("includeOnDemand", config.includeOnDemand)
            put("unknownMetadataMode", config.unknownMetadataMode.name)
            put("requiredCanonicalTagPrefixes", buildJsonArray {
                config.requiredCanonicalTagPrefixes.forEach { add(JsonPrimitive(it)) }
            })
        }
        return obj.toString()
    }

    fun decode(rawJson: String, fallbackProfileId: String = HarborSettingsStore.DEFAULT_PROFILE_ID): PreferenceConfig {
        return runCatching {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val profileId = obj["profileId"]?.jsonPrimitive?.contentOrNull ?: fallbackProfileId

            val tagPrefs = linkedMapOf<String, TagPreferenceState>()
            val tagPrefsObj = obj["tagPreferences"] as? JsonObject
            tagPrefsObj?.forEach { (k, v) ->
                val stateName = v.jsonPrimitive.contentOrNull ?: return@forEach
                val state = runCatching { TagPreferenceState.valueOf(stateName) }.getOrNull()
                    ?: return@forEach
                tagPrefs[k] = state
            }

            val mode = obj["unknownMetadataMode"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { UnknownMetadataMode.valueOf(it) }.getOrNull() }
                ?: UnknownMetadataMode.STANDARD

            PreferenceConfig(
                profileId = profileId,
                tagPreferences = tagPrefs,
                excludedSources = decodeStringSet(obj["excludedSources"] as? JsonArray),
                allowedLanguages = decodeStringSet(obj["allowedLanguages"] as? JsonArray),
                minDurationSeconds = obj["minDurationSeconds"]?.jsonPrimitive?.intOrNull,
                maxDurationSeconds = obj["maxDurationSeconds"]?.jsonPrimitive?.intOrNull,
                includeLive = obj["includeLive"]?.jsonPrimitive?.booleanOrNull ?: true,
                includeOnDemand = obj["includeOnDemand"]?.jsonPrimitive?.booleanOrNull ?: true,
                unknownMetadataMode = mode,
                requiredCanonicalTagPrefixes = decodeStringSet(obj["requiredCanonicalTagPrefixes"] as? JsonArray)
            )
        }.getOrElse {
            PreferenceConfig(profileId = fallbackProfileId)
        }
    }

    private fun decodeStringSet(array: JsonArray?): Set<String> {
        return array?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
    }
}
