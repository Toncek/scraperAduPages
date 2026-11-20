package com.harbor.tv.core.model

enum class DefinitionLifecycleState {
    IMPORTED,
    VALIDATED,
    AWAITING_APPROVAL,
    ENABLED,
    DISABLED,
    INCOMPATIBLE,
    BROKEN,
    PENDING_UPDATE
}

enum class HostPurpose {
    CATALOG,
    IMAGES,
    MEDIA,
    AUTHENTICATION
}

enum class LiveStatus {
    ON_DEMAND,
    LIVE,
    UPCOMING
}

enum class MetadataCompleteness {
    PARTIAL,
    COMPLETE,
    INSUFFICIENT
}

enum class TagConfidence {
    EXPLICIT,
    DERIVED
}

enum class TagPreferenceState {
    PREFERRED,
    NEUTRAL,
    EXCLUDED
}

enum class UnknownMetadataMode {
    STANDARD,
    STRICT
}

enum class QualityPreference {
    HIGHEST_AVAILABLE,
    PREFERRED_RESOLUTION,
    PREFERRED_BITRATE,
    MANUAL,
    AUTO_FALLBACK
}

enum class PlaybackStreamType {
    HLS,
    DASH,
    PROGRESSIVE
}

data class TagProvenance(
    val canonicalTag: String,
    val sourceField: String,
    val originalValue: String,
    val mappingVersion: String,
    val confidence: TagConfidence
)

data class CategoryItem(
    val sourceId: String,
    val id: String,
    val title: String,
    val url: String
)

data class CatalogItem(
    val sourceId: String,
    val sourceItemId: String,
    val sourcePageUrl: String,
    val title: String,
    val description: String? = null,
    val durationSeconds: Int? = null,
    val liveStatus: LiveStatus = LiveStatus.ON_DEMAND,
    val sourceTags: List<String> = emptyList(),
    val canonicalTags: List<String> = emptyList(),
    val tagProvenance: List<TagProvenance> = emptyList(),
    val languages: List<String> = emptyList(),
    val contentRating: String? = null,
    val thumbnailReferences: List<String> = emptyList(),
    val metadataCompleteness: MetadataCompleteness = MetadataCompleteness.COMPLETE,
    val metadataProvenance: String = "listing",
    val fetchedAt: Long = System.currentTimeMillis()
) {
    val compositeId: String get() = "$sourceId::$sourceItemId"
}

data class PaginatedListingResult(
    val items: List<CatalogItem>,
    val nextCursor: String? = null,
    val totalExtracted: Int = items.size,
    val totalRejected: Int = 0
)

data class PlaybackCandidate(
    val url: String,
    val mimeType: String? = null,
    val streamType: PlaybackStreamType = PlaybackStreamType.PROGRESSIVE,
    val qualityLabel: String? = null,
    val resolutionHeight: Int? = null,
    val bitrateKbps: Int? = null,
    val subtitleUrls: List<String> = emptyList(),
    val expiresAtEpochMs: Long? = null,
    val headers: Map<String, String> = emptyMap()
)

data class PlaybackResolutionResult(
    val sourceId: String,
    val sourceItemId: String,
    val sourcePageUrl: String,
    val candidates: List<PlaybackCandidate>,
    val selectedCandidate: PlaybackCandidate?,
    val requestPolicySourceId: String
)

data class UserProfile(
    val id: String,
    val name: String,
    val pinHash: String? = null,
    val isPrivateSession: Boolean = false,
    val unknownMetadataMode: UnknownMetadataMode = UnknownMetadataMode.STANDARD,
    val qualityPreference: QualityPreference = QualityPreference.HIGHEST_AVAILABLE,
    val preferredResolutionHeight: Int = 1080,
    val preferredBitrateKbps: Int = 5000,
    val allowLanSources: Boolean = false,
    val historyEnabled: Boolean = true
)

data class PreferenceConfig(
    val profileId: String = "default",
    val tagPreferences: Map<String, TagPreferenceState> = emptyMap(),
    val excludedSources: Set<String> = emptySet(),
    val allowedLanguages: Set<String> = emptySet(),
    val minDurationSeconds: Int? = null,
    val maxDurationSeconds: Int? = null,
    val includeLive: Boolean = true,
    val includeOnDemand: Boolean = true,
    val unknownMetadataMode: UnknownMetadataMode = UnknownMetadataMode.STANDARD,
    val requiredCanonicalTagPrefixes: Set<String> = emptySet()
)

data class SourceDiagnosticsReport(
    val sourceId: String,
    val activeVersion: String,
    val lastSuccessfulRequestMs: Long? = null,
    val lastSuccessfulParseMs: Long? = null,
    val itemsExtracted: Int = 0,
    val itemsRejected: Int = 0,
    val missingRequiredFields: List<String> = emptyList(),
    val blockedDestinations: List<String> = emptyList(),
    val playbackCandidateCount: Int = 0,
    val sanitizedFailureReason: String? = null,
    val errorCode: HarborErrorCode? = null,
    val fixtureResults: List<FixtureRunResult> = emptyList(),
    val referenceRepository: String? = null,
    val pinnedCommit: String? = null,
    val normalizedSourceStatus: String? = null
)

data class FixtureRunResult(
    val fixtureId: String,
    val operation: String,
    val passed: Boolean,
    val itemsExtracted: Int,
    val itemsRejected: Int,
    val candidatesExtracted: Int,
    val missingFields: List<String>,
    val errorCode: HarborErrorCode? = null,
    val message: String
)

data class MagicPhWebsiteProfile(
    val id: String,
    val name: String,
    val operator: String? = null,
    val catalogHosts: List<String>,
    val imageHosts: List<String>,
    val mediaHosts: List<String>,
    val desktopSupported: Boolean = true,
    val mobileSupported: Boolean = true,
    val searchSupported: Boolean = true,
    val directVideoSupported: Boolean = true,
    val officialApiType: String? = null,
    val sourceStatus: String = "reference-only",
    val compatibilityNotes: String = "",
    val bundledDefinitionAsset: String? = null
)
