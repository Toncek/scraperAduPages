package com.harbor.tv.engine.definition

import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.security.AddressSafetyValidator
import org.jsoup.Jsoup
import org.jsoup.select.Selector
import java.net.InetAddress
import java.net.URI

/**
 * Validates cross-field semantic invariants, capability consistency, host declarations,
 * operation reuse graphs, transforms, selectors, JSON pointers, interpolation variables,
 * and header/credential security policies.
 */
class SemanticValidator {

    companion object {
        const val MAX_TRANSFORM_CHAIN_LENGTH = 10

        val ALLOWED_TRANSFORMS = setOf(
            "trim",
            "collapseWhitespace",
            "lowercase",
            "uppercase",
            "resolveUrl",
            "canonicalUrl",
            "parseInteger",
            "parseDecimal",
            "parseDuration",
            "split",
            "mapValues",
            "deduplicate",
            "urlEncode",
            "template",
            "regexExtract",
            "regexExtractAll",
            "unescapeJson",
            "hexChunksToBase36"
        )

        val FORBIDDEN_HEADERS = setOf(
            "host",
            "content-length",
            "connection",
            "proxy-authorization",
            "cookie",
            "authorization"
        )

        private val SECRET_QUERY_KEYS = setOf(
            "password", "passwd", "pwd", "secret", "token", "access_token",
            "refresh_token", "id_token", "api_key", "apikey", "session",
            "session_id", "sessionid", "sid", "cookie"
        )

        private val BEARER_OR_BASIC_REGEX = Regex("""(?i)\b(Bearer|Basic)\s+[A-Za-z0-9\-._~+/]+=*""")
        private val INTERPOLATION_REGEX = Regex("""\$\{([^}]*)\}""")
        private val VALID_VAR_NAME_REGEX = Regex("""^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$""")
        private val IPV4_LITERAL_REGEX = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")
    }

    fun validate(definition: SourceDefinition): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        validateCapabilities(definition, issues)
        validateHostDeclarations(definition, issues)
        validateNetworkAndHeaders(definition, issues)
        validateReuseGraphs(definition.operations, issues)
        validateOperationsSemantics(definition, issues)

