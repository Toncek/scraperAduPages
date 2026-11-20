package com.harbor.tv.engine.extract

import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.CategoryItem
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.model.LiveStatus
import com.harbor.tv.core.model.MetadataCompleteness
import com.harbor.tv.core.model.PaginatedListingResult
import com.harbor.tv.core.model.PlaybackCandidate
import com.harbor.tv.core.model.PlaybackStreamType
import com.harbor.tv.core.network.HttpTransport
import com.harbor.tv.engine.definition.CatalogExtractDef
import com.harbor.tv.engine.definition.CatalogOperationDef
import com.harbor.tv.engine.definition.DetailsExtractDef
import com.harbor.tv.engine.definition.DetailsOperationDef
import com.harbor.tv.engine.definition.DocumentDef
import com.harbor.tv.engine.definition.PaginationDef
import com.harbor.tv.engine.definition.PlaybackStepDef
import com.harbor.tv.engine.definition.ResponseDef
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.policy.RequestPolicyEngine
import java.net.URI
import java.util.Locale

data class CategoriesExtractionResult(
    val categories: List<CategoryItem>,
    val records: List<ExtractedRecord>,
    val itemsRejected: Int = 0,
    val missingRequiredFields: List<String> = emptyList()
)

data class ListingExtractionResult(
    val listing: PaginatedListingResult,
    val records: List<ExtractedRecord>,
    val missingRequiredFields: List<String> = emptyList()
)

data class PlaybackStepExtractionResult(
    val stepId: String,
    val fields: Map<String, List<String>>,
    val candidates: List<PlaybackCandidate>,
    val candidateRecords: List<ExtractedRecord>,
    val candidatesRejected: Int = 0,
    val missingRequiredFields: List<String> = emptyList()
)

data class PlaybackExecutionResult(
    val candidates: List<PlaybackCandidate>,
    val candidateRecords: List<ExtractedRecord>,
    val candidatesRejected: Int = 0,
    val missingRequiredFields: List<String> = emptyList(),
    val stepOutputs: Map<String, String> = emptyMap()
)

/**
 * Dispatches HTML, JSON, and Embedded JSON extraction for `categories`, `listing`, `search`,
 * `details`, and multi-step `playback` operations while resolving `reuse` directives safely.
 */
