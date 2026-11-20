package com.harbor.tv.engine.definition

import com.harbor.tv.core.model.HarborErrorCode

/**
 * Validates structural schema rules, required fields, enums, and Section 22.4 execution budgets.
 */
class SchemaValidator {

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = "1.0"
        const val MAX_CATALOG_RESPONSE_BYTES = 2_097_152L // 2 MiB
        const val MAX_ITEMS_PER_PAGE = 100
        const val MAX_PLAYBACK_CANDIDATES = 20
        const val MAX_REDIRECT_HOPS = 3
        const val MAX_PLAYBACK_STEPS = 3
        const val MAX_OPERATION_TIMEOUT_MS = 30_000
        const val MAX_CONNECT_OR_READ_TIMEOUT_MS = 30_000
        const val MAX_CONCURRENT_REQUESTS = 10
        const val MAX_PAGINATION_PAGES = 50
        const val MAX_EXPIRED_RETRY_ATTEMPTS = 3

        private val VALID_RESPONSE_TYPES = setOf("html", "json")
        private val VALID_EMBEDDED_JSON_READS = setOf("data", "text")
        private val VALID_HTML_READS = setOf("text", "ownText", "attribute", "data", "html")
        private val VALID_CARDINALITIES = setOf("one", "many")
        private val VALID_PAGINATION_MODES = setOf("nextLink", "pageQuery", "offsetLimit", "jsonCursor")
        private val VALID_PLAYBACK_TYPES = setOf("hls", "dash", "progressive")
        private val VALID_ON_EXPIRED_ACTIONS = setOf("resolveAgain", "none", "fail")
        private val VALID_FIXTURE_OPERATIONS = setOf("categories", "listing", "search", "details", "playback")
    }

    fun validate(definition: SourceDefinition): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        validateVersion(definition, issues)
        validateSourceIdentity(definition.source, issues)
        validateNetwork(definition.network, issues)
        validateAuthentication(definition.authentication, issues)
        validateTaxonomy(definition.taxonomy, issues)
        validateOperations(definition.operations, issues)
        definition.playback?.let { validatePlaybackPolicy(it, issues) }
        validateFixtures(definition.fixtures, issues)
        definition.settingsReference?.let { validateSettingsReference(it, issues) }

        return issues
    }

    private fun validateVersion(definition: SourceDefinition, issues: MutableList<ValidationIssue>) {
        if (definition.schemaVersion.isBlank()) {
            issues.add(
                ValidationIssue(
                    stage = "schema",
                    code = HarborErrorCode.DEFINITION_INVALID,
                    path = "$.schemaVersion",
                    message = "schemaVersion must not be blank"
                )
            )
        } else if (definition.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            issues.add(
                ValidationIssue(
                    stage = "schema",
                    code = HarborErrorCode.UNSUPPORTED_SCHEMA,
                    path = "$.schemaVersion",
                    message = "Unsupported schemaVersion '${definition.schemaVersion}'; expected '$SUPPORTED_SCHEMA_VERSION'"
                )
            )
        }
    }

    private fun validateSourceIdentity(source: SourceIdentity, issues: MutableList<ValidationIssue>) {
        if (source.id.isBlank()) {
            issues.add(error("$.source.id", "source.id must not be blank"))
        }
        if (source.name.isBlank()) {
            issues.add(error("$.source.name", "source.name must not be blank"))
        }
        if (source.version.isBlank()) {
            issues.add(error("$.source.version", "source.version must not be blank"))
        }
        if (source.baseUrl.isBlank()) {
            issues.add(error("$.source.baseUrl", "source.baseUrl must not be blank"))
        }
        source.permission?.let { perm ->
            if (perm.basis.isBlank()) {
                issues.add(error("$.source.permission.basis", "source.permission.basis must not be blank"))
            }
        }
    }

    private fun validateNetwork(network: NetworkConfig, issues: MutableList<ValidationIssue>) {
        if (network.allowedHosts.catalog.isEmpty()) {
            issues.add(
                error(
                    "$.network.allowedHosts.catalog",
                    "At least one catalog host must be declared in network.allowedHosts.catalog"
                )
            )
        }
        if (network.redirects.maxHops !in 0..MAX_REDIRECT_HOPS) {
            issues.add(
                error(
                    "$.network.redirects.maxHops",
                    "redirects.maxHops (${network.redirects.maxHops}) must be within 0..$MAX_REDIRECT_HOPS"
                )
            )
        }
        if (network.timeouts.connectMs !in 1..MAX_CONNECT_OR_READ_TIMEOUT_MS) {
            issues.add(
                error(
                    "$.network.timeouts.connectMs",
                    "timeouts.connectMs (${network.timeouts.connectMs}) must be within 1..$MAX_CONNECT_OR_READ_TIMEOUT_MS"
                )
            )
        }
        if (network.timeouts.readMs !in 1..MAX_CONNECT_OR_READ_TIMEOUT_MS) {
            issues.add(
                error(
                    "$.network.timeouts.readMs",
                    "timeouts.readMs (${network.timeouts.readMs}) must be within 1..$MAX_CONNECT_OR_READ_TIMEOUT_MS"
                )
            )
        }
        if (network.timeouts.operationMs !in 1..MAX_OPERATION_TIMEOUT_MS) {
            issues.add(
                error(
                    "$.network.timeouts.operationMs",
                    "timeouts.operationMs (${network.timeouts.operationMs}) must be within 1..$MAX_OPERATION_TIMEOUT_MS"
                )
            )
        }
        if (network.limits.maxConcurrentRequests !in 1..MAX_CONCURRENT_REQUESTS) {
            issues.add(
                error(
                    "$.network.limits.maxConcurrentRequests",
                    "limits.maxConcurrentRequests (${network.limits.maxConcurrentRequests}) must be within 1..$MAX_CONCURRENT_REQUESTS"
                )
            )
        }
        if (network.limits.minRequestIntervalMs < 0L) {
            issues.add(
                error(
                    "$.network.limits.minRequestIntervalMs",
                    "limits.minRequestIntervalMs must be >= 0"
                )
            )
        }
        if (network.limits.maxCatalogResponseBytes !in 1L..MAX_CATALOG_RESPONSE_BYTES) {
            issues.add(
                error(
                    "$.network.limits.maxCatalogResponseBytes",
                    "limits.maxCatalogResponseBytes (${network.limits.maxCatalogResponseBytes}) must be within 1..$MAX_CATALOG_RESPONSE_BYTES"
                )
            )
        }
        if (network.limits.maxItemsPerPage !in 1..MAX_ITEMS_PER_PAGE) {
            issues.add(
                error(
                    "$.network.limits.maxItemsPerPage",
                    "limits.maxItemsPerPage (${network.limits.maxItemsPerPage}) must be within 1..$MAX_ITEMS_PER_PAGE"
                )
            )
        }
    }

    private fun validateAuthentication(auth: AuthConfig, issues: MutableList<ValidationIssue>) {
        if (auth.type.isBlank()) {
            issues.add(error("$.authentication.type", "authentication.type must not be blank"))
        } else if (auth.type != "none") {
            issues.add(
                ValidationIssue(
                    stage = "schema",
                    code = HarborErrorCode.UNSUPPORTED_CAPABILITY,
                    path = "$.authentication.type",
                    message = "Unsupported authentication type '${auth.type}'; MVP supports 'none' only"
                )
            )
        }
    }

    private fun validateTaxonomy(taxonomy: TaxonomyConfig?, issues: MutableList<ValidationIssue>) {
        if (taxonomy == null) return
        if (taxonomy.namespace.isBlank()) {
            issues.add(error("$.taxonomy.namespace", "taxonomy.namespace must not be blank"))
        }
        if (taxonomy.mappingVersion.isBlank()) {
            issues.add(error("$.taxonomy.mappingVersion", "taxonomy.mappingVersion must not be blank"))
        }
    }

    private fun validateOperations(ops: OperationsConfig, issues: MutableList<ValidationIssue>) {
        ops.categories?.let { validateCatalogOp(it, "$.operations.categories", issues) }
        ops.listing?.let { validateCatalogOp(it, "$.operations.listing", issues) }
        ops.search?.let { validateCatalogOp(it, "$.operations.search", issues) }
        ops.details?.let { validateDetailsOp(it, "$.operations.details", issues) }
        ops.playback?.let { validatePlaybackOp(it, "$.operations.playback", issues) }
    }

    private fun validateCatalogOp(
        op: CatalogOperationDef,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        validateRequest(op.request, "$path.request", issues)
        validateResponse(op.response, "$path.response", issues)
        op.document?.let { validateDocument(it, op.response, "$path.document", issues) }

        if (op.extract == null && op.reuse?.extractFrom.isNullOrBlank()) {
            issues.add(error(path, "Catalog operation must define either 'extract' or 'reuse.extractFrom'"))
        }
        op.extract?.let { extract ->
            if (extract.items.limit !in 1..MAX_ITEMS_PER_PAGE) {
                issues.add(
                    error(
                        "$path.extract.items.limit",
                        "extract.items.limit (${extract.items.limit}) must be within 1..$MAX_ITEMS_PER_PAGE"
                    )
                )
            }
            if (extract.items.fields.isEmpty()) {
                issues.add(error("$path.extract.items.fields", "extract.items.fields must not be empty"))
            }
            validateFieldsMap(extract.items.fields, "$path.extract.items.fields", issues)
        }
        op.pagination?.let { pagination ->
            if (pagination.mode !in VALID_PAGINATION_MODES) {
                issues.add(
                    ValidationIssue(
                        stage = "schema",
                        code = HarborErrorCode.UNSUPPORTED_CAPABILITY,
                        path = "$path.pagination.mode",
                        message = "Unsupported pagination mode '${pagination.mode}'"
                    )
                )
            }
            when (pagination.mode) {
                "nextLink" -> {
                    if (pagination.next == null) {
                        issues.add(error("$path.pagination.next", "pagination.next is required for mode 'nextLink'"))
                    } else {
                        validateFieldExtraction(pagination.next, "$path.pagination.next", issues)
                    }
                }
                "pageQuery" -> {
                    if (pagination.pageParam.isNullOrBlank()) {
                        issues.add(error("$path.pagination.pageParam", "pagination.pageParam is required for mode 'pageQuery'"))
                    }
                }
                "jsonCursor" -> {
                    if (pagination.cursorPointer.isNullOrBlank()) {
                        issues.add(error("$path.pagination.cursorPointer", "pagination.cursorPointer is required for mode 'jsonCursor'"))
                    }
                }
            }
            if (pagination.maxPages !in 1..MAX_PAGINATION_PAGES) {
                issues.add(
                    error(
                        "$path.pagination.maxPages",
                        "pagination.maxPages (${pagination.maxPages}) must be within 1..$MAX_PAGINATION_PAGES"
                    )
                )
            }
        }
    }

    private fun validateDetailsOp(
        op: DetailsOperationDef,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        validateRequest(op.request, "$path.request", issues)
        validateResponse(op.response, "$path.response", issues)
        op.document?.let { validateDocument(it, op.response, "$path.document", issues) }

        if (op.extract == null && op.reuse?.extractFrom.isNullOrBlank()) {
            issues.add(error(path, "Details operation must define either 'extract' or 'reuse.extractFrom'"))
        }
        op.extract?.let { extract ->
            if (extract.fields.isEmpty()) {
                issues.add(error("$path.extract.fields", "details extract.fields must not be empty"))
            }
            validateFieldsMap(extract.fields, "$path.extract.fields", issues)
        }
    }

    private fun validatePlaybackOp(
        op: PlaybackOperationDef,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        if (op.steps.isEmpty()) {
            issues.add(error("$path.steps", "playback.steps must contain at least one step"))
        } else if (op.steps.size > MAX_PLAYBACK_STEPS) {
            issues.add(
                error(
                    "$path.steps",
                    "playback.steps (${op.steps.size}) exceeds maximum allowed steps ($MAX_PLAYBACK_STEPS)"
                )
            )
        }

        op.steps.forEachIndexed { idx, step ->
            val stepPath = "$path.steps[$idx]"
            if (step.id.isBlank()) {
                issues.add(error("$stepPath.id", "playback step id must not be blank"))
            }
            validateRequest(step.request, "$stepPath.request", issues)
            validateResponse(step.response, "$stepPath.response", issues)
            step.document?.let { validateDocument(it, step.response, "$stepPath.document", issues) }

            val candidates = step.extract.candidates
            if (candidates == null && step.extract.fields.isEmpty()) {
                issues.add(
                    error(
                        "$stepPath.extract",
                        "playback step extract must define 'candidates' and/or 'fields'"
                    )
                )
            }
            if (candidates != null) {
                if (candidates.limit !in 1..MAX_PLAYBACK_CANDIDATES) {
                    issues.add(
                        error(
                            "$stepPath.extract.candidates.limit",
                            "candidates.limit (${candidates.limit}) must be within 1..$MAX_PLAYBACK_CANDIDATES"
                        )
                    )
                }
                if (candidates.fields.isEmpty()) {
                    issues.add(
                        error("$stepPath.extract.candidates.fields", "candidates.fields must not be empty")
                    )
                }
                validateFieldsMap(candidates.fields, "$stepPath.extract.candidates.fields", issues)
            }
            validateFieldsMap(step.extract.fields, "$stepPath.extract.fields", issues)
        }

        if (op.result.candidatesFrom.isBlank()) {
            issues.add(error("$path.result.candidatesFrom", "playback.result.candidatesFrom must not be blank"))
        }
    }

    private fun validateRequest(
        request: RequestDef,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        if (request.method.isBlank()) {
            issues.add(error("$path.method", "request.method must not be blank"))
        } else if (request.method.uppercase() != "GET") {
            issues.add(
                ValidationIssue(
                    stage = "schema",
                    code = HarborErrorCode.UNSUPPORTED_CAPABILITY,
                    path = "$path.method",
                    message = "Unsupported HTTP method '${request.method}'; MVP supports GET only"
                )
            )
        }
        if (request.url.isBlank()) {
            issues.add(error("$path.url", "request.url must not be blank"))
        }
    }

    private fun validateResponse(
        response: ResponseDef,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        if (response.type !in VALID_RESPONSE_TYPES) {
            issues.add(
                error(
                    "$path.type",
                    "Invalid response.type '${response.type}'; expected one of $VALID_RESPONSE_TYPES"
                )
            )
        }
    }

    private fun validateDocument(
        document: DocumentDef,
        response: ResponseDef,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        document.embeddedJson?.let { ej ->
            if (response.type != "html") {
                issues.add(
                    error(
                        "$path.embeddedJson",
                        "document.embeddedJson is only valid when response.type is 'html'"
                    )
                )
            }
            if (ej.selector.isBlank()) {
                issues.add(error("$path.embeddedJson.selector", "embeddedJson.selector must not be blank"))
            }
            if (ej.read !in VALID_EMBEDDED_JSON_READS) {
                issues.add(
                    error(
                        "$path.embeddedJson.read",
                        "Invalid embeddedJson.read '${ej.read}'; expected one of $VALID_EMBEDDED_JSON_READS"
                    )
                )
            }
        }
    }

    private fun validateFieldsMap(
        fields: Map<String, FieldExtractionDef>,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        for ((name, def) in fields) {
            if (name.isBlank()) {
                issues.add(error(path, "Field name must not be blank"))
            }
            validateFieldExtraction(def, "$path.$name", issues)
        }
    }

    private fun validateFieldExtraction(
        field: FieldExtractionDef,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        field.read?.let { readOp ->
            if (readOp !in VALID_HTML_READS) {
                issues.add(
                    error(
                        "$path.read",
                        "Invalid read operation '$readOp'; expected one of $VALID_HTML_READS"
                    )
                )
            }
            if (readOp == "attribute" && field.attribute.isNullOrBlank()) {
                issues.add(
                    error(
                        "$path.attribute",
                        "attribute name is required when read is 'attribute'"
                    )
                )
            }
        }
        if (field.cardinality !in VALID_CARDINALITIES) {
            issues.add(
                error(
                    "$path.cardinality",
                    "Invalid cardinality '${field.cardinality}'; expected one of $VALID_CARDINALITIES"
                )
            )
        }
        field.fallback?.let { validateFieldExtraction(it, "$path.fallback", issues) }
    }

    private fun validatePlaybackPolicy(
        policy: PlaybackPolicyConfig,
        issues: MutableList<ValidationIssue>
    ) {
        if (policy.allowedTypes.isEmpty()) {
            issues.add(error("$.playback.allowedTypes", "playback.allowedTypes must not be empty"))
        } else {
            policy.allowedTypes.forEachIndexed { idx, type ->
                if (type !in VALID_PLAYBACK_TYPES) {
                    issues.add(
                        error(
                            "$.playback.allowedTypes[$idx]",
                            "Invalid playback type '$type'; expected one of $VALID_PLAYBACK_TYPES"
                        )
                    )
                }
            }
        }
        if (policy.onExpired.action !in VALID_ON_EXPIRED_ACTIONS) {
            issues.add(
                error(
                    "$.playback.onExpired.action",
                    "Invalid onExpired.action '${policy.onExpired.action}'; expected one of $VALID_ON_EXPIRED_ACTIONS"
                )
            )
        }
        if (policy.onExpired.maxAttempts !in 0..MAX_EXPIRED_RETRY_ATTEMPTS) {
            issues.add(
                error(
                    "$.playback.onExpired.maxAttempts",
                    "onExpired.maxAttempts (${policy.onExpired.maxAttempts}) must be within 0..$MAX_EXPIRED_RETRY_ATTEMPTS"
                )
            )
        }
    }

    private fun validateFixtures(
        fixtures: List<FixtureDefinition>,
        issues: MutableList<ValidationIssue>
    ) {
        fixtures.forEachIndexed { idx, fixture ->
            val path = "$.fixtures[$idx]"
            if (fixture.id.isBlank()) {
                issues.add(error("$path.id", "fixture id must not be blank"))
            }
            if (fixture.operation !in VALID_FIXTURE_OPERATIONS) {
                issues.add(
                    error(
                        "$path.operation",
                        "Invalid fixture operation '${fixture.operation}'; expected one of $VALID_FIXTURE_OPERATIONS"
                    )
                )
            }
            if (fixture.responseFile.isBlank() && fixture.stepFiles.isEmpty()) {
                issues.add(error("$path.responseFile", "fixture responseFile or stepFiles must not be blank"))
            }
            fixture.expected.minimumItems?.let { min ->
                if (min < 0) issues.add(error("$path.expected.minimumItems", "minimumItems must be >= 0"))
            }
            fixture.expected.minimumCandidates?.let { min ->
                if (min < 0) issues.add(error("$path.expected.minimumCandidates", "minimumCandidates must be >= 0"))
            }
        }
    }

    private fun validateSettingsReference(
        ref: SettingsReferenceConfig,
        issues: MutableList<ValidationIssue>
    ) {
        if (ref.provider.isBlank()) {
            issues.add(error("$.settingsReference.provider", "settingsReference.provider must not be blank"))
        }
        if (ref.repository.isBlank()) {
            issues.add(error("$.settingsReference.repository", "settingsReference.repository must not be blank"))
        }
        if (ref.pinnedCommit.isBlank()) {
            issues.add(error("$.settingsReference.pinnedCommit", "settingsReference.pinnedCommit must not be blank"))
        }
        if (ref.license.isBlank()) {
            issues.add(error("$.settingsReference.license", "settingsReference.license must not be blank"))
        }
    }

    private fun error(path: String, message: String): ValidationIssue {
        return ValidationIssue(
            stage = "schema",
            code = HarborErrorCode.DEFINITION_INVALID,
            path = path,
            message = message
        )
    }
}