        return issues
    }

    // -------------------------------------------------------------------------
    // Capability consistency
    // -------------------------------------------------------------------------

    private fun validateCapabilities(
        definition: SourceDefinition,
        issues: MutableList<ValidationIssue>
    ) {
        val caps = definition.source.capabilities
        val ops = definition.operations

        if (caps.playback && ops.playback == null) {
            issues.add(
                issue(
                    HarborErrorCode.DEFINITION_INVALID,
                    "$.operations.playback",
                    "Capability 'playback' is true, but 'operations.playback' is missing"
                )
            )
        }
        if (caps.categories && ops.categories == null) {
            issues.add(
                issue(
                    HarborErrorCode.DEFINITION_INVALID,
                    "$.operations.categories",
                    "Capability 'categories' is true, but 'operations.categories' is missing"
                )
            )
        }
        if (caps.search && ops.search == null) {
            issues.add(
                issue(
                    HarborErrorCode.DEFINITION_INVALID,
                    "$.operations.search",
                    "Capability 'search' is true, but 'operations.search' is missing"
                )
            )
        }
        if (caps.details && ops.details == null) {
            issues.add(
                issue(
                    HarborErrorCode.DEFINITION_INVALID,
                    "$.operations.details",
                    "Capability 'details' is true, but 'operations.details' is missing"
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Host declarations and URL safety
    // -------------------------------------------------------------------------

    private fun validateHostDeclarations(
        definition: SourceDefinition,
        issues: MutableList<ValidationIssue>
    ) {
        val allowedHosts = definition.network.allowedHosts
        val allowLan = definition.network.allowLan

        validateHostGroup(allowedHosts.catalog, "$.network.allowedHosts.catalog", allowLan, issues)
        validateHostGroup(allowedHosts.images, "$.network.allowedHosts.images", allowLan, issues)
        validateHostGroup(allowedHosts.media, "$.network.allowedHosts.media", allowLan, issues)
        validateHostGroup(allowedHosts.authentication, "$.network.allowedHosts.authentication", allowLan, issues)

        val catalogHostSet = allowedHosts.catalog.map { it.trim().lowercase() }.toSet()
        val baseUri = parseHttpUri(
            rawUrl = definition.source.baseUrl,
            httpsOnly = definition.network.httpsOnly,
            path = "$.source.baseUrl",
            issues = issues
        )
        if (baseUri != null) {
            val baseHost = baseUri.host.lowercase()
            if (!isHostAllowed(baseHost, catalogHostSet)) {
                issues.add(
                    issue(
                        HarborErrorCode.DESTINATION_BLOCKED,
                        "$.source.baseUrl",
                        "baseUrl host '$baseHost' is not declared in network.allowedHosts.catalog"
                    )
                )
            }
        }
    }

    private fun isHostAllowed(host: String, allowedHosts: Set<String>): Boolean {
        val normalized = host.trim().lowercase()
        for (entry in allowedHosts) {
            if (entry.startsWith("*.")) {
                val domain = entry.removePrefix("*.")
                if (domain.isNotEmpty() && (normalized == domain || normalized.endsWith(".$domain"))) {
                    return true
                }
            } else if (normalized == entry) {
                return true
            }
        }
        return false
    }

    private fun validateHostGroup(
        hosts: List<String>,
        path: String,
        allowLan: Boolean,
        issues: MutableList<ValidationIssue>
    ) {
        hosts.forEachIndexed { idx, rawHost ->
            val host = rawHost.trim()
            val entryPath = "$path[$idx]"
            if (host.isEmpty()) {
                issues.add(issue(HarborErrorCode.DEFINITION_INVALID, entryPath, "Host entry must not be blank"))
                return@forEachIndexed
            }
            val effectiveHost = if (host.startsWith("*.")) {
                val domain = host.removePrefix("*.")
                if (domain.isEmpty() || domain.contains('*') || !domain.contains('.')) {
                    issues.add(
                        issue(
                            HarborErrorCode.DESTINATION_BLOCKED,
                            entryPath,
                            "Invalid wildcard host pattern '$host'; expected '*.domain.tld'"
                        )
                    )
                    return@forEachIndexed
                }
                domain
            } else if (host.contains('*')) {
                issues.add(
                    issue(
                        HarborErrorCode.DESTINATION_BLOCKED,
                        entryPath,
                        "Wildcard hostnames ('$host') are forbidden unless explicit '*.domain.tld'"
                    )
                )
                return@forEachIndexed
            } else {
                host
            }
            if (effectiveHost.contains("://") || effectiveHost.contains('/') || effectiveHost.contains('?') ||
                effectiveHost.contains('#') || effectiveHost.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }
            ) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        entryPath,
                        "Invalid hostname '$host': must be a bare hostname without scheme, path, or whitespace"
                    )
                )
                return@forEachIndexed
            }
            // Reject embedded port in non-IPv6 hostname
            if (!effectiveHost.startsWith("[") && effectiveHost.count { it == ':' } == 1) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        entryPath,
                        "Invalid hostname '$host': ports must not be included in allowedHosts"
                    )
                )
                return@forEachIndexed
            }
            validateLiteralAddressSafety(effectiveHost, allowLan, entryPath, issues)
        }
    }

    private fun validateLiteralAddressSafety(
        host: String,
        allowLan: Boolean,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        val cleanHost = host.removePrefix("[").removeSuffix("]").lowercase()
        if (!allowLan && (cleanHost == "localhost" || cleanHost.endsWith(".localhost") || cleanHost.endsWith(".local"))) {
            issues.add(
                issue(
                    HarborErrorCode.DESTINATION_BLOCKED,
                    path,
                    "Local hostname '$host' is blocked unless allowLan is true"
                )
            )
            return
        }
        val isIpLiteral = IPV4_LITERAL_REGEX.matches(cleanHost) || cleanHost.contains(':')
        if (isIpLiteral) {
            val resolved = runCatching { InetAddress.getAllByName(cleanHost).toList() }.getOrNull()
            if (resolved == null || !AddressSafetyValidator.isSafePublicAddress(cleanHost, resolved, allowLan)) {
                issues.add(
                    issue(
                        HarborErrorCode.DESTINATION_BLOCKED,
                        path,
                        "IP address host '$host' is not permitted for ordinary internet sources"
                    )
                )
            }
        }
    }

    private fun parseHttpUri(
        rawUrl: String,
        httpsOnly: Boolean,
        path: String,
        issues: MutableList<ValidationIssue>
    ): URI? {
        if (hasControlChars(rawUrl)) {
            issues.add(issue(HarborErrorCode.DEFINITION_INVALID, path, "URL contains control characters"))
            return null
        }
        val uri = try {
            URI(rawUrl)
        } catch (e: Exception) {
            issues.add(issue(HarborErrorCode.DEFINITION_INVALID, path, "Malformed URL '$rawUrl'"))
            return null
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            issues.add(
                issue(
                    HarborErrorCode.DESTINATION_BLOCKED,
                    path,
                    "Unsupported URL scheme '${uri.scheme}'; only http/https are permitted"
                )
            )
            return null
        }
        if (httpsOnly && scheme != "https") {
            issues.add(
                issue(
                    HarborErrorCode.DESTINATION_BLOCKED,
                    path,
                    "HTTPS is required by network.httpsOnly, but URL uses 'http'"
                )
            )
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            issues.add(
                issue(
                    HarborErrorCode.POLICY_BLOCKED,
                    path,
                    "Embedded user credentials in URL are forbidden"
                )
            )
        }
        if (uri.host.isNullOrBlank()) {
            issues.add(issue(HarborErrorCode.DEFINITION_INVALID, path, "URL '$rawUrl' is missing a host"))
            return null
        }
        return uri
    }

    // -------------------------------------------------------------------------
    // Headers and secret rejection
    // -------------------------------------------------------------------------

    private fun validateNetworkAndHeaders(
        definition: SourceDefinition,
        issues: MutableList<ValidationIssue>
    ) {
        validateHeadersMap(definition.network.headers, "$.network.headers", issues)
    }

    private fun validateHeadersMap(
        headers: Map<String, String>,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        for ((name, value) in headers) {
            val headerPath = "$path.$name"
            if (name.isBlank() || hasControlChars(name) || name.any { it.isWhitespace() }) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        headerPath,
                        "Invalid HTTP header name '$name'"
                    )
                )
            }
            if (name.trim().lowercase() in FORBIDDEN_HEADERS) {
                issues.add(
                    issue(
                        HarborErrorCode.POLICY_BLOCKED,
                        headerPath,
                        "Header '$name' is forbidden in source definitions"
                    )
                )
            }
            if (hasControlChars(value)) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        headerPath,
                        "Header '$name' value contains forbidden control characters"
                    )
                )
            }
            if (containsPlaintextSecret(value)) {
                issues.add(
                    issue(
                        HarborErrorCode.POLICY_BLOCKED,
                        headerPath,
                        "Header '$name' appears to contain an embedded credential or token"
                    )
                )
            }
        }
    }

    private fun containsPlaintextSecret(value: String): Boolean {
        return BEARER_OR_BASIC_REGEX.containsMatchIn(value)
    }

    // -------------------------------------------------------------------------
    // Reuse resolution and cycle detection
    // -------------------------------------------------------------------------

    private fun validateReuseGraphs(
        ops: OperationsConfig,
        issues: MutableList<ValidationIssue>
    ) {
        val opMap = linkedMapOf<String, Any?>()
        ops.categories?.let { opMap["categories"] = it }
        ops.listing?.let { opMap["listing"] = it }
        ops.search?.let { opMap["search"] = it }
        ops.details?.let { opMap["details"] = it }

        // Check extractFrom reuse
        for ((opName, opObj) in opMap) {
            val reuse = when (opObj) {
                is CatalogOperationDef -> opObj.reuse
                is DetailsOperationDef -> opObj.reuse
                else -> null
            } ?: continue

            reuse.extractFrom?.let { target ->
                resolveExtractChain(opName, target, opMap, issues)
            }
            reuse.paginationFrom?.let { target ->
                if (opObj is DetailsOperationDef) {
                    issues.add(
                        issue(
                            HarborErrorCode.DEFINITION_INVALID,
                            "$.operations.$opName.reuse.paginationFrom",
                            "Details operation cannot reuse pagination"
                        )
                    )
                } else {
                    resolvePaginationChain(opName, target, opMap, issues)
                }
            }
        }
    }

    private fun resolveExtractChain(
        sourceOp: String,
        initialTarget: String,
        opMap: Map<String, Any?>,
        issues: MutableList<ValidationIssue>
    ) {
        val path = "$.operations.$sourceOp.reuse.extractFrom"
        val sourceObj = opMap[sourceOp]
        val visited = linkedSetOf(sourceOp)
        var current: String? = initialTarget

        while (current != null) {
            if (current in visited) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "Cyclic reuse.extractFrom detected involving '$current'"
                    )
                )
                return
            }
            val targetObj = opMap[current]
            if (targetObj == null) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "reuse.extractFrom references missing or undefined operation '$current'"
                    )
                )
                return
            }

            val sourceIsDetails = sourceObj is DetailsOperationDef
            val targetIsDetails = targetObj is DetailsOperationDef
            if (sourceIsDetails != targetIsDetails) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "Incompatible reuse.extractFrom: '$sourceOp' cannot reuse extraction from '$current'"
                    )
                )
                return
            }

            visited.add(current)

            when (targetObj) {
                is CatalogOperationDef -> {
                    if (targetObj.extract != null) {
                        val srcCatalog = sourceObj as CatalogOperationDef
                        val srcJsonMode = srcCatalog.response.type == "json" || srcCatalog.document?.embeddedJson != null
                        val tgtJsonMode = targetObj.response.type == "json" || targetObj.document?.embeddedJson != null
                        if (srcJsonMode != tgtJsonMode) {
                            issues.add(
                                issue(
                                    HarborErrorCode.DEFINITION_INVALID,
                                    path,
                                    "Incompatible reuse.extractFrom: '$sourceOp' and '$current' have mismatched HTML/JSON extraction modes"
                                )
                            )
                        }
                        return
                    }
                    current = targetObj.reuse?.extractFrom
                }
                is DetailsOperationDef -> {
                    if (targetObj.extract != null) {
                        val srcDetails = sourceObj as DetailsOperationDef
                        val srcJsonMode = srcDetails.response.type == "json" || srcDetails.document?.embeddedJson != null
                        val tgtJsonMode = targetObj.response.type == "json" || targetObj.document?.embeddedJson != null
                        if (srcJsonMode != tgtJsonMode) {
                            issues.add(
                                issue(
                                    HarborErrorCode.DEFINITION_INVALID,
                                    path,
                                    "Incompatible reuse.extractFrom: '$sourceOp' and '$current' have mismatched HTML/JSON extraction modes"
                                )
                            )
                        }
                        return
                    }
                    current = targetObj.reuse?.extractFrom
                }
                else -> return
            }
        }

        issues.add(
            issue(
                HarborErrorCode.DEFINITION_INVALID,
                path,
                "reuse.extractFrom target chain for '$sourceOp' does not resolve to an extract definition"
            )
        )
    }

    private fun resolvePaginationChain(
        sourceOp: String,
        initialTarget: String,
        opMap: Map<String, Any?>,
        issues: MutableList<ValidationIssue>
    ) {
        val path = "$.operations.$sourceOp.reuse.paginationFrom"
        val visited = linkedSetOf(sourceOp)
        var current: String? = initialTarget

        while (current != null) {
            if (current in visited) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "Cyclic reuse.paginationFrom detected involving '$current'"
                    )
                )
                return
            }
            val targetObj = opMap[current]
            if (targetObj == null) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "reuse.paginationFrom references missing or undefined operation '$current'"
                    )
                )
                return
            }
            if (targetObj !is CatalogOperationDef) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "Incompatible reuse.paginationFrom: '$current' does not support pagination"
                    )
                )
                return
            }
            visited.add(current)
            if (targetObj.pagination != null) {
                return
            }
            current = targetObj.reuse?.paginationFrom
        }

        issues.add(
            issue(
                HarborErrorCode.DEFINITION_INVALID,
                path,
                "reuse.paginationFrom target '$initialTarget' does not define pagination"
            )
        )
    }

    // -------------------------------------------------------------------------
    // Per-operation semantic validation (URLs, interpolation, selectors, pointers, transforms)
    // -------------------------------------------------------------------------

    private fun validateOperationsSemantics(
        definition: SourceDefinition,
        issues: MutableList<ValidationIssue>
    ) {
        val ops = definition.operations

        ops.categories?.let { op ->
            validateCatalogOperationSemantics(
                definition = definition,
                op = op,
                opName = "categories",
                allowedVars = setOf("source.baseUrl"),
                path = "$.operations.categories",
                issues = issues
            )
        }
        ops.listing?.let { op ->
            validateCatalogOperationSemantics(
                definition = definition,
                op = op,
                opName = "listing",
                allowedVars = setOf("source.baseUrl", "input.url"),
                path = "$.operations.listing",
                issues = issues
            )
        }
        ops.search?.let { op ->
            validateCatalogOperationSemantics(
                definition = definition,
                op = op,
                opName = "search",
                allowedVars = setOf("source.baseUrl", "input.query"),
                path = "$.operations.search",
                issues = issues
            )
        }
        ops.details?.let { op ->
            validateDetailsOperationSemantics(
                definition = definition,
                op = op,
                allowedVars = setOf("source.baseUrl", "input.detailUrl"),
                path = "$.operations.details",
                issues = issues
            )
        }
        ops.playback?.let { op ->
            validatePlaybackOperationSemantics(definition, op, "$.operations.playback", issues)
        }
    }

    private fun validateCatalogOperationSemantics(
        definition: SourceDefinition,
        op: CatalogOperationDef,
        opName: String,
        allowedVars: Set<String>,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        validateRequestSemantics(definition, op.request, allowedVars, "$path.request", issues)
        op.document?.embeddedJson?.let { ej ->
            validateCssSelector(ej.selector, "$path.document.embeddedJson.selector", issues)
        }
        val usesJsonPointer = op.response.type == "json" || op.document?.embeddedJson != null
        op.extract?.let { extract ->
            val items = extract.items
            val itemsPath = "$path.extract.items"
            if (usesJsonPointer) {
                if (items.pointer == null) {
                    issues.add(
                        issue(
                            HarborErrorCode.SELECTOR_INVALID,
                            "$itemsPath.pointer",
                            "$opName JSON extraction requires 'pointer'"
                        )
                    )
                } else {
                    validateJsonPointer(items.pointer, "$itemsPath.pointer", issues)
                }
                if (items.selector != null) {
                    issues.add(
                        issue(
                            HarborErrorCode.DEFINITION_INVALID,
                            "$itemsPath.selector",
                            "HTML CSS 'selector' cannot be used in JSON extraction mode"
                        )
                    )
                }
            } else {
                if (items.selector.isNullOrBlank()) {
                    issues.add(
                        issue(
                            HarborErrorCode.SELECTOR_INVALID,
                            "$itemsPath.selector",
                            "$opName HTML extraction requires 'selector'"
                        )
                    )
                } else {
                    validateCssSelector(items.selector, "$itemsPath.selector", issues)
                }
                if (items.pointer != null) {
                    issues.add(
                        issue(
                            HarborErrorCode.DEFINITION_INVALID,
                            "$itemsPath.pointer",
                            "JSON 'pointer' cannot be used in HTML extraction mode without embeddedJson"
                        )
                    )
                }
            }
            validateFieldsSemantics(items.fields, usesJsonPointer, "$itemsPath.fields", issues)
        }
        op.pagination?.next?.let { nextField ->
            validateFieldSemantics(nextField, usesJsonPointer, "$path.pagination.next", issues)
        }
        op.pagination?.cursorPointer?.let { cursorPointer ->
            validateJsonPointer(cursorPointer, "$path.pagination.cursorPointer", issues)
        }
    }

    private fun validateDetailsOperationSemantics(
        definition: SourceDefinition,
        op: DetailsOperationDef,
        allowedVars: Set<String>,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        validateRequestSemantics(definition, op.request, allowedVars, "$path.request", issues)
        op.document?.embeddedJson?.let { ej ->
            validateCssSelector(ej.selector, "$path.document.embeddedJson.selector", issues)
        }
        val usesJsonPointer = op.response.type == "json" || op.document?.embeddedJson != null
        op.extract?.let { extract ->
            validateFieldsSemantics(extract.fields, usesJsonPointer, "$path.extract.fields", issues)
        }
    }

    private fun validatePlaybackOperationSemantics(
        definition: SourceDefinition,
        op: PlaybackOperationDef,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        val seenStepIds = mutableSetOf<String>()
        val previousOutputs = mutableSetOf<String>()
        val stepCandidatesMap = mutableMapOf<String, Boolean>()

        op.steps.forEachIndexed { idx, step ->
            val stepPath = "$path.steps[$idx]"
            if (step.id.isNotBlank() && !seenStepIds.add(step.id)) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        "$stepPath.id",
                        "Duplicate playback step id '${step.id}'"
                    )
                )
            }

            val allowedVars = buildSet {
                add("source.baseUrl")
                add("input.detailUrl")
                addAll(previousOutputs)
            }
            validateRequestSemantics(definition, step.request, allowedVars, "$stepPath.request", issues)
            step.document?.embeddedJson?.let { ej ->
                validateCssSelector(ej.selector, "$stepPath.document.embeddedJson.selector", issues)
            }

            val usesJsonPointer = step.response.type == "json" || step.document?.embeddedJson != null
            step.extract.candidates?.let { cands ->
                val cPath = "$stepPath.extract.candidates"
                if (usesJsonPointer) {
                    if (cands.pointer == null) {
                        issues.add(
                            issue(
                                HarborErrorCode.SELECTOR_INVALID,
                                "$cPath.pointer",
                                "Playback JSON candidate extraction requires 'pointer'"
                            )
                        )
                    } else {
                        validateJsonPointer(cands.pointer, "$cPath.pointer", issues)
                    }
                    if (cands.selector != null) {
                        issues.add(
                            issue(
                                HarborErrorCode.DEFINITION_INVALID,
                                "$cPath.selector",
                                "HTML CSS 'selector' cannot be used in JSON extraction mode"
                            )
                        )
                    }
                } else {
                    if (cands.selector.isNullOrBlank()) {
                        issues.add(
                            issue(
                                HarborErrorCode.SELECTOR_INVALID,
                                "$cPath.selector",
                                "Playback HTML candidate extraction requires 'selector'"
                            )
                        )
                    } else {
                        validateCssSelector(cands.selector, "$cPath.selector", issues)
                    }
                    if (cands.pointer != null) {
                        issues.add(
                            issue(
                                HarborErrorCode.DEFINITION_INVALID,
                                "$cPath.pointer",
                                "JSON 'pointer' cannot be used in HTML extraction mode without embeddedJson"
                            )
                        )
                    }
                }
                validateFieldsSemantics(cands.fields, usesJsonPointer, "$cPath.fields", issues)
            }
            validateFieldsSemantics(step.extract.fields, usesJsonPointer, "$stepPath.extract.fields", issues)

            if (step.id.isNotBlank()) {
                stepCandidatesMap[step.id] = step.extract.candidates != null
                for (fieldName in step.extract.fields.keys) {
                    previousOutputs.add("${step.id}.$fieldName")
                    previousOutputs.add("steps.${step.id}.$fieldName")
                }
            }
        }

        val candidatesFrom = op.result.candidatesFrom
        if (candidatesFrom.isNotBlank()) {
            val parts = candidatesFrom.split('.')
            if (parts.size != 2 || parts[1] != "candidates") {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        "$path.result.candidatesFrom",
                        "candidatesFrom '$candidatesFrom' must have form '<stepId>.candidates'"
                    )
                )
            } else {
                val targetStepId = parts[0]
                when (stepCandidatesMap[targetStepId]) {
                    null -> issues.add(
                        issue(
                            HarborErrorCode.DEFINITION_INVALID,
                            "$path.result.candidatesFrom",
                            "candidatesFrom references unknown step '$targetStepId'"
                        )
                    )
                    false -> issues.add(
                        issue(
                            HarborErrorCode.DEFINITION_INVALID,
                            "$path.result.candidatesFrom",
                            "Referenced playback step '$targetStepId' does not define 'extract.candidates'"
                        )
                    )
                    true -> Unit
                }
            }
        }
    }

    private fun validateRequestSemantics(
        definition: SourceDefinition,
        request: RequestDef,
        allowedVars: Set<String>,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        validateHeadersMap(request.headers, "$path.headers", issues)
        validateInterpolation(request.url, allowedVars, "$path.url", issues)

        if (hasControlChars(request.url)) {
            issues.add(issue(HarborErrorCode.DEFINITION_INVALID, "$path.url", "URL contains control characters"))
        }

        // Static URL host validation (when scheme+authority has no dynamic ${...} variables)
        val expandedUrl = if (request.url.startsWith("\${source.baseUrl}")) {
            definition.source.baseUrl.trimEnd('/') + request.url.removePrefix("\${source.baseUrl}")
        } else {
            request.url
        }
        val schemeSep = expandedUrl.indexOf("://")
        val authorityPrefix = if (schemeSep >= 0) {
            val afterScheme = expandedUrl.substring(schemeSep + 3)
            val endAuth = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (endAuth >= 0) expandedUrl.substring(0, schemeSep + 3 + endAuth) else expandedUrl
        } else {
            expandedUrl
        }
        if (!authorityPrefix.contains("\${")) {
            val sanitizedForUri = INTERPOLATION_REGEX.replace(expandedUrl, "__VAR__")
            val uri = parseHttpUri(sanitizedForUri, definition.network.httpsOnly, "$path.url", issues)
            if (uri != null) {
                val catalogHosts = definition.network.allowedHosts.catalog.map { it.trim().lowercase() }.toSet()
                val host = uri.host.lowercase()
                if (!isHostAllowed(host, catalogHosts)) {
                    issues.add(
                        issue(
                            HarborErrorCode.DESTINATION_BLOCKED,
                            "$path.url",
                            "Request URL host '$host' is not in network.allowedHosts.catalog"
                        )
                    )
                }
                validateQueryStringForSecrets(uri.rawQuery, "$path.url", issues)
            }
        }

        for ((qKey, qVal) in request.query) {
            val qPath = "$path.query.$qKey"
            if (hasControlChars(qKey) || hasControlChars(qVal)) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        qPath,
                        "Query parameter contains control characters"
                    )
                )
            }
            validateInterpolation(qVal, allowedVars, qPath, issues)
            if (qKey.lowercase() in SECRET_QUERY_KEYS && !isInterpolationOnly(qVal) && qVal.isNotBlank()) {
                issues.add(
                    issue(
                        HarborErrorCode.POLICY_BLOCKED,
                        qPath,
                        "Query parameter '$qKey' appears to embed a plaintext secret"
                    )
                )
            }
        }
    }

    private fun validateQueryStringForSecrets(
        rawQuery: String?,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        if (rawQuery.isNullOrBlank()) return
        for (pair in rawQuery.split('&')) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val key = pair.substring(0, eqIdx).lowercase()
            val value = pair.substring(eqIdx + 1)
            if (key in SECRET_QUERY_KEYS && value.isNotBlank() && !isInterpolationOnly(value) && value != "__VAR__") {
                issues.add(
                    issue(
                        HarborErrorCode.POLICY_BLOCKED,
                        path,
                        "Static URL query parameter '$key' appears to embed a plaintext secret"
                    )
                )
            }
        }
    }

    private fun isInterpolationOnly(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("\${") && trimmed.endsWith("}") && trimmed.indexOf("\${", 2) == -1
    }

    private fun validateInterpolation(
        text: String,
        allowedVars: Set<String>,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        // Detect unclosed "${"
        var searchFrom = 0
        while (true) {
            val open = text.indexOf("\${", searchFrom)
            if (open == -1) break
            val close = text.indexOf('}', open + 2)
            if (close == -1) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "Unclosed interpolation expression in '$text'"
                    )
                )
                return
            }
            val nested = text.indexOf("\${", open + 2)
            if (nested != -1 && nested < close) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "Nested interpolation expressions are forbidden in '$text'"
                    )
                )
                return
            }
            searchFrom = close + 1
        }

        for (match in INTERPOLATION_REGEX.findAll(text)) {
            val varName = match.groupValues[1]
            if (!VALID_VAR_NAME_REGEX.matches(varName)) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "Invalid or expression-like interpolation '\${$varName}'"
                    )
                )
            } else if (varName !in allowedVars) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        path,
                        "Undeclared or disallowed interpolation variable '\${$varName}' for this operation"
                    )
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fields, selectors, JSON pointers, and transforms
    // -------------------------------------------------------------------------

    private fun validateFieldsSemantics(
        fields: Map<String, FieldExtractionDef>,
        usesJsonPointer: Boolean,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        for ((name, def) in fields) {
            validateFieldSemantics(def, usesJsonPointer, "$path.$name", issues)
        }
    }

    private fun validateFieldSemantics(
        field: FieldExtractionDef,
        usesJsonPointer: Boolean,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        if (usesJsonPointer) {
            if (field.pointer == null) {
                issues.add(
                    issue(
                        HarborErrorCode.SELECTOR_INVALID,
                        "$path.pointer",
                        "JSON field extraction requires 'pointer'"
                    )
                )
            } else {
                validateJsonPointer(field.pointer, "$path.pointer", issues)
            }
            if (field.selector != null) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        "$path.selector",
                        "CSS 'selector' cannot be used in JSON extraction mode"
                    )
                )
            }
        } else {
            field.selector?.let { sel ->
                validateCssSelector(sel, "$path.selector", issues)
            }
            if (field.pointer != null) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        "$path.pointer",
                        "JSON 'pointer' cannot be used in HTML extraction mode without embeddedJson"
                    )
                )
            }
        }

        validateTransforms(field.transforms, "$path.transforms", issues)
        field.fallback?.let { validateFieldSemantics(it, usesJsonPointer, "$path.fallback", issues) }
    }

    private fun validateTransforms(
        transforms: List<TransformDef>,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        if (transforms.size > MAX_TRANSFORM_CHAIN_LENGTH) {
            issues.add(
                issue(
                    HarborErrorCode.DEFINITION_INVALID,
                    path,
                    "Transform chain length (${transforms.size}) exceeds maximum allowed ($MAX_TRANSFORM_CHAIN_LENGTH)"
                )
            )
        }
        transforms.forEachIndexed { idx, t ->
            val tPath = "$path[$idx]"
            if (t.op !in ALLOWED_TRANSFORMS) {
                issues.add(
                    issue(
                        HarborErrorCode.DEFINITION_INVALID,
                        "$tPath.op",
                        "Unknown or unsupported transform operation '${t.op}'"
                    )
                )
                return@forEachIndexed
            }
            when (t.op) {
                "split" -> {
                    if (t.delimiter.isNullOrEmpty()) {
                        issues.add(
                            issue(
                                HarborErrorCode.DEFINITION_INVALID,
                                "$tPath.delimiter",
                                "Transform 'split' requires a non-empty 'delimiter'"
                            )
                        )
                    }
                }
                "mapValues" -> {
                    if (t.mappings.isEmpty()) {
                        issues.add(
                            issue(
                                HarborErrorCode.DEFINITION_INVALID,
                                "$tPath.mappings",
                                "Transform 'mapValues' requires a non-empty 'mappings' map"
                            )
                        )
                    }
                }
                "template" -> {
                    if (t.template == null) {
                        issues.add(
                            issue(
                                HarborErrorCode.DEFINITION_INVALID,
                                "$tPath.template",
                                "Transform 'template' requires a 'template' string"
                            )
                        )
                    }
                }
                "regexExtract", "regexExtractAll" -> {
                    if (t.pattern.isNullOrEmpty()) {
                        issues.add(
                            issue(
                                HarborErrorCode.DEFINITION_INVALID,
                                "$tPath.pattern",
                                "Transform '${t.op}' requires a non-empty 'pattern'"
                            )
                        )
                    } else {
                        try {
                            Regex(t.pattern)
                        } catch (e: Exception) {
                            issues.add(
                                issue(
                                    HarborErrorCode.DEFINITION_INVALID,
                                    "$tPath.pattern",
                                    "Invalid regex pattern '${t.pattern}': ${e.message}"
                                )
                            )
                        }
                    }
                    if (t.group < 0) {
                        issues.add(
                            issue(
                                HarborErrorCode.DEFINITION_INVALID,
                                "$tPath.group",
                                "Transform '${t.op}' group must be >= 0"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun validateCssSelector(
        selector: String,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        if (selector.isBlank()) {
            issues.add(issue(HarborErrorCode.SELECTOR_INVALID, path, "CSS selector must not be blank"))
            return
        }
        try {
            Jsoup.parse("").select(selector)
        } catch (e: Selector.SelectorParseException) {
            issues.add(
                issue(
                    HarborErrorCode.SELECTOR_INVALID,
                    path,
                    "Invalid CSS selector '$selector': ${e.message}"
                )
            )
        } catch (e: IllegalStateException) {
            issues.add(
                issue(
                    HarborErrorCode.SELECTOR_INVALID,
                    path,
                    "Invalid CSS selector '$selector': ${e.message}"
                )
            )
        } catch (e: IllegalArgumentException) {
            issues.add(
                issue(
                    HarborErrorCode.SELECTOR_INVALID,
                    path,
                    "Invalid CSS selector '$selector': ${e.message}"
                )
            )
        }
    }

    private fun validateJsonPointer(
        pointer: String,
        path: String,
        issues: MutableList<ValidationIssue>
    ) {
        // RFC 6901: "" (root) or "/" followed by tokens where "~" may only precede "0" or "1".
        if (pointer.isEmpty()) return
        if (!pointer.startsWith("/")) {
            issues.add(
                issue(
                    HarborErrorCode.SELECTOR_INVALID,
                    path,
                    "Invalid RFC 6901 JSON Pointer '$pointer': must be empty or start with '/'"
                )
            )
            return
        }
        var i = 0
        while (i < pointer.length) {
            if (pointer[i] == '~') {
                if (i + 1 >= pointer.length || (pointer[i + 1] != '0' && pointer[i + 1] != '1')) {
                    issues.add(
                        issue(
                            HarborErrorCode.SELECTOR_INVALID,
                            path,
                            "Invalid RFC 6901 JSON Pointer '$pointer': '~' must be escaped as '~0' or '~1'"
                        )
                    )
                    return
                }
                i += 2
            } else {
                i++
            }
        }
    }

    private fun hasControlChars(text: String): Boolean {
        return text.any { it.code < 0x20 || it.code == 0x7F }
    }

    private fun issue(code: HarborErrorCode, path: String, message: String): ValidationIssue {
        return ValidationIssue(
            stage = "semantic",
            code = code,
            path = path,
            message = message
        )
    }
}