class UnifiedOperationExtractor(
    private val htmlExtractor: HtmlExtractionEngine = HtmlExtractionEngine(),
    private val jsonExtractor: JsonExtractionEngine = JsonExtractionEngine(),
    private val policyEngine: RequestPolicyEngine = RequestPolicyEngine()
) {

    fun extractCategories(
        definition: SourceDefinition,
        responseBody: String,
        finalUrl: String
    ): CategoriesExtractionResult {
        val op = definition.operations.categories
            ?: throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Source '${definition.source.id}' does not define 'operations.categories'",
                sourceId = definition.source.id,
                operation = "categories"
            )
        val extractDef = resolveCatalogExtract(definition, op, "categories")
        val output = extractCollection(
            response = op.response,
            document = op.document,
            responseBody = responseBody,
            finalUrl = finalUrl,
            itemsDef = extractDef.items,
            paginationDef = null,
            maxItemsCap = definition.network.limits.maxItemsPerPage,
            allowedCatalogHosts = allowedCatalogHostSet(definition)
        )

        val categories = output.records.map { record ->
            val id = record.firstOrNull("id")
                ?: throw HarborException(
                    HarborErrorCode.REQUIRED_FIELD_MISSING,
                    "Category item missing required field 'id'",
                    sourceId = definition.source.id,
                    operation = "categories"
                )
            val title = record.firstOrNull("title")
                ?: throw HarborException(
                    HarborErrorCode.REQUIRED_FIELD_MISSING,
                    "Category item missing required field 'title'",
                    sourceId = definition.source.id,
                    operation = "categories"
                )
            val url = record.firstOrNull("url")
                ?: throw HarborException(
                    HarborErrorCode.REQUIRED_FIELD_MISSING,
                    "Category item missing required field 'url'",
                    sourceId = definition.source.id,
                    operation = "categories"
                )
            CategoryItem(
                sourceId = definition.source.id,
                id = id,
                title = title,
                url = url
            )
        }

        return CategoriesExtractionResult(
            categories = categories,
            records = output.records,
            itemsRejected = output.itemsRejected,
            missingRequiredFields = output.missingRequiredFields
        )
    }

    fun extractListing(
        definition: SourceDefinition,
        operationName: String,
        responseBody: String,
        finalUrl: String
    ): ListingExtractionResult {
        val op = when (operationName) {
            "listing" -> definition.operations.listing
            "search" -> definition.operations.search
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported catalog listing operation '$operationName'",
                sourceId = definition.source.id,
                operation = operationName
            )
        } ?: throw HarborException(
            HarborErrorCode.UNSUPPORTED_CAPABILITY,
            "Source '${definition.source.id}' does not define 'operations.$operationName'",
            sourceId = definition.source.id,
            operation = operationName
        )

        val extractDef = resolveCatalogExtract(definition, op, operationName)
        val paginationDef = resolveCatalogPagination(definition, op, operationName)
        val output = extractCollection(
            response = op.response,
            document = op.document,
            responseBody = responseBody,
            finalUrl = finalUrl,
            itemsDef = extractDef.items,
            paginationDef = paginationDef,
            maxItemsCap = definition.network.limits.maxItemsPerPage,
            allowedCatalogHosts = allowedCatalogHostSet(definition)
        )

        val items = output.records.map { record ->
            mapRecordToCatalogItem(definition, record, finalUrl, operationName)
        }

        return ListingExtractionResult(
            listing = PaginatedListingResult(
                items = items,
                nextCursor = output.nextCursor,
                totalExtracted = items.size,
                totalRejected = output.itemsRejected
            ),
            records = output.records,
            missingRequiredFields = output.missingRequiredFields
        )
    }

    fun extractDetails(
        definition: SourceDefinition,
        responseBody: String,
        finalUrl: String
    ): SingleExtractionOutput {
        val op = definition.operations.details
            ?: throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Source '${definition.source.id}' does not define 'operations.details'",
                sourceId = definition.source.id,
                operation = "details"
            )
        val extractDef = resolveDetailsExtract(definition, op)
        val allowedHosts = allowedCatalogHostSet(definition)
        val embedded = op.document?.embeddedJson
        return when {
            embedded != null -> {
                val jsonText = htmlExtractor.extractEmbeddedJson(
                    html = responseBody,
                    finalUrl = finalUrl,
                    embeddedJson = embedded,
                    allowedCatalogHosts = allowedHosts
                )
                jsonExtractor.extractDetails(jsonText, finalUrl, extractDef.fields)
            }
            op.response.type == "json" -> {
                jsonExtractor.extractDetails(responseBody, finalUrl, extractDef.fields)
            }
            op.response.type == "html" -> {
                htmlExtractor.extractDetails(responseBody, finalUrl, extractDef.fields, allowedHosts)
            }
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported response type '${op.response.type}'",
                sourceId = definition.source.id,
                operation = "details"
            )
        }
    }

    fun extractPlaybackStep(
        definition: SourceDefinition,
        step: PlaybackStepDef,
        responseBody: String,
        finalUrl: String
    ): PlaybackStepExtractionResult {
        val allowedHosts = allowedCatalogHostSet(definition)
        val embedded = step.document?.embeddedJson

        val jsonPayload: String? = when {
            embedded != null -> htmlExtractor.extractEmbeddedJson(
                html = responseBody,
                finalUrl = finalUrl,
                embeddedJson = embedded,
                allowedCatalogHosts = allowedHosts
            )
            step.response.type == "json" -> responseBody
            step.response.type == "html" -> null
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported response type '${step.response.type}' in playback step '${step.id}'",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }

        val extractedFields = if (step.extract.fields.isNotEmpty()) {
            val out = if (jsonPayload != null) {
                jsonExtractor.extractFields(jsonPayload, finalUrl, step.extract.fields)
            } else {
                htmlExtractor.extractFields(responseBody, finalUrl, step.extract.fields, allowedHosts)
            }
            out.record.fields
        } else {
            emptyMap()
        }

        val candidateOutput = step.extract.candidates?.let { candsDef ->
            val maxCap = minOf(candsDef.limit, 20)
            if (jsonPayload != null) {
                jsonExtractor.extractCandidates(jsonPayload, finalUrl, candsDef, maxCap)
            } else {
                htmlExtractor.extractCandidates(responseBody, finalUrl, candsDef, maxCap, allowedHosts)
            }
        }

        val candidates = candidateOutput?.records?.map { record ->
            mapRecordToPlaybackCandidate(record)
        }.orEmpty()

        return PlaybackStepExtractionResult(
            stepId = step.id,
            fields = extractedFields,
            candidates = candidates,
            candidateRecords = candidateOutput?.records.orEmpty(),
            candidatesRejected = candidateOutput?.itemsRejected ?: 0,
            missingRequiredFields = candidateOutput?.missingRequiredFields.orEmpty()
        )
    }

    suspend fun executePlayback(
        definition: SourceDefinition,
        detailUrl: String,
        transport: HttpTransport,
        userAllowLan: Boolean = false
    ): PlaybackExecutionResult {
        val op = definition.operations.playback
            ?: throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Source '${definition.source.id}' does not define 'operations.playback'",
                sourceId = definition.source.id,
                operation = "playback"
            )

        val variables = linkedMapOf(
            "source.baseUrl" to definition.source.baseUrl,
            "input.detailUrl" to detailUrl
        )
        val allowedVariables = linkedSetOf("source.baseUrl", "input.detailUrl")
        val stepResults = linkedMapOf<String, PlaybackStepExtractionResult>()

        for (step in op.steps) {
            val requestUrl = policyEngine.buildInterpolatedUrl(
                requestDef = step.request,
                definition = definition,
                variables = variables,
                allowedVariables = allowedVariables
            )
            val response = transport.executeGet(
                url = requestUrl,
                definition = definition,
                purpose = HostPurpose.CATALOG,
                extraHeaders = step.request.headers,
                userAllowLan = userAllowLan
            )
            val stepResult = extractPlaybackStep(
                definition = definition,
                step = step,
                responseBody = response.body,
                finalUrl = response.finalUrl
            )
            stepResults[step.id] = stepResult

            for ((fieldName, values) in stepResult.fields) {
                val scalar = values.firstOrNull() ?: continue
                variables["${step.id}.$fieldName"] = scalar
                variables["steps.${step.id}.$fieldName"] = scalar
                allowedVariables.add("${step.id}.$fieldName")
                allowedVariables.add("steps.${step.id}.$fieldName")
            }
        }

        val targetParts = op.result.candidatesFrom.split('.')
        if (targetParts.size != 2 || targetParts[1] != "candidates") {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Invalid playback.result.candidatesFrom '${op.result.candidatesFrom}'",
                sourceId = definition.source.id,
                operation = "playback"
            )
        }
        val targetStep = stepResults[targetParts[0]]
            ?: throw HarborException(
                HarborErrorCode.PLAYBACK_NOT_FOUND,
                "Playback result step '${targetParts[0]}' produced no output",
                sourceId = definition.source.id,
                operation = "playback"
            )

        return PlaybackExecutionResult(
            candidates = targetStep.candidates,
            candidateRecords = targetStep.candidateRecords,
            candidatesRejected = targetStep.candidatesRejected,
            missingRequiredFields = targetStep.missingRequiredFields,
            stepOutputs = variables
        )
    }

    private fun extractCollection(
        response: ResponseDef,
        document: DocumentDef?,
        responseBody: String,
        finalUrl: String,
        itemsDef: com.harbor.tv.engine.definition.ItemsExtractionDef,
        paginationDef: PaginationDef?,
        maxItemsCap: Int,
        allowedCatalogHosts: Set<String>
    ): CollectionExtractionOutput {
        val embedded = document?.embeddedJson
        return when {
            embedded != null -> {
                val jsonText = htmlExtractor.extractEmbeddedJson(
                    html = responseBody,
                    finalUrl = finalUrl,
                    embeddedJson = embedded,
                    allowedCatalogHosts = allowedCatalogHosts
                )
                jsonExtractor.extractItems(jsonText, finalUrl, itemsDef, paginationDef, maxItemsCap)
            }
            response.type == "json" -> {
                jsonExtractor.extractItems(responseBody, finalUrl, itemsDef, paginationDef, maxItemsCap)
            }
            response.type == "html" -> {
                htmlExtractor.extractItems(
                    html = responseBody,
                    finalUrl = finalUrl,
                    itemsDef = itemsDef,
                    paginationDef = paginationDef,
                    maxItemsCap = maxItemsCap,
                    allowedCatalogHosts = allowedCatalogHosts
                )
            }
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported response type '${response.type}'"
            )
        }
    }

    private fun resolveCatalogExtract(
        definition: SourceDefinition,
        op: CatalogOperationDef,
        opName: String
    ): CatalogExtractDef {
        op.extract?.let { return it }
        val visited = linkedSetOf(opName)
        var currentName = op.reuse?.extractFrom
        while (currentName != null) {
            if (!visited.add(currentName)) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Cyclic reuse.extractFrom detected at '$currentName'",
                    sourceId = definition.source.id,
                    operation = opName
                )
            }
            val targetOp = catalogOperationByName(definition, currentName)
                ?: throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "reuse.extractFrom target '$currentName' not found",
                    sourceId = definition.source.id,
                    operation = opName
                )
            targetOp.extract?.let { return it }
            currentName = targetOp.reuse?.extractFrom
        }
        throw HarborException(
            HarborErrorCode.DEFINITION_INVALID,
            "Operation '$opName' has no extract definition",
            sourceId = definition.source.id,
            operation = opName
        )
    }

    private fun resolveCatalogPagination(
        definition: SourceDefinition,
        op: CatalogOperationDef,
        opName: String
    ): PaginationDef? {
        op.pagination?.let { return it }
        val visited = linkedSetOf(opName)
        var currentName = op.reuse?.paginationFrom
        while (currentName != null) {
            if (!visited.add(currentName)) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Cyclic reuse.paginationFrom detected at '$currentName'",
                    sourceId = definition.source.id,
                    operation = opName
                )
            }
            val targetOp = catalogOperationByName(definition, currentName)
                ?: throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "reuse.paginationFrom target '$currentName' not found",
                    sourceId = definition.source.id,
                    operation = opName
                )
            targetOp.pagination?.let { return it }
            currentName = targetOp.reuse?.paginationFrom
        }
        return null
    }

    private fun resolveDetailsExtract(
        definition: SourceDefinition,
        op: DetailsOperationDef
    ): DetailsExtractDef {
        op.extract?.let { return it }
        val targetName = op.reuse?.extractFrom
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Operation 'details' has no extract definition",
                sourceId = definition.source.id,
                operation = "details"
            )
        val visited = linkedSetOf("details")
        var currentName: String? = targetName
        while (currentName != null) {
            if (!visited.add(currentName)) {
                throw HarborException(
                    HarborErrorCode.DEFINITION_INVALID,
                    "Cyclic reuse.extractFrom detected at '$currentName'",
                    sourceId = definition.source.id,
                    operation = "details"
                )
            }
            val targetOp = if (currentName == "details") definition.operations.details else null
            targetOp?.extract?.let { return it }
            currentName = targetOp?.reuse?.extractFrom
        }
        throw HarborException(
            HarborErrorCode.DEFINITION_INVALID,
            "Details reuse.extractFrom target '$targetName' not found",
            sourceId = definition.source.id,
            operation = "details"
        )
    }

    private fun catalogOperationByName(
        definition: SourceDefinition,
        name: String
    ): CatalogOperationDef? {
        return when (name) {
            "categories" -> definition.operations.categories
            "listing" -> definition.operations.listing
            "search" -> definition.operations.search
            else -> null
        }
    }

    private fun mapRecordToCatalogItem(
        definition: SourceDefinition,
        record: ExtractedRecord,
        finalUrl: String,
        operationName: String
    ): CatalogItem {
        val id = record.firstOrNull("id")
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Catalog item missing required field 'id'",
                sourceId = definition.source.id,
                operation = operationName
            )
        val title = record.firstOrNull("title")
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Catalog item missing required field 'title'",
                sourceId = definition.source.id,
                operation = operationName
            )
        val detailUrl = record.firstOrNull("detailUrl") ?: finalUrl
        val durationSeconds = record.firstOrNull("durationSeconds")?.toIntOrNull()
        val liveValue = record.firstOrNull("liveStatus")?.lowercase(Locale.ROOT)
        val liveStatus = when (liveValue) {
            "live", "public", "true" -> LiveStatus.LIVE
            "upcoming", "scheduled" -> LiveStatus.UPCOMING
            "offline", "ondemand", "on_demand", "false" -> LiveStatus.ON_DEMAND
            null -> if (definition.source.capabilities.live && durationSeconds == null) {
                LiveStatus.LIVE
            } else {
                LiveStatus.ON_DEMAND
            }
            else -> LiveStatus.ON_DEMAND
        }

        val sourceTags = record.list("sourceTags")
        val (maxQualityLabel, maxResolutionHeight) = normalizeCatalogQuality(
            record.firstOrNull("maxQuality") ?: record.firstOrNull("qualityLabel")
        )
        val completeness = if (sourceTags.isEmpty() && durationSeconds == null && !definition.source.capabilities.live) {
            MetadataCompleteness.PARTIAL
        } else {
            MetadataCompleteness.COMPLETE
        }

        val combinedThumbnails = (
            record.list("thumbnailUrl") +
                record.list("thumbnailReferences") +
                record.list("previewThumbnails")
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val sampledThumbnails = sampleThumbnails(combinedThumbnails)

        return CatalogItem(
            sourceId = definition.source.id,
            sourceItemId = id,
            sourcePageUrl = detailUrl,
            title = title,
            description = record.firstOrNull("description"),
            durationSeconds = durationSeconds,
            liveStatus = liveStatus,
            sourceTags = sourceTags,
            languages = record.list("languages"),
            contentRating = record.firstOrNull("contentRating"),
            thumbnailReferences = sampledThumbnails,
            maxQualityLabel = maxQualityLabel,
            maxResolutionHeight = maxResolutionHeight,
            metadataCompleteness = completeness,
            metadataProvenance = operationName
        )
    }

    private fun mapRecordToPlaybackCandidate(record: ExtractedRecord): PlaybackCandidate {
        val url = record.firstOrNull("url")
            ?: throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Playback candidate missing required field 'url'"
            )
        val mimeType = record.firstOrNull("mimeType")
        val rawQualityLabel = record.firstOrNull("qualityLabel")
            ?: record.firstOrNull("quality")
            ?: record.firstOrNull("label")
        val resolutionHeight = record.firstOrNull("resolutionHeight")?.toIntOrNull()
            ?: inferResolutionHeight(rawQualityLabel, url)
        val qualityLabel = when {
            !rawQualityLabel.isNullOrBlank() -> rawQualityLabel.trim()
            resolutionHeight != null -> if (resolutionHeight >= 2160) "2160p 4K" else "${resolutionHeight}p"
            else -> null
        }
        val bitrateKbps = record.firstOrNull("bitrateKbps")?.toIntOrNull()

        return PlaybackCandidate(
            url = url,
            mimeType = mimeType,
            streamType = inferStreamType(url, mimeType),
            qualityLabel = qualityLabel,
            resolutionHeight = resolutionHeight,
            bitrateKbps = bitrateKbps,
            subtitleUrls = record.list("subtitleUrls")
        )
    }

    private fun inferStreamType(url: String, mimeType: String?): PlaybackStreamType {
        val mime = mimeType?.lowercase(Locale.ROOT)
        if (mime != null) {
            if (mime.contains("mpegurl")) return PlaybackStreamType.HLS
            if (mime.contains("dash+xml")) return PlaybackStreamType.DASH
        }
        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT) }.getOrNull() ?: url.lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> PlaybackStreamType.HLS
            path.endsWith(".mpd") -> PlaybackStreamType.DASH
            else -> PlaybackStreamType.PROGRESSIVE
        }
    }

    private fun normalizeCatalogQuality(rawQuality: String?): Pair<String?, Int?> {
        val cleaned = rawQuality?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val height = inferResolutionHeight(cleaned, url = null)
        if (height != null) {
            val label = when {
                height >= 2160 -> "4K"
                height in setOf(1440, 1080, 720, 480, 360, 240) -> "${height}p"
                else -> "${height}p"
            }
            return label to height
        }
        if (cleaned.contains("hd", ignoreCase = true)) {
            return "HD" to 720
        }
        return cleaned to null
    }

    private fun inferResolutionHeight(qualityLabel: String?, url: String? = null): Int? {
        if (!qualityLabel.isNullOrBlank()) {
            parseHeightFromText(qualityLabel, allowBareDigits = true)?.let { return it }
        }
        if (!url.isNullOrBlank()) {
            parseHeightFromText(url, allowBareDigits = false)?.let { return it }
        }
        return null
    }

    private fun parseHeightFromText(text: String, allowBareDigits: Boolean): Int? {
        Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(text)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        Regex("""\d{3,4}x(\d{3,4})""", RegexOption.IGNORE_CASE).find(text)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        Regex("""(?:^|[/\-_])(2160|1440|1080|720|480|360|240)(?:\.(?:mp4|m3u8|mpd)|[/\-_]|$)""", RegexOption.IGNORE_CASE)
            .find(text)?.let { match ->
                match.groupValues[1].toIntOrNull()?.let { return it }
            }
        val lower = text.lowercase(Locale.ROOT)
        if (Regex("""(?:^|[^a-z0-9])(4k|uhd)(?:[^a-z0-9]|$)""").containsMatchIn(lower)) return 2160
        if (Regex("""(?:^|[^a-z0-9])(2k|qhd)(?:[^a-z0-9]|$)""").containsMatchIn(lower)) return 1440
        if (Regex("""(?:^|[^a-z0-9])(fhd|full\s*hd)(?:[^a-z0-9]|$)""").containsMatchIn(lower)) return 1080
        if (allowBareDigits) {
            text.trim().toIntOrNull()?.takeIf { it in 144..4320 }?.let { return it }
        }
        return null
    }

    private fun sampleThumbnails(urls: List<String>, maxCount: Int = 6): List<String> {
        if (urls.size <= maxCount) return urls
        if (maxCount <= 1) return urls.take(maxCount)
        return (0 until maxCount).map { i ->
            val idx = (i * (urls.size - 1)) / (maxCount - 1)
            urls[idx]
        }.distinct()
    }

    private fun allowedCatalogHostSet(definition: SourceDefinition): Set<String> {
        return definition.network.allowedHosts.catalog
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
