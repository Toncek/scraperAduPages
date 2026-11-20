package com.harbor.tv.engine.extract

import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.engine.definition.CandidatesExtractionDef
import com.harbor.tv.engine.definition.FieldExtractionDef
import com.harbor.tv.engine.definition.ItemsExtractionDef
import com.harbor.tv.engine.definition.PaginationDef
import com.harbor.tv.engine.transform.TransformationEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Extracts items, detail fields, playback candidates, and pagination cursors from JSON payloads
 * (including embedded JSON blocks) using strict `kotlinx.serialization.json` and RFC 6901
 * JSON Pointers without JavaScript evaluation.
 */
class JsonExtractionEngine(
    private val transformationEngine: TransformationEngine = TransformationEngine(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }
) {

    fun parseJson(rawJson: String): JsonElement {
        if (rawJson.isBlank()) {
            throw HarborException(
                HarborErrorCode.SOURCE_LAYOUT_CHANGED,
                "JSON response payload is blank"
            )
        }
        return try {
            json.parseToJsonElement(rawJson)
        } catch (e: Exception) {
            throw HarborException(
                HarborErrorCode.SOURCE_LAYOUT_CHANGED,
                "Failed to parse JSON payload: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Evaluates an RFC 6901 JSON Pointer (e.g. `""`, `"/a/b/0"`) against [root].
     * Returns `null` when the target path does not exist or resolves to `JsonNull`.
     */
    fun evaluatePointer(root: JsonElement, pointer: String): JsonElement? {
        if (pointer.isEmpty()) {
            return if (root is JsonNull) null else root
        }
        if (!pointer.startsWith("/")) {
            throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "Invalid RFC 6901 JSON Pointer '$pointer': must be empty or start with '/'"
            )
        }

        val tokens = pointer.substring(1).split('/')
        var current: JsonElement = root
        for (rawToken in tokens) {
            val token = unescapePointerToken(rawToken, pointer)
            current = when (current) {
                is JsonObject -> current[token] ?: return null
                is JsonArray -> {
                    val idx = parseArrayIndex(token) ?: return null
                    if (idx !in 0 until current.size) return null
                    current[idx]
                }
                else -> return null
            }
        }
        return if (current is JsonNull) null else current
    }

    fun extractItems(
        rawJson: String,
        finalUrl: String,
        itemsDef: ItemsExtractionDef,
        paginationDef: PaginationDef? = null,
        maxItemsCap: Int = itemsDef.limit
    ): CollectionExtractionOutput {
        val root = parseJson(rawJson)
        val pointer = itemsDef.pointer
            ?: throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "JSON item extraction requires a 'pointer'"
            )
        val elements = resolveCollectionElements(root, pointer, "collection")

        val cap = minOf(itemsDef.limit, maxItemsCap).coerceAtLeast(0)
        val accepted = mutableListOf<ExtractedRecord>()
        var rejected = 0
        val missingRequired = linkedSetOf<String>()

        for (element in elements.take(cap)) {
            val result = extractFieldsMap(element, itemsDef.fields, finalUrl)
            if (result.missingRequiredFields.isNotEmpty()) {
                rejected++
                missingRequired.addAll(result.missingRequiredFields)
            } else {
                accepted.add(result.record)
            }
        }

        val nextCursor = extractNextCursor(root, paginationDef, finalUrl)
        return CollectionExtractionOutput(
            records = accepted,
            itemsRejected = rejected,
            missingRequiredFields = missingRequired.toList(),
            nextCursor = nextCursor
        )
    }

    fun extractDetails(
        rawJson: String,
        finalUrl: String,
        fields: Map<String, FieldExtractionDef>
    ): SingleExtractionOutput {
        val root = parseJson(rawJson)
        val result = extractFieldsMap(root, fields, finalUrl)
        if (result.missingRequiredFields.isNotEmpty()) {
            throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Missing required JSON detail fields: ${result.missingRequiredFields.joinToString(", ")}"
            )
        }
        return result
    }

    fun extractFields(
        rawJson: String,
        finalUrl: String,
        fields: Map<String, FieldExtractionDef>
    ): SingleExtractionOutput {
        return extractDetails(rawJson, finalUrl, fields)
    }

    fun extractCandidates(
        rawJson: String,
        finalUrl: String,
        candidatesDef: CandidatesExtractionDef,
        maxCandidatesCap: Int = candidatesDef.limit
    ): CollectionExtractionOutput {
        val root = parseJson(rawJson)
        val pointer = candidatesDef.pointer
            ?: throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "JSON candidate extraction requires a 'pointer'"
            )
        val elements = resolveCollectionElements(root, pointer, "candidate")

        val cap = minOf(candidatesDef.limit, maxCandidatesCap).coerceAtLeast(0)
        val accepted = mutableListOf<ExtractedRecord>()
        var rejected = 0
        val missingRequired = linkedSetOf<String>()

        for (element in elements.take(cap)) {
            val result = extractFieldsMap(element, candidatesDef.fields, finalUrl)
            if (result.missingRequiredFields.isNotEmpty()) {
                rejected++
                missingRequired.addAll(result.missingRequiredFields)
            } else {
                accepted.add(result.record)
            }
        }

        val effectiveRejected = if (accepted.isNotEmpty()) 0 else rejected
        val effectiveMissing = if (accepted.isNotEmpty()) emptyList() else missingRequired.toList()

        return CollectionExtractionOutput(
            records = accepted,
            itemsRejected = effectiveRejected,
            missingRequiredFields = effectiveMissing,
            nextCursor = null
        )
    }

    private fun resolveCollectionElements(
        root: JsonElement,
        pointer: String,
        kind: String
    ): List<JsonElement> {
        val collectionNode: JsonElement = if (pointer.isEmpty() || (pointer == "/" && (root is JsonArray || (root is JsonObject && !root.containsKey(""))))) {
            root
        } else {
            evaluatePointer(root, pointer)
                ?: throw HarborException(
                    HarborErrorCode.SOURCE_LAYOUT_CHANGED,
                    "JSON $kind pointer '$pointer' did not match any element"
                )
        }
        return when (collectionNode) {
            is JsonArray -> collectionNode.toList()
            is JsonObject -> {
                if (collectionNode.isEmpty()) {
                    emptyList()
                } else {
                    val objects = collectionNode.values.filterIsInstance<JsonObject>()
                    if (objects.isNotEmpty()) {
                        objects
                    } else {
                        throw HarborException(
                            HarborErrorCode.SOURCE_LAYOUT_CHANGED,
                            "JSON $kind pointer '$pointer' did not resolve to an array or object collection"
                        )
                    }
                }
            }
            else -> throw HarborException(
                HarborErrorCode.SOURCE_LAYOUT_CHANGED,
                "JSON $kind pointer '$pointer' did not resolve to an array"
            )
        }
    }

    private fun extractNextCursor(
        root: JsonElement,
        paginationDef: PaginationDef?,
        finalUrl: String
    ): String? {
        if (paginationDef == null) return null
        if (paginationDef.cursorPointer != null) {
            val node = evaluatePointer(root, paginationDef.cursorPointer)
            return extractScalarString(node)?.takeIf { it.isNotBlank() }
        }
        val nextDef = paginationDef.next ?: return null
        val values = extractSingleField(root, nextDef, finalUrl)
        return values.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun extractFieldsMap(
        root: JsonElement,
        fields: Map<String, FieldExtractionDef>,
        finalUrl: String
    ): SingleExtractionOutput {
        val extracted = LinkedHashMap<String, List<String>>(fields.size)
        val missingRequired = mutableListOf<String>()

        for ((fieldName, fieldDef) in fields) {
            val values = extractSingleField(root, fieldDef, finalUrl)
            if (values.isEmpty() || values.all { it.isBlank() }) {
                if (fieldDef.required) {
                    missingRequired.add(fieldName)
                }
            } else {
                extracted[fieldName] = values
            }
        }

        return SingleExtractionOutput(
            record = ExtractedRecord(extracted),
            missingRequiredFields = missingRequired
        )
    }

    private fun extractSingleField(
        root: JsonElement,
        field: FieldExtractionDef,
        finalUrl: String
    ): List<String> {
        val pointer = field.pointer
            ?: throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "JSON field extraction requires a 'pointer'"
            )
        val target = evaluatePointer(root, pointer)

        val rawValues = when (field.cardinality) {
            "many" -> extractManyStrings(target)
            "one" -> {
                val single = extractScalarString(target)?.takeIf { it.isNotBlank() }
                if (single != null) listOf(single) else emptyList()
            }
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported field cardinality '${field.cardinality}'"
            )
        }

        val resolvedValues = if (rawValues.isNotEmpty()) {
            rawValues
        } else if (field.fallback != null) {
            extractSingleField(root, field.fallback, finalUrl)
        } else {
            emptyList()
        }

        if (resolvedValues.isEmpty()) {
            return emptyList()
        }

        return if (field.transforms.isEmpty()) {
            resolvedValues
        } else {
            transformationEngine.apply(resolvedValues, field.transforms, contextUrl = finalUrl)
        }
    }

    private fun extractScalarString(element: JsonElement?): String? {
        return when (element) {
            null, is JsonNull -> null
            is JsonPrimitive -> element.content
            else -> null
        }
    }

    private fun extractManyStrings(element: JsonElement?): List<String> {
        return when (element) {
            null, is JsonNull -> emptyList()
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            is JsonArray -> element.mapNotNull { item ->
                when (item) {
                    is JsonObject -> {
                        val candidate = item["tag_name"] ?: item["name"] ?: item["tag"] ?: item["category"]
                        extractScalarString(candidate)?.takeIf { it.isNotBlank() }
                    }
                    else -> extractScalarString(item)?.takeIf { it.isNotBlank() }
                }
            }
            is JsonObject -> emptyList()
        }
    }

    private fun unescapePointerToken(token: String, fullPointer: String): String {
        val sb = StringBuilder(token.length)
        var i = 0
        while (i < token.length) {
            val ch = token[i]
            if (ch == '~') {
                if (i + 1 >= token.length) {
                    throw HarborException(
                        HarborErrorCode.SELECTOR_INVALID,
                        "Invalid escape in JSON Pointer '$fullPointer'"
                    )
                }
                when (token[i + 1]) {
                    '0' -> sb.append('~')
                    '1' -> sb.append('/')
                    else -> throw HarborException(
                        HarborErrorCode.SELECTOR_INVALID,
                        "Invalid escape '~${token[i + 1]}' in JSON Pointer '$fullPointer'"
                    )
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun parseArrayIndex(token: String): Int? {
        if (token == "0") return 0
        if (token.isEmpty() || token[0] !in '1'..'9') return null
        if (!token.all { it in '0'..'9' }) return null
        return token.toIntOrNull()
    }
}
