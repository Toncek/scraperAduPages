package com.harbor.tv.engine.definition

import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import java.nio.charset.StandardCharsets

/**
 * Safely parses JSON and restricted JSON-compatible YAML definitions into [SourceDefinition]
 * without arbitrary object instantiation, YAML custom tags, duplicate keys, or excessive aliases.
 */
class SafeDataParser {

    companion object {
        const val MAX_DEFINITION_BYTES = 512 * 1024 // 512 KiB
        const val MAX_ALIASES = 25
        const val MAX_NESTING_DEPTH = 64

        private val ROOT_KEYS = setOf(
            "schemaVersion", "source", "network", "authentication",
            "taxonomy", "operations", "playback", "fixtures", "settingsReference"
        )
        private val SOURCE_KEYS = setOf(
            "id", "name", "version", "description", "author", "language",
            "baseUrl", "enabledByDefault", "permission", "capabilities"
        )
        private val PERMISSION_KEYS = setOf("basis", "reference", "notes")
        private val CAPABILITIES_KEYS = setOf(
            "categories", "search", "details", "playback", "live", "intermediateTraversal"
        )
        private val NETWORK_KEYS = setOf(
            "httpsOnly", "allowLan", "lanAccess", "allowedHosts", "redirects", "timeouts", "limits", "headers"
        )
        private val ALLOWED_HOSTS_KEYS = setOf("catalog", "images", "media", "authentication")
        private val REDIRECTS_KEYS = setOf("maxHops")
        private val TIMEOUTS_KEYS = setOf("connectMs", "readMs", "operationMs")
        private val LIMITS_KEYS = setOf(
            "maxConcurrentRequests", "minRequestIntervalMs", "maxCatalogResponseBytes", "maxItemsPerPage"
        )
        private val AUTH_KEYS = setOf("type", "profileId", "scopes")
        private val TAXONOMY_KEYS = setOf("namespace", "mappingVersion", "mappings")
        private val OPERATIONS_KEYS = setOf("categories", "listing", "search", "details", "playback")
        private val CATALOG_OP_KEYS = setOf("request", "response", "document", "extract", "pagination", "reuse")
        private val DETAILS_OP_KEYS = setOf("request", "response", "document", "extract", "reuse")
        private val PLAYBACK_OP_KEYS = setOf("steps", "result")
        private val PLAYBACK_STEP_KEYS = setOf("id", "request", "response", "document", "extract")
        private val PLAYBACK_STEP_EXTRACT_KEYS = setOf("candidates", "fields")
        private val PLAYBACK_RESULT_KEYS = setOf("candidatesFrom")
        private val REQUEST_KEYS = setOf("method", "url", "query", "headers")
        private val RESPONSE_KEYS = setOf("type", "encoding")
        private val DOCUMENT_KEYS = setOf("embeddedJson")
        private val EMBEDDED_JSON_KEYS = setOf("selector", "read")
        private val CATALOG_EXTRACT_KEYS = setOf("items")
        private val ITEMS_EXTRACT_KEYS = setOf("selector", "pointer", "limit", "fields")
        private val DETAILS_EXTRACT_KEYS = setOf("fields")
        private val CANDIDATES_EXTRACT_KEYS = setOf("selector", "pointer", "limit", "fields")
        private val FIELD_EXTRACT_KEYS = setOf(
            "selector", "pointer", "read", "attribute", "cardinality", "fallback", "transforms", "required"
        )
        private val TRANSFORM_KEYS = setOf("op", "delimiter", "mappings", "template", "pattern", "group")
        private val PAGINATION_KEYS = setOf("mode", "next", "pageParam", "cursorPointer", "maxPages")
        private val REUSE_KEYS = setOf("extractFrom", "paginationFrom")
        private val PLAYBACK_POLICY_KEYS = setOf(
            "allowedTypes", "preserveMediaQueryParameters", "preferredQuality", "onExpired"
        )
        private val ON_EXPIRED_KEYS = setOf("action", "maxAttempts")
        private val FIXTURE_KEYS = setOf("id", "operation", "responseFile", "stepFiles", "input", "expected")
        private val FIXTURE_EXPECTED_KEYS = setOf("minimumItems", "minimumCandidates", "requiredFields")
        private val SETTINGS_REFERENCE_KEYS = setOf(
            "provider", "repository", "pinnedCommit", "license", "format", "sourceStatus", "website"
        )
        private val SETTINGS_REFERENCE_WEBSITE_KEYS = setOf(
            "id", "name", "catalogHosts", "imageHosts", "mediaHosts",
            "desktopSupported", "mobileSupported", "searchSupported",
            "directVideoSupported", "sourceStatus"
        )
    }

    fun parse(rawText: String): SourceDefinition {
        val rootMap = parseToMap(rawText)
        return mapSourceDefinition(rootMap)
    }

