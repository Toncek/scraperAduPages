package com.harbor.tv.engine.definition

import com.harbor.tv.core.model.DefinitionLifecycleState
import com.harbor.tv.core.model.HarborErrorCode

data class SourceDefinition(
    val schemaVersion: String,
    val source: SourceIdentity,
    val network: NetworkConfig,
    val authentication: AuthConfig = AuthConfig(),
    val taxonomy: TaxonomyConfig? = null,
    val operations: OperationsConfig,
    val playback: PlaybackPolicyConfig? = null,
    val fixtures: List<FixtureDefinition> = emptyList(),
    val settingsReference: SettingsReferenceConfig? = null
)

data class SourceIdentity(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val language: String? = null,
    val baseUrl: String,
    val enabledByDefault: Boolean = false,
    val permission: PermissionDeclaration? = null,
    val capabilities: CapabilitiesConfig = CapabilitiesConfig()
)

data class PermissionDeclaration(
    val basis: String,
    val reference: String? = null,
    val notes: String? = null
)

data class CapabilitiesConfig(
    val categories: Boolean = false,
    val search: Boolean = false,
    val details: Boolean = false,
    val playback: Boolean = false,
    val live: Boolean = false,
    val intermediateTraversal: Boolean = false
)

data class NetworkConfig(
    val httpsOnly: Boolean = true,
    val allowLan: Boolean = false,
    val allowedHosts: AllowedHostsConfig = AllowedHostsConfig(),
    val redirects: RedirectsConfig = RedirectsConfig(),
    val timeouts: TimeoutsConfig = TimeoutsConfig(),
    val limits: LimitsConfig = LimitsConfig(),
    val headers: Map<String, String> = emptyMap()
)

data class AllowedHostsConfig(
    val catalog: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val media: List<String> = emptyList(),
    val authentication: List<String> = emptyList()
)

data class RedirectsConfig(
    val maxHops: Int = 3
)

data class TimeoutsConfig(
    val connectMs: Int = 10_000,
    val readMs: Int = 15_000,
    val operationMs: Int = 30_000
)

data class LimitsConfig(
    val maxConcurrentRequests: Int = 2,
    val minRequestIntervalMs: Long = 500L,
    val maxCatalogResponseBytes: Long = 2_097_152L,
    val maxItemsPerPage: Int = 100
)

data class AuthConfig(
    val type: String = "none",
    val profileId: String? = null,
    val scopes: List<String> = emptyList()
)

data class TaxonomyConfig(
    val namespace: String,
    val mappingVersion: String = "1.0",
    val mappings: Map<String, List<String>> = emptyMap()
)

data class OperationsConfig(
    val categories: CatalogOperationDef? = null,
    val listing: CatalogOperationDef? = null,
    val search: CatalogOperationDef? = null,
    val details: DetailsOperationDef? = null,
    val playback: PlaybackOperationDef? = null
)

data class RequestDef(
    val method: String = "GET",
    val url: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap()
)

data class ResponseDef(
    val type: String,
    val encoding: String? = null
)

data class EmbeddedJsonDef(
    val selector: String,
    val read: String = "data"
)

data class DocumentDef(
    val embeddedJson: EmbeddedJsonDef? = null
)

data class TransformDef(
    val op: String,
    val delimiter: String? = null,
    val mappings: Map<String, String> = emptyMap(),
    val template: String? = null,
    val pattern: String? = null,
    val group: Int = 1
)

data class FieldExtractionDef(
    val selector: String? = null,
    val pointer: String? = null,
    val read: String? = null,
    val attribute: String? = null,
    val cardinality: String = "one",
    val fallback: FieldExtractionDef? = null,
    val transforms: List<TransformDef> = emptyList(),
    val required: Boolean = false
)

data class ItemsExtractionDef(
    val selector: String? = null,
    val pointer: String? = null,
    val limit: Int = 100,
    val fields: Map<String, FieldExtractionDef> = emptyMap()
)

data class CatalogExtractDef(
    val items: ItemsExtractionDef
)