    fun parseToMap(rawText: String): Map<String, Any?> {
        val bytes = rawText.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_DEFINITION_BYTES) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Definition exceeds maximum size of $MAX_DEFINITION_BYTES bytes (actual: ${bytes.size})"
            )
        }
        val stripped = if (rawText.startsWith("\uFEFF")) rawText.substring(1) else rawText
        if (stripped.isBlank()) {
            throw HarborException(HarborErrorCode.DEFINITION_INVALID, "Definition content is blank")
        }

        val trimmed = stripped.trimStart()
        val parsed: Any? = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            StrictJsonParser(stripped).parse()
        } else {
            SafeYamlParser(stripped).parse()
        }

        @Suppress("UNCHECKED_CAST")
        return (parsed as? Map<String, Any?>)
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Definition root must be a mapping/object"
            )
    }

    // -------------------------------------------------------------------------
    // Strict SourceDefinition Mapper (rejects unknown keys and wrong types)
    // -------------------------------------------------------------------------

    private fun mapSourceDefinition(root: Map<String, Any?>): SourceDefinition {
        rejectUnknownKeys(root, ROOT_KEYS, "$")
        val schemaVersion = requireString(root, "schemaVersion", "$")
        val source = mapSourceIdentity(requireMap(root, "source", "$"))
        val network = mapNetworkConfig(requireMap(root, "network", "$"))
        val authentication = optionalMap(root, "authentication", "$")?.let {
            mapAuthConfig(it)
        } ?: AuthConfig()
        val taxonomy = optionalMap(root, "taxonomy", "$")?.let {
            mapTaxonomyConfig(it)
        }
        val operations = mapOperationsConfig(requireMap(root, "operations", "$"))
        val playback = optionalMap(root, "playback", "$")?.let {
            mapPlaybackPolicy(it)
        }
        val fixtures = optionalList(root, "fixtures", "$")?.mapIndexed { idx, item ->
            val map = asStringMap(item, "$.fixtures[$idx]")
            mapFixture(map, "$.fixtures[$idx]")
        } ?: emptyList()
        val settingsReference = optionalMap(root, "settingsReference", "$")?.let {
            mapSettingsReference(it)
        }

        return SourceDefinition(
            schemaVersion = schemaVersion,
            source = source,
            network = network,
            authentication = authentication,
            taxonomy = taxonomy,
            operations = operations,
            playback = playback,
            fixtures = fixtures,
            settingsReference = settingsReference
        )
    }

    private fun mapSourceIdentity(map: Map<String, Any?>): SourceIdentity {
        val path = "$.source"
        rejectUnknownKeys(map, SOURCE_KEYS, path)
        return SourceIdentity(
            id = requireString(map, "id", path),
            name = requireString(map, "name", path),
            version = requireString(map, "version", path),
            description = optionalString(map, "description", path),
            author = optionalString(map, "author", path),
            language = optionalString(map, "language", path),
            baseUrl = requireString(map, "baseUrl", path),
            enabledByDefault = optionalBoolean(map, "enabledByDefault", path) ?: false,
            permission = optionalMap(map, "permission", path)?.let { mapPermission(it) },
            capabilities = optionalMap(map, "capabilities", path)?.let { mapCapabilities(it) }
                ?: CapabilitiesConfig()
        )
    }

    private fun mapPermission(map: Map<String, Any?>): PermissionDeclaration {
        val path = "$.source.permission"
        rejectUnknownKeys(map, PERMISSION_KEYS, path)
        return PermissionDeclaration(
            basis = requireString(map, "basis", path),
            reference = optionalString(map, "reference", path),
            notes = optionalString(map, "notes", path)
        )
    }

    private fun mapCapabilities(map: Map<String, Any?>): CapabilitiesConfig {
        val path = "$.source.capabilities"
        rejectUnknownKeys(map, CAPABILITIES_KEYS, path)
        return CapabilitiesConfig(
            categories = optionalBoolean(map, "categories", path) ?: false,
            search = optionalBoolean(map, "search", path) ?: false,
            details = optionalBoolean(map, "details", path) ?: false,
            playback = optionalBoolean(map, "playback", path) ?: false,
            live = optionalBoolean(map, "live", path) ?: false,
            intermediateTraversal = optionalBoolean(map, "intermediateTraversal", path) ?: false
        )
    }

    private fun mapNetworkConfig(map: Map<String, Any?>): NetworkConfig {
        val path = "$.network"
        rejectUnknownKeys(map, NETWORK_KEYS, path)
        val allowLan = optionalBoolean(map, "allowLan", path)
            ?: optionalBoolean(map, "lanAccess", path)
            ?: false
        return NetworkConfig(
            httpsOnly = optionalBoolean(map, "httpsOnly", path) ?: true,
            allowLan = allowLan,
            allowedHosts = optionalMap(map, "allowedHosts", path)?.let { mapAllowedHosts(it) }
                ?: AllowedHostsConfig(),
            redirects = optionalMap(map, "redirects", path)?.let { mapRedirects(it) }
                ?: RedirectsConfig(),
            timeouts = optionalMap(map, "timeouts", path)?.let { mapTimeouts(it) }
                ?: TimeoutsConfig(),
            limits = optionalMap(map, "limits", path)?.let { mapLimits(it) }
                ?: LimitsConfig(),
            headers = optionalStringMap(map, "headers", path) ?: emptyMap()
        )
    }

    private fun mapAllowedHosts(map: Map<String, Any?>): AllowedHostsConfig {
        val path = "$.network.allowedHosts"
        rejectUnknownKeys(map, ALLOWED_HOSTS_KEYS, path)
        return AllowedHostsConfig(
            catalog = optionalStringList(map, "catalog", path) ?: emptyList(),
            images = optionalStringList(map, "images", path) ?: emptyList(),
            media = optionalStringList(map, "media", path) ?: emptyList(),
            authentication = optionalStringList(map, "authentication", path) ?: emptyList()
        )
    }

    private fun mapRedirects(map: Map<String, Any?>): RedirectsConfig {
        val path = "$.network.redirects"
        rejectUnknownKeys(map, REDIRECTS_KEYS, path)
        return RedirectsConfig(
            maxHops = optionalInt(map, "maxHops", path) ?: 3
        )
    }

    private fun mapTimeouts(map: Map<String, Any?>): TimeoutsConfig {
        val path = "$.network.timeouts"
        rejectUnknownKeys(map, TIMEOUTS_KEYS, path)
        return TimeoutsConfig(
            connectMs = optionalInt(map, "connectMs", path) ?: 10_000,
            readMs = optionalInt(map, "readMs", path) ?: 15_000,
            operationMs = optionalInt(map, "operationMs", path) ?: 30_000
        )
    }

    private fun mapLimits(map: Map<String, Any?>): LimitsConfig {
        val path = "$.network.limits"
        rejectUnknownKeys(map, LIMITS_KEYS, path)
        return LimitsConfig(
            maxConcurrentRequests = optionalInt(map, "maxConcurrentRequests", path) ?: 2,
            minRequestIntervalMs = optionalLong(map, "minRequestIntervalMs", path) ?: 500L,
            maxCatalogResponseBytes = optionalLong(map, "maxCatalogResponseBytes", path) ?: 2_097_152L,
            maxItemsPerPage = optionalInt(map, "maxItemsPerPage", path) ?: 100
        )
    }

    private fun mapAuthConfig(map: Map<String, Any?>): AuthConfig {
        val path = "$.authentication"
        rejectUnknownKeys(map, AUTH_KEYS, path)
        return AuthConfig(
            type = optionalString(map, "type", path) ?: "none",
            profileId = optionalString(map, "profileId", path),
            scopes = optionalStringList(map, "scopes", path) ?: emptyList()
        )
    }

    private fun mapTaxonomyConfig(map: Map<String, Any?>): TaxonomyConfig {
        val path = "$.taxonomy"
        rejectUnknownKeys(map, TAXONOMY_KEYS, path)
        val rawMappings = optionalMap(map, "mappings", path) ?: emptyMap()
        val mappings = LinkedHashMap<String, List<String>>()
        for ((key, value) in rawMappings) {
            val list = value as? List<*> ?: throw invalidType("$path.mappings.$key", "list of strings")
            mappings[key] = list.mapIndexed { idx, elem ->
                elem as? String ?: throw invalidType("$path.mappings.$key[$idx]", "string")
            }
        }
        return TaxonomyConfig(
            namespace = requireString(map, "namespace", path),
            mappingVersion = optionalString(map, "mappingVersion", path) ?: "1.0",
            mappings = mappings
        )
    }

    private fun mapOperationsConfig(map: Map<String, Any?>): OperationsConfig {
        val path = "$.operations"
        rejectUnknownKeys(map, OPERATIONS_KEYS, path)
        return OperationsConfig(
            categories = optionalMap(map, "categories", path)?.let {
                mapCatalogOperation(it, "$path.categories")
            },
            listing = optionalMap(map, "listing", path)?.let {
                mapCatalogOperation(it, "$path.listing")
            },
            search = optionalMap(map, "search", path)?.let {
                mapCatalogOperation(it, "$path.search")
            },
            details = optionalMap(map, "details", path)?.let {
                mapDetailsOperation(it, "$path.details")
            },
            playback = optionalMap(map, "playback", path)?.let {
                mapPlaybackOperation(it, "$path.playback")
            }
        )
    }

    private fun mapCatalogOperation(map: Map<String, Any?>, path: String): CatalogOperationDef {
        rejectUnknownKeys(map, CATALOG_OP_KEYS, path)
        return CatalogOperationDef(
            request = mapRequest(requireMap(map, "request", path), "$path.request"),
            response = mapResponse(requireMap(map, "response", path), "$path.response"),
            document = optionalMap(map, "document", path)?.let { mapDocument(it, "$path.document") },
            extract = optionalMap(map, "extract", path)?.let { mapCatalogExtract(it, "$path.extract") },
            pagination = optionalMap(map, "pagination", path)?.let { mapPagination(it, "$path.pagination") },
            reuse = optionalMap(map, "reuse", path)?.let { mapReuse(it, "$path.reuse") }
        )
    }

    private fun mapDetailsOperation(map: Map<String, Any?>, path: String): DetailsOperationDef {
        rejectUnknownKeys(map, DETAILS_OP_KEYS, path)
        return DetailsOperationDef(
            request = mapRequest(requireMap(map, "request", path), "$path.request"),
            response = mapResponse(requireMap(map, "response", path), "$path.response"),
            document = optionalMap(map, "document", path)?.let { mapDocument(it, "$path.document") },
            extract = optionalMap(map, "extract", path)?.let { mapDetailsExtract(it, "$path.extract") },
            reuse = optionalMap(map, "reuse", path)?.let { mapReuse(it, "$path.reuse") }
        )
    }

    private fun mapPlaybackOperation(map: Map<String, Any?>, path: String): PlaybackOperationDef {
        rejectUnknownKeys(map, PLAYBACK_OP_KEYS, path)
        val rawSteps = requireList(map, "steps", path)
        val steps = rawSteps.mapIndexed { idx, item ->
            val stepMap = asStringMap(item, "$path.steps[$idx]")
            mapPlaybackStep(stepMap, "$path.steps[$idx]")
        }
        val resultMap = requireMap(map, "result", path)
        rejectUnknownKeys(resultMap, PLAYBACK_RESULT_KEYS, "$path.result")
        val result = PlaybackResultDef(
            candidatesFrom = requireString(resultMap, "candidatesFrom", "$path.result")
        )
        return PlaybackOperationDef(steps = steps, result = result)
    }

    private fun mapPlaybackStep(map: Map<String, Any?>, path: String): PlaybackStepDef {
        rejectUnknownKeys(map, PLAYBACK_STEP_KEYS, path)
        return PlaybackStepDef(
            id = requireString(map, "id", path),
            request = mapRequest(requireMap(map, "request", path), "$path.request"),
            response = mapResponse(requireMap(map, "response", path), "$path.response"),
            document = optionalMap(map, "document", path)?.let { mapDocument(it, "$path.document") },
            extract = mapPlaybackStepExtract(requireMap(map, "extract", path), "$path.extract")
        )
    }

    private fun mapPlaybackStepExtract(map: Map<String, Any?>, path: String): PlaybackStepExtractDef {
        rejectUnknownKeys(map, PLAYBACK_STEP_EXTRACT_KEYS, path)
        return PlaybackStepExtractDef(
            candidates = optionalMap(map, "candidates", path)?.let {
                mapCandidatesExtract(it, "$path.candidates")
            },
            fields = optionalMap(map, "fields", path)?.let {
                mapFieldsMap(it, "$path.fields")
            } ?: emptyMap()
        )
    }

    private fun mapCandidatesExtract(map: Map<String, Any?>, path: String): CandidatesExtractionDef {
        rejectUnknownKeys(map, CANDIDATES_EXTRACT_KEYS, path)
        return CandidatesExtractionDef(
            selector = optionalString(map, "selector", path),
            pointer = optionalString(map, "pointer", path),
            limit = optionalInt(map, "limit", path) ?: 20,
            fields = requireMap(map, "fields", path).let { mapFieldsMap(it, "$path.fields") }
        )
    }

    private fun mapRequest(map: Map<String, Any?>, path: String): RequestDef {
        rejectUnknownKeys(map, REQUEST_KEYS, path)
        return RequestDef(
            method = optionalString(map, "method", path) ?: "GET",
            url = requireString(map, "url", path),
            query = optionalStringMap(map, "query", path) ?: emptyMap(),
            headers = optionalStringMap(map, "headers", path) ?: emptyMap()
        )
    }

    private fun mapResponse(map: Map<String, Any?>, path: String): ResponseDef {
        rejectUnknownKeys(map, RESPONSE_KEYS, path)
        return ResponseDef(
            type = requireString(map, "type", path),
            encoding = optionalString(map, "encoding", path)
        )
    }

    private fun mapDocument(map: Map<String, Any?>, path: String): DocumentDef {
        rejectUnknownKeys(map, DOCUMENT_KEYS, path)
        return DocumentDef(
            embeddedJson = optionalMap(map, "embeddedJson", path)?.let {
                rejectUnknownKeys(it, EMBEDDED_JSON_KEYS, "$path.embeddedJson")
                EmbeddedJsonDef(
                    selector = requireString(it, "selector", "$path.embeddedJson"),
                    read = optionalString(it, "read", "$path.embeddedJson") ?: "data"
                )
            }
        )
    }

    private fun mapCatalogExtract(map: Map<String, Any?>, path: String): CatalogExtractDef {
        rejectUnknownKeys(map, CATALOG_EXTRACT_KEYS, path)
        val itemsMap = requireMap(map, "items", path)
        val itemsPath = "$path.items"
        rejectUnknownKeys(itemsMap, ITEMS_EXTRACT_KEYS, itemsPath)
        return CatalogExtractDef(
            items = ItemsExtractionDef(
                selector = optionalString(itemsMap, "selector", itemsPath),
                pointer = optionalString(itemsMap, "pointer", itemsPath),
                limit = optionalInt(itemsMap, "limit", itemsPath) ?: 100,
                fields = requireMap(itemsMap, "fields", itemsPath).let {
                    mapFieldsMap(it, "$itemsPath.fields")
                }
            )
        )
    }

    private fun mapDetailsExtract(map: Map<String, Any?>, path: String): DetailsExtractDef {
        rejectUnknownKeys(map, DETAILS_EXTRACT_KEYS, path)
        return DetailsExtractDef(
            fields = requireMap(map, "fields", path).let { mapFieldsMap(it, "$path.fields") }
        )
    }

    private fun mapFieldsMap(map: Map<String, Any?>, path: String): Map<String, FieldExtractionDef> {
        val out = LinkedHashMap<String, FieldExtractionDef>()
        for ((fieldName, value) in map) {
            val fieldMap = asStringMap(value, "$path.$fieldName")
            out[fieldName] = mapFieldExtraction(fieldMap, "$path.$fieldName")
        }
        return out
    }

    private fun mapFieldExtraction(map: Map<String, Any?>, path: String): FieldExtractionDef {
        rejectUnknownKeys(map, FIELD_EXTRACT_KEYS, path)
        val transforms = optionalList(map, "transforms", path)?.mapIndexed { idx, item ->
            val tMap = asStringMap(item, "$path.transforms[$idx]")
            mapTransform(tMap, "$path.transforms[$idx]")
        } ?: emptyList()

        return FieldExtractionDef(
            selector = optionalString(map, "selector", path),
            pointer = optionalString(map, "pointer", path),
            read = optionalString(map, "read", path),
            attribute = optionalString(map, "attribute", path),
            cardinality = optionalString(map, "cardinality", path) ?: "one",
            fallback = optionalMap(map, "fallback", path)?.let {
                mapFieldExtraction(it, "$path.fallback")
            },
            transforms = transforms,
            required = optionalBoolean(map, "required", path) ?: false
        )
    }

    private fun mapTransform(map: Map<String, Any?>, path: String): TransformDef {
        rejectUnknownKeys(map, TRANSFORM_KEYS, path)
        return TransformDef(
            op = requireString(map, "op", path),
            delimiter = optionalString(map, "delimiter", path),
            mappings = optionalStringMap(map, "mappings", path) ?: emptyMap(),
            template = optionalString(map, "template", path),
            pattern = optionalString(map, "pattern", path),
            group = optionalInt(map, "group", path) ?: 1
        )
    }

    private fun mapPagination(map: Map<String, Any?>, path: String): PaginationDef {
        rejectUnknownKeys(map, PAGINATION_KEYS, path)
        return PaginationDef(
            mode = optionalString(map, "mode", path) ?: "nextLink",
            next = optionalMap(map, "next", path)?.let { mapFieldExtraction(it, "$path.next") },
            pageParam = optionalString(map, "pageParam", path),
            cursorPointer = optionalString(map, "cursorPointer", path),
            maxPages = optionalInt(map, "maxPages", path) ?: 20
        )
    }

    private fun mapReuse(map: Map<String, Any?>, path: String): ReuseDef {
        rejectUnknownKeys(map, REUSE_KEYS, path)
        return ReuseDef(
            extractFrom = optionalString(map, "extractFrom", path),
            paginationFrom = optionalString(map, "paginationFrom", path)
        )
    }

    private fun mapPlaybackPolicy(map: Map<String, Any?>): PlaybackPolicyConfig {
        val path = "$.playback"
        rejectUnknownKeys(map, PLAYBACK_POLICY_KEYS, path)
        val onExpired = optionalMap(map, "onExpired", path)?.let {
            rejectUnknownKeys(it, ON_EXPIRED_KEYS, "$path.onExpired")
            OnExpiredConfig(
                action = optionalString(it, "action", "$path.onExpired") ?: "resolveAgain",
                maxAttempts = optionalInt(it, "maxAttempts", "$path.onExpired") ?: 1
            )
        } ?: OnExpiredConfig()

        return PlaybackPolicyConfig(
            allowedTypes = optionalStringList(map, "allowedTypes", path)
                ?: listOf("hls", "dash", "progressive"),
            preserveMediaQueryParameters = optionalBoolean(map, "preserveMediaQueryParameters", path) ?: true,
            preferredQuality = optionalString(map, "preferredQuality", path),
            onExpired = onExpired
        )
    }

    private fun mapFixture(map: Map<String, Any?>, path: String): FixtureDefinition {
        rejectUnknownKeys(map, FIXTURE_KEYS, path)
        val expected = optionalMap(map, "expected", path)?.let {
            rejectUnknownKeys(it, FIXTURE_EXPECTED_KEYS, "$path.expected")
            FixtureExpectedDef(
                minimumItems = optionalInt(it, "minimumItems", "$path.expected"),
                minimumCandidates = optionalInt(it, "minimumCandidates", "$path.expected"),
                requiredFields = optionalStringList(it, "requiredFields", "$path.expected") ?: emptyList()
            )
        } ?: FixtureExpectedDef()

        val stepFiles = optionalStringMap(map, "stepFiles", path) ?: emptyMap()
        val responseFile = optionalString(map, "responseFile", path)
            ?: stepFiles.values.firstOrNull()
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Missing required string property '$path.responseFile'"
            )

        return FixtureDefinition(
            id = requireString(map, "id", path),
            operation = requireString(map, "operation", path),
            responseFile = responseFile,
            stepFiles = stepFiles,
            input = optionalStringMap(map, "input", path) ?: emptyMap(),
            expected = expected
        )
    }

    private fun mapSettingsReference(map: Map<String, Any?>): SettingsReferenceConfig {
        val path = "$.settingsReference"
        rejectUnknownKeys(map, SETTINGS_REFERENCE_KEYS, path)
        val website = optionalMap(map, "website", path)?.let { mapSettingsReferenceWebsite(it) }
        return SettingsReferenceConfig(
            provider = requireString(map, "provider", path),
            repository = requireString(map, "repository", path),
            pinnedCommit = requireString(map, "pinnedCommit", path),
            license = requireString(map, "license", path),
            format = optionalString(map, "format", path) ?: "normalized-source-capabilities",
            sourceStatus = optionalString(map, "sourceStatus", path) ?: website?.sourceStatus ?: "reference-only",
            website = website
        )
    }

    private fun mapSettingsReferenceWebsite(map: Map<String, Any?>): SettingsReferenceWebsiteConfig {
        val path = "$.settingsReference.website"
        rejectUnknownKeys(map, SETTINGS_REFERENCE_WEBSITE_KEYS, path)
        return SettingsReferenceWebsiteConfig(
            id = requireString(map, "id", path),
            name = requireString(map, "name", path),
            catalogHosts = optionalStringList(map, "catalogHosts", path) ?: emptyList(),
            imageHosts = optionalStringList(map, "imageHosts", path) ?: emptyList(),
            mediaHosts = optionalStringList(map, "mediaHosts", path) ?: emptyList(),
            desktopSupported = optionalBoolean(map, "desktopSupported", path) ?: true,
            mobileSupported = optionalBoolean(map, "mobileSupported", path) ?: true,
            searchSupported = optionalBoolean(map, "searchSupported", path) ?: false,
            directVideoSupported = optionalBoolean(map, "directVideoSupported", path) ?: false,
            sourceStatus = optionalString(map, "sourceStatus", path) ?: "reference-only"
        )
    }

    // -------------------------------------------------------------------------
    // Typed map extraction helpers
    // -------------------------------------------------------------------------

    private fun rejectUnknownKeys(map: Map<String, Any?>, allowed: Set<String>, path: String) {
        for (key in map.keys) {
            if (key !in allowed) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Unknown configuration property '$path.$key'"
                )
            }
        }
    }

    private fun requireString(map: Map<String, Any?>, key: String, path: String): String {
        if (!map.containsKey(key) || map[key] == null) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Missing required string property '$path.$key'"
            )
        }
        return map[key] as? String ?: throw invalidType("$path.$key", "string")
    }

    private fun optionalString(map: Map<String, Any?>, key: String, path: String): String? {
        val value = map[key] ?: return null
        return value as? String ?: throw invalidType("$path.$key", "string")
    }

    private fun optionalBoolean(map: Map<String, Any?>, key: String, path: String): Boolean? {
        val value = map[key] ?: return null
        return value as? Boolean ?: throw invalidType("$path.$key", "boolean")
    }

    private fun optionalInt(map: Map<String, Any?>, key: String, path: String): Int? {
        val value = map[key] ?: return null
        val number = value as? Number ?: throw invalidType("$path.$key", "integer")
        if (number is Double || number is Float) {
            val d = number.toDouble()
            if (d % 1.0 != 0.0) throw invalidType("$path.$key", "integer")
            return d.toInt()
        }
        val longVal = number.toLong()
        if (longVal < Int.MIN_VALUE || longVal > Int.MAX_VALUE) {
            throw invalidType("$path.$key", "32-bit integer")
        }
        return longVal.toInt()
    }

    private fun optionalLong(map: Map<String, Any?>, key: String, path: String): Long? {
        val value = map[key] ?: return null
        val number = value as? Number ?: throw invalidType("$path.$key", "integer")
        if (number is Double || number is Float) {
            val d = number.toDouble()
            if (d % 1.0 != 0.0) throw invalidType("$path.$key", "integer")
            return d.toLong()
        }
        return number.toLong()
    }

    private fun requireMap(map: Map<String, Any?>, key: String, path: String): Map<String, Any?> {
        if (!map.containsKey(key) || map[key] == null) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Missing required object property '$path.$key'"
            )
        }
        return asStringMap(map[key], "$path.$key")
    }

    private fun optionalMap(map: Map<String, Any?>, key: String, path: String): Map<String, Any?>? {
        val value = map[key] ?: return null
        return asStringMap(value, "$path.$key")
    }

    @Suppress("UNCHECKED_CAST")
    private fun asStringMap(value: Any?, path: String): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: throw invalidType(path, "object/map")
        for (k in raw.keys) {
            if (k !is String) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Non-string key in mapping at '$path'"
                )
            }
        }
        return raw as Map<String, Any?>
    }

    private fun requireList(map: Map<String, Any?>, key: String, path: String): List<Any?> {
        if (!map.containsKey(key) || map[key] == null) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Missing required array property '$path.$key'"
            )
        }
        return map[key] as? List<*> ?: throw invalidType("$path.$key", "array/list")
    }

    private fun optionalList(map: Map<String, Any?>, key: String, path: String): List<Any?>? {
        val value = map[key] ?: return null
        return value as? List<*> ?: throw invalidType("$path.$key", "array/list")
    }

    private fun optionalStringList(map: Map<String, Any?>, key: String, path: String): List<String>? {
        val list = optionalList(map, key, path) ?: return null
        return list.mapIndexed { idx, item ->
            item as? String ?: throw invalidType("$path.$key[$idx]", "string")
        }
    }

    private fun optionalStringMap(map: Map<String, Any?>, key: String, path: String): Map<String, String>? {
        val sub = optionalMap(map, key, path) ?: return null
        val out = LinkedHashMap<String, String>()
        for ((k, v) in sub) {
            out[k] = v as? String ?: throw invalidType("$path.$key.$k", "string")
        }
        return out
    }

    private fun invalidType(path: String, expected: String): HarborException {
        return HarborException(
            HarborErrorCode.DEFINITION_INVALID,
            "Invalid type at '$path': expected $expected"
        )
    }

    // =========================================================================
    // Strict RFC 8259 JSON Parser (rejects duplicate keys, trailing commas, comments)
    // =========================================================================

    private class StrictJsonParser(private val src: String) {
        private var pos = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (pos < src.length) {
                throw syntaxError("Unexpected trailing content in JSON")
            }
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > MAX_NESTING_DEPTH) {
                throw syntaxError("JSON nesting depth exceeds $MAX_NESTING_DEPTH")
            }
            skipWhitespace()
            if (pos >= src.length) throw syntaxError("Unexpected end of JSON input")
            return when (src[pos]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw syntaxError("Unexpected character '${src[pos]}' in JSON")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            pos++ // '{'
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (pos >= src.length || src[pos] != '"') {
                    throw syntaxError("Expected string key in JSON object")
                }
                val key = parseString()
                if (map.containsKey(key)) {
                    throw HarborException(
                        HarborErrorCode.DEFINITION_INVALID,
                        "Duplicate JSON key '$key'"
                    )
                }
                skipWhitespace()
                if (pos >= src.length || src[pos] != ':') {
                    throw syntaxError("Expected ':' after JSON key '$key'")
                }
                pos++
                val value = parseValue(depth)
                map[key] = value
                skipWhitespace()
                if (pos >= src.length) throw syntaxError("Unterminated JSON object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw syntaxError("Expected ',' or '}' in JSON object")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            pos++ // '['
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue(depth))
                skipWhitespace()
                if (pos >= src.length) throw syntaxError("Unterminated JSON array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw syntaxError("Expected ',' or ']' in JSON array")
                }
            }
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= src.length) throw syntaxError("Unterminated escape sequence in JSON string")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw syntaxError("Incomplete \\uXXXX escape")
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                val code = hex.toIntOrNull(16)
                                    ?: throw syntaxError("Invalid hex in \\u$hex")
                                sb.append(code.toChar())
                            }
                            else -> throw syntaxError("Invalid escape '\\$esc' in JSON string")
                        }
                    }
                    ch.code < 0x20 -> throw syntaxError("Unescaped control character in JSON string")
                    else -> sb.append(ch)
                }
            }
            throw syntaxError("Unterminated JSON string")
        }

        private fun parseLiteral(expected: String, result: Any?): Any? {
            if (!src.startsWith(expected, pos)) {
                throw syntaxError("Unexpected token near '${src.substring(pos, minOf(src.length, pos + 10))}'")
            }
            pos += expected.length
            return result
        }

        private fun parseNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            if (pos >= src.length) throw syntaxError("Invalid JSON number")
            if (src[pos] == '0') {
                pos++
            } else if (src[pos] in '1'..'9') {
                while (pos < src.length && src[pos] in '0'..'9') pos++
            } else {
                throw syntaxError("Invalid JSON number")
            }

            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                if (pos >= src.length || src[pos] !in '0'..'9') {
                    throw syntaxError("Invalid fraction in JSON number")
                }
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                if (pos >= src.length || src[pos] !in '0'..'9') {
                    throw syntaxError("Invalid exponent in JSON number")
                }
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }

            val raw = src.substring(start, pos)
            return if (isFloat) {
                raw.toDoubleOrNull() ?: throw syntaxError("Invalid JSON float '$raw'")
            } else {
                raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw syntaxError("Invalid JSON integer '$raw'")
            }
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos] in charArrayOf(' ', '\t', '\r', '\n')) {
                pos++
            }
        }

        private fun syntaxError(msg: String): HarborException {
            return HarborException(HarborErrorCode.DEFINITION_INVALID, "$msg (at offset $pos)")
        }
    }

    // =========================================================================
    // Safe Restricted YAML Parser
    // Rejects custom tags (!), arbitrary objects, >25 aliases, cyclic aliases,
    // duplicate keys, and non-string keys.
    // =========================================================================

    private class SafeYamlParser(src: String) {
        private val lines: List<String> = src.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        private var lineIdx = 0
        private val anchors = HashMap<String, Any?>()
        private var aliasCount = 0

        fun parse(): Any? {
            skipBlankAndComments()
            if (lineIdx >= lines.size) {
                throw yamlError("Empty YAML document")
            }
            // Optional single document header '---'
            val firstTrimmed = lines[lineIdx].trim()
            if (firstTrimmed == "---" || firstTrimmed.startsWith("--- ")) {
                if (firstTrimmed != "---") {
                    // Reject directives/tags on document header
                    val afterHeader = firstTrimmed.removePrefix("---").trim()
                    if (afterHeader.startsWith("!")) {
                        throw yamlError("YAML tags are forbidden")
                    }
                }
                lineIdx++
                skipBlankAndComments()
            } else if (firstTrimmed.startsWith("%")) {
                throw yamlError("YAML directives are forbidden")
            }

            if (lineIdx >= lines.size) {
                throw yamlError("Empty YAML document")
            }

            val rootIndent = countIndent(lineIdx)
            val result = parseBlockNode(rootIndent, depth = 0)

            skipBlankAndComments()
            if (lineIdx < lines.size) {
                val rem = lines[lineIdx].trim()
                if (rem == "...") {
                    lineIdx++
                    skipBlankAndComments()
                }
            }
            if (lineIdx < lines.size) {
                throw yamlError("Multiple YAML documents or unexpected trailing content at line ${lineIdx + 1}")
            }
            return result
        }

        private fun parseBlockNode(minIndent: Int, depth: Int): Any? {
            if (depth > MAX_NESTING_DEPTH) {
                throw yamlError("YAML nesting depth exceeds $MAX_NESTING_DEPTH")
            }
            skipBlankAndComments()
            if (lineIdx >= lines.size) return null

            val line = lines[lineIdx]
            val trimmed = line.trimStart()
            if (trimmed == "---" || trimmed.startsWith("--- ") || trimmed == "...") {
                return null
            }

            val indent = countIndent(lineIdx)
            if (indent < minIndent) return null

            if (trimmed.startsWith("!")) {
                throw yamlError("YAML tags are forbidden at line ${lineIdx + 1}")
            }
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return parseFlowFromCurrentPos(indent, depth)
            }
            if (isSequenceEntry(trimmed)) {
                return parseBlockSequence(indent, depth)
            }
            return parseBlockMapping(indent, firstKeyInline = null, depth = depth)
        }

        private fun parseBlockSequence(seqIndent: Int, depth: Int): List<Any?> {
            if (depth > MAX_NESTING_DEPTH) {
                throw yamlError("YAML nesting depth exceeds $MAX_NESTING_DEPTH")
            }
            val list = ArrayList<Any?>()
            while (lineIdx < lines.size) {
                skipBlankAndComments()
                if (lineIdx >= lines.size) break
                val indent = countIndent(lineIdx)
                if (indent != seqIndent) break
                val trimmed = lines[lineIdx].substring(indent)
                if (trimmed == "---" || trimmed == "...") break
                if (!isSequenceEntry(trimmed)) break

                val afterDashOffset = indent + 1
                val rest = lines[lineIdx].substring(afterDashOffset)
                val trimmedRest = rest.trimStart()
                val leadingSpacesAfterDash = rest.length - trimmedRest.length
                val valueStartCol = afterDashOffset + leadingSpacesAfterDash

                if (trimmedRest.isEmpty() || trimmedRest.startsWith("#")) {
                    lineIdx++
                    skipBlankAndComments()
                    val value = if (lineIdx < lines.size && countIndent(lineIdx) > seqIndent) {
                        parseBlockNode(seqIndent + 1, depth + 1)
                    } else {
                        null
                    }
                    list.add(value)
                } else {
                    var anchorName: String? = null
                    var content = trimmedRest
                    var contentCol = valueStartCol
                    if (content.startsWith("!")) {
                        throw yamlError("YAML tags are forbidden at line ${lineIdx + 1}")
                    }
                    if (content.startsWith("&")) {
                        val parsedAnchor = extractAnchorPrefix(content)
                        anchorName = parsedAnchor.first
                        val afterAnchor = parsedAnchor.second
                        val spaces = afterAnchor.length - afterAnchor.trimStart().length
                        content = afterAnchor.trimStart()
                        contentCol += (parsedAnchor.third + spaces)
                    }

                    val itemValue: Any? = if (content.isEmpty() || content.startsWith("#")) {
                        lineIdx++
                        skipBlankAndComments()
                        if (lineIdx < lines.size && countIndent(lineIdx) > seqIndent) {
                            parseBlockNode(seqIndent + 1, depth + 1)
                        } else {
                            null
                        }
                    } else if (looksLikeMappingEntry(content)) {
                        parseBlockMapping(
                            mapIndent = contentCol,
                            firstKeyInline = content,
                            depth = depth + 1
                        )
                    } else {
                        parseInlineValue(content, parentIndent = seqIndent, depth = depth + 1)
                    }

                    if (anchorName != null) {
                        anchors[anchorName] = itemValue
                    }
                    list.add(itemValue)
                }
            }
            return list
        }

        private fun parseBlockMapping(
            mapIndent: Int,
            firstKeyInline: String?,
            depth: Int
        ): Map<String, Any?> {
            if (depth > MAX_NESTING_DEPTH) {
                throw yamlError("YAML nesting depth exceeds $MAX_NESTING_DEPTH")
            }
            val map = LinkedHashMap<String, Any?>()
            var inlineLineContent = firstKeyInline

            while (lineIdx < lines.size) {
                val rawContent: String
                if (inlineLineContent != null) {
                    rawContent = inlineLineContent
                    inlineLineContent = null
                } else {
                    skipBlankAndComments()
                    if (lineIdx >= lines.size) break
                    val indent = countIndent(lineIdx)
                    if (indent < mapIndent) break
                    if (indent > mapIndent) {
                        throw yamlError("Unexpected indentation at line ${lineIdx + 1}")
                    }
                    val trimmed = lines[lineIdx].substring(indent)
                    if (trimmed == "---" || trimmed == "...") break
                    if (isSequenceEntry(trimmed)) break
                    rawContent = trimmed
                }

                if (rawContent.startsWith("!")) {
                    throw yamlError("YAML tags are forbidden at line ${lineIdx + 1}")
                }
                if (rawContent.startsWith("{") || rawContent.startsWith("[") || rawContent.startsWith("?")) {
                    throw yamlError("Complex or non-string YAML mapping keys are forbidden at line ${lineIdx + 1}")
                }

                val (key, afterColon) = splitMappingKeyAndValue(rawContent, lineIdx + 1)
                if (key == "<<") {
                    throw yamlError("YAML merge keys ('<<') are forbidden at line ${lineIdx + 1}")
                }
                if (map.containsKey(key)) {
                    throw HarborException(
                        HarborErrorCode.DEFINITION_INVALID,
                        "Duplicate YAML key '$key' at line ${lineIdx + 1}"
                    )
                }

                val trimmedVal = afterColon.trimStart()
                var anchorName: String? = null
                var effectiveVal = trimmedVal
                if (effectiveVal.startsWith("!")) {
                    throw yamlError("YAML tags are forbidden at line ${lineIdx + 1}")
                }
                if (effectiveVal.startsWith("&")) {
                    val parsedAnchor = extractAnchorPrefix(effectiveVal)
                    anchorName = parsedAnchor.first
                    effectiveVal = parsedAnchor.second.trimStart()
                }

                val value: Any? = if (effectiveVal.isEmpty() || effectiveVal.startsWith("#")) {
                    lineIdx++
                    skipBlankAndComments()
                    if (lineIdx < lines.size) {
                        val nextIndent = countIndent(lineIdx)
                        val nextTrimmed = lines[lineIdx].substring(nextIndent)
                        if (nextIndent > mapIndent) {
                            parseBlockNode(nextIndent, depth + 1)
                        } else if (nextIndent == mapIndent && isSequenceEntry(nextTrimmed)) {
                            parseBlockSequence(nextIndent, depth + 1)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    parseInlineValue(effectiveVal, parentIndent = mapIndent, depth = depth + 1)
                }

                if (anchorName != null) {
                    anchors[anchorName] = value
                }
                map[key] = value
            }

            return map
        }

        private fun parseInlineValue(raw: String, parentIndent: Int, depth: Int): Any? {
            if (raw.startsWith("!")) {
                throw yamlError("YAML tags are forbidden at line ${lineIdx + 1}")
            }
            if (raw.startsWith("*")) {
                val aliasPart = stripTrailingComment(raw).trim()
                lineIdx++
                return resolveAlias(aliasPart.substring(1))
            }
            if (raw.startsWith("|") || raw.startsWith(">")) {
                return parseBlockScalar(raw, parentIndent)
            }
            if (raw.startsWith("{") || raw.startsWith("[")) {
                val col = lines[lineIdx].indexOf(raw)
                return parseFlowFromLineCol(lineIdx, maxOf(0, col), depth)
            }
            if (raw.startsWith("\"")) {
                val (str, rest) = parseDoubleQuotedScalar(raw, lineIdx + 1)
                val trailing = rest.trim()
                if (trailing.isNotEmpty() && !trailing.startsWith("#")) {
                    throw yamlError("Unexpected trailing tokens after quoted string at line ${lineIdx + 1}")
                }
                lineIdx++
                return str
            }
            if (raw.startsWith("'")) {
                val (str, rest) = parseSingleQuotedScalar(raw, lineIdx + 1)
                val trailing = rest.trim()
                if (trailing.isNotEmpty() && !trailing.startsWith("#")) {
                    throw yamlError("Unexpected trailing tokens after quoted string at line ${lineIdx + 1}")
                }
                lineIdx++
                return str
            }
            val cleaned = stripTrailingComment(raw).trim()
            lineIdx++
            return evaluateUnquotedScalar(cleaned)
        }

        private fun parseBlockScalar(headerRaw: String, parentIndent: Int): String {
            val header = stripTrailingComment(headerRaw).trim()
            val folded = header.startsWith(">")
            var chomp = "clip"
            for (c in header.substring(1)) {
                when (c) {
                    '-' -> chomp = "strip"
                    '+' -> chomp = "keep"
                    in '1'..'9' -> {} // explicit indent indicator ignored/handled via content indent
                    else -> throw yamlError("Invalid block scalar header '$header' at line ${lineIdx + 1}")
                }
            }
            lineIdx++
            val rawBlockLines = ArrayList<String>()
            var blockIndent = -1
            while (lineIdx < lines.size) {
                val line = lines[lineIdx]
                if (line.isBlank()) {
                    rawBlockLines.add("")
                    lineIdx++
                    continue
                }
                val indent = countIndent(lineIdx)
                if (indent <= parentIndent) break
                if (blockIndent == -1) blockIndent = indent
                if (indent < blockIndent) break
                rawBlockLines.add(line.substring(blockIndent))
                lineIdx++
            }
            while (rawBlockLines.isNotEmpty() && rawBlockLines.last().isEmpty() && chomp != "keep") {
                rawBlockLines.removeAt(rawBlockLines.size - 1)
            }
            val joined = if (folded) {
                rawBlockLines.joinToString(" ")
            } else {
                rawBlockLines.joinToString("\n")
            }
            return when (chomp) {
                "strip" -> joined
                "keep" -> "$joined\n"
                else -> if (rawBlockLines.isEmpty()) "" else "$joined\n"
            }
        }

        private fun parseFlowFromCurrentPos(indent: Int, depth: Int): Any? {
            return parseFlowFromLineCol(lineIdx, indent, depth)
        }

        private fun parseFlowFromLineCol(startLine: Int, startCol: Int, depth: Int): Any? {
            // Join remaining lines from startLine/startCol as a single stream and advance lineIdx cleanly
            val sb = StringBuilder()
            val lineStarts = ArrayList<Int>()
            var cur = startLine
            while (cur < lines.size) {
                lineStarts.add(sb.length)
                val line = if (cur == startLine) lines[cur].substring(startCol) else lines[cur]
                sb.append(line).append('\n')
                cur++
            }
            val parser = FlowParser(sb.toString(), depth)
            val result = parser.parse()
            val consumedOffset = parser.pos
            // Determine how many lines were consumed
            var lastLineOffset = 0
            for (i in lineStarts.indices) {
                if (lineStarts[i] <= consumedOffset) {
                    lastLineOffset = i
                }
            }
            val endLine = startLine + lastLineOffset
            val offsetInEndLine = consumedOffset - lineStarts[lastLineOffset]
            val endLineText = if (endLine == startLine) lines[endLine].substring(startCol) else lines[endLine]
            val trailing = endLineText.substring(minOf(offsetInEndLine, endLineText.length)).trim()
            if (trailing.isNotEmpty() && !trailing.startsWith("#")) {
                throw yamlError("Unexpected trailing tokens after flow collection at line ${endLine + 1}")
            }
            lineIdx = endLine + 1
            return result
        }

        private inner class FlowParser(private val text: String, private val baseDepth: Int) {
            var pos = 0

            fun parse(): Any? {
                skipFlowWsAndComments()
                return parseFlowValue(baseDepth)
            }

            private fun parseFlowValue(depth: Int): Any? {
                if (depth > MAX_NESTING_DEPTH) {
                    throw yamlError("YAML nesting depth exceeds $MAX_NESTING_DEPTH")
                }
                skipFlowWsAndComments()
                if (pos >= text.length) throw yamlError("Unexpected end of YAML flow collection")
                return when (text[pos]) {
                    '!' -> throw yamlError("YAML tags are forbidden")
                    '*' -> {
                        pos++
                        val start = pos
                        while (pos < text.length && isAnchorChar(text[pos])) pos++
                        resolveAlias(text.substring(start, pos))
                    }
                    '{' -> parseFlowMap(depth + 1)
                    '[' -> parseFlowList(depth + 1)
                    '"' -> parseFlowDoubleQuoted()
                    '\'' -> parseFlowSingleQuoted()
                    else -> {
                        val start = pos
                        while (pos < text.length && text[pos] !in charArrayOf(',', ']', '}', '\n', '#')) {
                            pos++
                        }
                        val raw = text.substring(start, pos).trim()
                        if (raw.startsWith("!")) throw yamlError("YAML tags are forbidden")
                        evaluateUnquotedScalar(raw)
                    }
                }
            }

            private fun parseFlowMap(depth: Int): Map<String, Any?> {
                pos++ // '{'
                val map = LinkedHashMap<String, Any?>()
                skipFlowWsAndComments()
                if (pos < text.length && text[pos] == '}') {
                    pos++
                    return map
                }
                while (true) {
                    skipFlowWsAndComments()
                    if (pos >= text.length) throw yamlError("Unterminated YAML flow mapping")
                    if (text[pos] == '}') {
                        pos++
                        return map
                    }
                    if (text[pos] == '!') throw yamlError("YAML tags are forbidden")
                    if (text[pos] == '{' || text[pos] == '[' || text[pos] == '*') {
                        throw yamlError("Non-string key in YAML flow mapping")
                    }
                    val key: String = when (text[pos]) {
                        '"' -> parseFlowDoubleQuoted()
                        '\'' -> parseFlowSingleQuoted()
                        else -> {
                            val start = pos
                            while (pos < text.length) {
                                val ch = text[pos]
                                if (ch == ':' && (pos + 1 >= text.length || text[pos + 1] in charArrayOf(' ', '\t', '\n', ',', '}'))) {
                                    break
                                }
                                if (ch in charArrayOf(',', '}', '\n')) break
                                pos++
                            }
                            val rawKey = text.substring(start, pos).trim()
                            validateUnquotedStringKey(rawKey, lineIdx + 1)
                            rawKey
                        }
                    }
                    if (map.containsKey(key)) {
                        throw HarborException(
                            HarborErrorCode.DEFINITION_INVALID,
                            "Duplicate YAML key '$key'"
                        )
                    }
                    skipFlowWsAndComments()
                    if (pos >= text.length || text[pos] != ':') {
                        throw yamlError("Expected ':' after key '$key' in YAML flow mapping")
                    }
                    pos++
                    skipFlowWsAndComments()
                    val value = if (pos < text.length && (text[pos] == ',' || text[pos] == '}')) {
                        null
                    } else {
                        parseFlowValue(depth)
                    }
                    map[key] = value
                    skipFlowWsAndComments()
                    if (pos >= text.length) throw yamlError("Unterminated YAML flow mapping")
                    when (text[pos]) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return map
                        }
                        else -> throw yamlError("Expected ',' or '}' in YAML flow mapping")
                    }
                }
            }

            private fun parseFlowList(depth: Int): List<Any?> {
                pos++ // '['
                val list = ArrayList<Any?>()
                skipFlowWsAndComments()
                if (pos < text.length && text[pos] == ']') {
                    pos++
                    return list
                }
                while (true) {
                    skipFlowWsAndComments()
                    if (pos >= text.length) throw yamlError("Unterminated YAML flow sequence")
                    if (text[pos] == ']') {
                        pos++
                        return list
                    }
                    list.add(parseFlowValue(depth))
                    skipFlowWsAndComments()
                    if (pos >= text.length) throw yamlError("Unterminated YAML flow sequence")
                    when (text[pos]) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return list
                        }
                        else -> throw yamlError("Expected ',' or ']' in YAML flow sequence")
                    }
                }
            }

            private fun parseFlowDoubleQuoted(): String {
                val slice = text.substring(pos)
                val (str, rest) = parseDoubleQuotedScalar(slice, lineIdx + 1)
                pos += slice.length - rest.length
                return str
            }

            private fun parseFlowSingleQuoted(): String {
                val slice = text.substring(pos)
                val (str, rest) = parseSingleQuotedScalar(slice, lineIdx + 1)
                pos += slice.length - rest.length
                return str
            }

            private fun skipFlowWsAndComments() {
                while (pos < text.length) {
                    val ch = text[pos]
                    if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                        pos++
                    } else if (ch == '#' && (pos == 0 || text[pos - 1] in charArrayOf(' ', '\t', '\n', ',', '[', '{'))) {
                        while (pos < text.length && text[pos] != '\n') pos++
                    } else {
                        break
                    }
                }
            }
        }

        private fun resolveAlias(name: String): Any? {
            if (name.isEmpty()) throw yamlError("Empty YAML alias at line ${lineIdx + 1}")
            aliasCount++
            if (aliasCount > MAX_ALIASES) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "YAML alias count exceeds maximum allowed ($MAX_ALIASES)"
                )
            }
            if (!anchors.containsKey(name)) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Undefined or cyclic YAML alias '*$name'"
                )
            }
            return anchors[name]
        }

        private fun extractAnchorPrefix(content: String): Triple<String, String, Int> {
            var i = 1
            while (i < content.length && isAnchorChar(content[i])) i++
            val name = content.substring(1, i)
            if (name.isEmpty()) {
                throw yamlError("Empty YAML anchor name at line ${lineIdx + 1}")
            }
            return Triple(name, content.substring(i), i)
        }

        private fun isAnchorChar(c: Char): Boolean {
            return !c.isWhitespace() && c !in charArrayOf('[', ']', '{', '}', ',', ':')
        }

        private fun splitMappingKeyAndValue(line: String, lineNum: Int): Pair<String, String> {
            if (line.startsWith("\"")) {
                val (key, rest) = parseDoubleQuotedScalar(line, lineNum)
                val trimmedRest = rest.trimStart()
                if (!trimmedRest.startsWith(":") || (trimmedRest.length > 1 && !trimmedRest[1].isWhitespace())) {
                    throw yamlError("Expected ':' after quoted key '$key' at line $lineNum")
                }
                return key to trimmedRest.substring(1)
            }
            if (line.startsWith("'")) {
                val (key, rest) = parseSingleQuotedScalar(line, lineNum)
                val trimmedRest = rest.trimStart()
                if (!trimmedRest.startsWith(":") || (trimmedRest.length > 1 && !trimmedRest[1].isWhitespace())) {
                    throw yamlError("Expected ':' after quoted key '$key' at line $lineNum")
                }
                return key to trimmedRest.substring(1)
            }
            val colonIdx = findMappingColonIndex(line)
            if (colonIdx < 0) {
                throw yamlError("Expected 'key: value' mapping entry at line $lineNum")
            }
            val rawKey = line.substring(0, colonIdx).trim()
            validateUnquotedStringKey(rawKey, lineNum)
            return rawKey to line.substring(colonIdx + 1)
        }

        private fun validateUnquotedStringKey(rawKey: String, lineNum: Int) {
            if (rawKey.isEmpty()) {
                throw yamlError("Empty YAML mapping key at line $lineNum")
            }
            val evaluated = evaluateUnquotedScalar(rawKey)
            if (evaluated !is String) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Non-string YAML mapping key '$rawKey' is forbidden at line $lineNum"
                )
            }
        }

        private fun looksLikeMappingEntry(content: String): Boolean {
            if (content.startsWith("\"")) {
                val (_, rest) = parseDoubleQuotedScalar(content, lineIdx + 1)
                val tr = rest.trimStart()
                return tr.startsWith(":") && (tr.length == 1 || tr[1].isWhitespace())
            }
            if (content.startsWith("'")) {
                val (_, rest) = parseSingleQuotedScalar(content, lineIdx + 1)
                val tr = rest.trimStart()
                return tr.startsWith(":") && (tr.length == 1 || tr[1].isWhitespace())
            }
            if (content.startsWith("{") || content.startsWith("[")) return false
            return findMappingColonIndex(content) > 0
        }

        private fun findMappingColonIndex(text: String): Int {
            for (i in text.indices) {
                if (text[i] == ':') {
                    if (i + 1 >= text.length || text[i + 1] == ' ' || text[i + 1] == '\t') {
                        return i
                    }
                }
                if (text[i] == '#' && (i == 0 || text[i - 1].isWhitespace())) {
                    return -1
                }
            }
            return -1
        }

        private fun isSequenceEntry(trimmed: String): Boolean {
            return trimmed == "-" || trimmed.startsWith("- ") || trimmed.startsWith("-\t")
        }

        private fun countIndent(idx: Int): Int {
            val line = lines[idx]
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (ch == ' ') {
                    i++
                } else if (ch == '\t') {
                    throw yamlError("Tabs are forbidden in YAML indentation at line ${idx + 1}")
                } else {
                    break
                }
            }
            return i
        }

        private fun skipBlankAndComments() {
            while (lineIdx < lines.size) {
                val trimmed = lines[lineIdx].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    lineIdx++
                } else {
                    break
                }
            }
        }

        private fun stripTrailingComment(raw: String): String {
            for (i in raw.indices) {
                if (raw[i] == '#' && (i == 0 || raw[i - 1].isWhitespace())) {
                    return raw.substring(0, i)
                }
            }
            return raw
        }

        private fun parseDoubleQuotedScalar(raw: String, lineNum: Int): Pair<String, String> {
            val sb = StringBuilder()
            var i = 1
            while (i < raw.length) {
                val ch = raw[i++]
                when (ch) {
                    '"' -> return sb.toString() to raw.substring(i)
                    '\\' -> {
                        if (i >= raw.length) throw yamlError("Unterminated escape in string at line $lineNum")
                        when (val esc = raw[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '0' -> sb.append('\u0000')
                            'u' -> {
                                if (i + 4 > raw.length) throw yamlError("Invalid \\u escape at line $lineNum")
                                val code = raw.substring(i, i + 4).toIntOrNull(16)
                                    ?: throw yamlError("Invalid hex in \\u escape at line $lineNum")
                                i += 4
                                sb.append(code.toChar())
                            }
                            else -> throw yamlError("Invalid escape '\\$esc' at line $lineNum")
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw yamlError("Unterminated double-quoted string at line $lineNum")
        }

        private fun parseSingleQuotedScalar(raw: String, lineNum: Int): Pair<String, String> {
            val sb = StringBuilder()
            var i = 1
            while (i < raw.length) {
                val ch = raw[i++]
                if (ch == '\'') {
                    if (i < raw.length && raw[i] == '\'') {
                        sb.append('\'')
                        i++
                    } else {
                        return sb.toString() to raw.substring(i)
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw yamlError("Unterminated single-quoted string at line $lineNum")
        }

        private fun evaluateUnquotedScalar(raw: String): Any? {
            return when (raw) {
                "", "null", "Null", "NULL", "~" -> null
                "true", "True", "TRUE" -> true
                "false", "False", "FALSE" -> false
                else -> {
                    if (INT_REGEX.matches(raw)) {
                        raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
                    } else if (FLOAT_REGEX.matches(raw)) {
                        raw.toDoubleOrNull() ?: raw
                    } else {
                        raw
                    }
                }
            }
        }

        private fun yamlError(msg: String): HarborException {
            return HarborException(HarborErrorCode.DEFINITION_INVALID, msg)
        }

        companion object {
            private val INT_REGEX = Regex("""^-?(?:0|[1-9][0-9]*)$""")
            private val FLOAT_REGEX = Regex("""^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$""")
        }
    }
}