data class PaginationDef(
    val mode: String = "nextLink",
    val next: FieldExtractionDef? = null,
    val pageParam: String? = null,
    val cursorPointer: String? = null,
    val maxPages: Int = 20
)

data class ReuseDef(
    val extractFrom: String? = null,
    val paginationFrom: String? = null
)

data class CatalogOperationDef(
    val request: RequestDef,
    val response: ResponseDef,
    val document: DocumentDef? = null,
    val extract: CatalogExtractDef? = null,
    val pagination: PaginationDef? = null,
    val reuse: ReuseDef? = null
)

data class DetailsExtractDef(
    val fields: Map<String, FieldExtractionDef> = emptyMap()
)

data class DetailsOperationDef(
    val request: RequestDef,
    val response: ResponseDef,
    val document: DocumentDef? = null,
    val extract: DetailsExtractDef? = null,
    val reuse: ReuseDef? = null
)

data class CandidatesExtractionDef(
    val selector: String? = null,
    val pointer: String? = null,
    val limit: Int = 20,
    val fields: Map<String, FieldExtractionDef> = emptyMap()
)

data class PlaybackStepExtractDef(
    val candidates: CandidatesExtractionDef? = null,
    val fields: Map<String, FieldExtractionDef> = emptyMap()
)

data class PlaybackStepDef(
    val id: String,
    val request: RequestDef,
    val response: ResponseDef,
    val document: DocumentDef? = null,
    val extract: PlaybackStepExtractDef
)

data class PlaybackResultDef(
    val candidatesFrom: String
)

data class PlaybackOperationDef(
    val steps: List<PlaybackStepDef> = emptyList(),
    val result: PlaybackResultDef
)

data class OnExpiredConfig(
    val action: String = "resolveAgain",
    val maxAttempts: Int = 1
)

data class PlaybackPolicyConfig(
    val allowedTypes: List<String> = listOf("hls", "dash", "progressive"),
    val preserveMediaQueryParameters: Boolean = true,
    val preferredQuality: String? = null,
    val onExpired: OnExpiredConfig = OnExpiredConfig()
)

data class FixtureExpectedDef(
    val minimumItems: Int? = null,
    val minimumCandidates: Int? = null,
    val requiredFields: List<String> = emptyList()
)

data class FixtureDefinition(
    val id: String,
    val operation: String,
    val responseFile: String,
    val stepFiles: Map<String, String> = emptyMap(),
    val input: Map<String, String> = emptyMap(),
    val expected: FixtureExpectedDef = FixtureExpectedDef()
)

data class SettingsReferenceWebsiteConfig(
    val id: String,
    val name: String,
    val catalogHosts: List<String> = emptyList(),
    val imageHosts: List<String> = emptyList(),
    val mediaHosts: List<String> = emptyList(),
    val desktopSupported: Boolean = true,
    val mobileSupported: Boolean = true,
    val searchSupported: Boolean = false,
    val directVideoSupported: Boolean = false,
    val sourceStatus: String = "reference-only"
)

data class SettingsReferenceConfig(
    val provider: String,
    val repository: String,
    val pinnedCommit: String,
    val license: String,
    val format: String = "normalized-source-capabilities",
    val sourceStatus: String = "reference-only",
    val website: SettingsReferenceWebsiteConfig? = null
)

enum class ValidationSeverity {
    ERROR,
    WARNING
}

data class ValidationIssue(
    val stage: String,
    val code: HarborErrorCode,
    val path: String,
    val message: String,
    val severity: ValidationSeverity = ValidationSeverity.ERROR
)

data class ValidationResult(
    val isValid: Boolean,
    val definition: SourceDefinition? = null,
    val issues: List<ValidationIssue> = emptyList(),
    val lifecycleState: DefinitionLifecycleState =
        if (isValid) DefinitionLifecycleState.VALIDATED else DefinitionLifecycleState.INCOMPATIBLE
) {
    val errors: List<ValidationIssue>
        get() = issues.filter { it.severity == ValidationSeverity.ERROR }
}
