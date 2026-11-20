package com.harbor.tv.engine.extract

import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.engine.definition.CandidatesExtractionDef
import com.harbor.tv.engine.definition.EmbeddedJsonDef
import com.harbor.tv.engine.definition.FieldExtractionDef
import com.harbor.tv.engine.definition.ItemsExtractionDef
import com.harbor.tv.engine.definition.PaginationDef
import com.harbor.tv.engine.transform.TransformationEngine
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Selector

data class ExtractedRecord(
    val fields: Map<String, List<String>>
) {
    fun firstOrNull(name: String): String? = fields[name]?.firstOrNull()
    fun list(name: String): List<String> = fields[name].orEmpty()
    fun hasField(name: String): Boolean = !fields[name].isNullOrEmpty()
}

data class CollectionExtractionOutput(
    val records: List<ExtractedRecord>,
    val itemsRejected: Int = 0,
    val missingRequiredFields: List<String> = emptyList(),
    val nextCursor: String? = null
)

data class SingleExtractionOutput(
    val record: ExtractedRecord,
    val missingRequiredFields: List<String> = emptyList()
)

/**
 * Extracts items, detail fields, playback candidates, pagination links, and embedded JSON
 * scripts from HTML responses using Jsoup while ignoring untrusted page `<base>` elements.
 */
class HtmlExtractionEngine(
    private val transformationEngine: TransformationEngine = TransformationEngine()
) {

    fun parseDocument(
        html: String,
        finalUrl: String,
        @Suppress("UNUSED_PARAMETER") allowedCatalogHosts: Set<String> = emptySet()
    ): Document {
        val doc = Jsoup.parse(html, finalUrl)
        // Never allow page-provided <base> tags to hijack URL resolution or expand host permissions.
        val baseElements = doc.select("base[href]")
        if (baseElements.isNotEmpty()) {
            baseElements.remove()
            doc.setBaseUri(finalUrl)
        }
        return doc
    }

    fun extractEmbeddedJson(
        html: String,
        finalUrl: String,
        embeddedJson: EmbeddedJsonDef,
        allowedCatalogHosts: Set<String> = emptySet()
    ): String {
        val doc = parseDocument(html, finalUrl, allowedCatalogHosts)
        val elements = selectSafe(doc, embeddedJson.selector)
        val scriptElement = elements.firstOrNull()
            ?: throw HarborException(
                HarborErrorCode.SOURCE_LAYOUT_CHANGED,
                "Embedded JSON selector '${embeddedJson.selector}' matched no elements"
            )

        val content = when (embeddedJson.read) {
            "data" -> scriptElement.data()
            "text" -> scriptElement.text()
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported embeddedJson read mode '${embeddedJson.read}'"
            )
        }.trim()

        if (content.isEmpty()) {
            throw HarborException(
                HarborErrorCode.SOURCE_LAYOUT_CHANGED,
                "Embedded JSON element '${embeddedJson.selector}' contained no JSON data"
            )
        }
        return content
    }

    fun extractItems(
        html: String,
        finalUrl: String,
        itemsDef: ItemsExtractionDef,
        paginationDef: PaginationDef? = null,
        maxItemsCap: Int = itemsDef.limit,
        allowedCatalogHosts: Set<String> = emptySet()
    ): CollectionExtractionOutput {
        val selector = itemsDef.selector?.takeIf { it.isNotBlank() }
            ?: throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "HTML item extraction requires a non-blank CSS selector"
            )
        val doc = parseDocument(html, finalUrl, allowedCatalogHosts)
        val elements = selectSafe(doc, selector)
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

        val nextCursor = extractNextLink(doc, paginationDef, finalUrl)
        return CollectionExtractionOutput(
            records = accepted,
            itemsRejected = rejected,
            missingRequiredFields = missingRequired.toList(),
            nextCursor = nextCursor
        )
    }

    fun extractDetails(
        html: String,
        finalUrl: String,
        fields: Map<String, FieldExtractionDef>,
        allowedCatalogHosts: Set<String> = emptySet()
    ): SingleExtractionOutput {
        val doc = parseDocument(html, finalUrl, allowedCatalogHosts)
        val result = extractFieldsMap(doc, fields, finalUrl)
        if (result.missingRequiredFields.isNotEmpty()) {
            throw HarborException(
                HarborErrorCode.REQUIRED_FIELD_MISSING,
                "Missing required HTML detail fields: ${result.missingRequiredFields.joinToString(", ")}"
            )
        }
        return result
    }

    fun extractFields(
        html: String,
        finalUrl: String,
        fields: Map<String, FieldExtractionDef>,
        allowedCatalogHosts: Set<String> = emptySet()
    ): SingleExtractionOutput {
        return extractDetails(html, finalUrl, fields, allowedCatalogHosts)
    }

    fun extractCandidates(
        html: String,
        finalUrl: String,
        candidatesDef: CandidatesExtractionDef,
        maxCandidatesCap: Int = candidatesDef.limit,
        allowedCatalogHosts: Set<String> = emptySet()
    ): CollectionExtractionOutput {
        val selector = candidatesDef.selector?.takeIf { it.isNotBlank() }
            ?: throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "HTML candidate extraction requires a non-blank CSS selector"
            )
        val doc = parseDocument(html, finalUrl, allowedCatalogHosts)
        val elements = selectSafe(doc, selector)
        val cap = minOf(candidatesDef.limit, maxCandidatesCap).coerceAtLeast(0)

        val accepted = mutableListOf<ExtractedRecord>()
        var rejected = 0
        val missingRequired = linkedSetOf<String>()

        for (element in elements) {
            if (accepted.size >= cap) break
            val result = extractFieldsMap(element, candidatesDef.fields, finalUrl)
            if (result.missingRequiredFields.isNotEmpty()) {
                rejected++
                missingRequired.addAll(result.missingRequiredFields)
            } else {
                val urls = result.record.fields["url"].orEmpty()
                if (urls.size <= 1) {
                    accepted.add(result.record)
                } else {
                    for (i in urls.indices) {
                        if (accepted.size >= cap) break
                        val expandedFields = LinkedHashMap<String, List<String>>(result.record.fields.size)
                        expandedFields["url"] = listOf(urls[i])
                        for ((fieldName, fieldValues) in result.record.fields) {
                            if (fieldName == "url" || fieldValues.isEmpty()) continue
                            if (fieldName == "subtitleUrls") {
                                expandedFields[fieldName] = fieldValues
                            } else {
                                expandedFields[fieldName] = listOf(fieldValues.getOrNull(i) ?: fieldValues.first())
                            }
                        }
                        accepted.add(ExtractedRecord(expandedFields))
                    }
                }
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

    private fun extractNextLink(
        doc: Document,
        paginationDef: PaginationDef?,
        finalUrl: String
    ): String? {
        if (paginationDef == null || paginationDef.mode != "nextLink") return null
        val nextDef = paginationDef.next ?: return null
        val extracted = extractSingleField(doc, nextDef, finalUrl)
        return extracted.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun extractFieldsMap(
        root: Element,
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
        root: Element,
        field: FieldExtractionDef,
        finalUrl: String
    ): List<String> {
        val targets = if (field.selector.isNullOrBlank()) {
            listOf(root)
        } else {
            selectSafe(root, field.selector)
        }

        val readMode = field.read ?: "text"
        when (field.cardinality) {
            "one" -> {
                for (el in targets) {
                    val raw = readElementValue(el, readMode, field.attribute)
                    if (raw.isBlank()) continue
                    val transformed = if (field.transforms.isEmpty()) {
                        listOf(raw)
                    } else {
                        transformationEngine.apply(listOf(raw), field.transforms, contextUrl = finalUrl)
                    }
                    if (transformed.any { it.isNotBlank() }) {
                        return transformed
                    }
                }
                if (field.fallback != null) {
                    val fallbackValues = extractSingleField(root, field.fallback, finalUrl)
                    if (fallbackValues.isEmpty()) return emptyList()
                    return if (field.transforms.isEmpty()) {
                        fallbackValues
                    } else {
                        transformationEngine.apply(fallbackValues, field.transforms, contextUrl = finalUrl)
                    }
                }
                return emptyList()
            }
            "many" -> {
                val rawValues = targets.mapNotNull { el ->
                    readElementValue(el, readMode, field.attribute).takeIf { it.isNotBlank() }
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
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported field cardinality '${field.cardinality}'"
            )
        }
    }

    private fun readElementValue(element: Element, readMode: String, attributeName: String?): String {
        return when (readMode) {
            "text" -> element.text()
            "ownText" -> element.ownText()
            "data" -> element.data().ifBlank { element.html() }
            "html" -> element.html()
            "attribute" -> {
                val attr = attributeName?.takeIf { it.isNotBlank() }
                    ?: throw HarborException(
                        HarborErrorCode.DEFINITION_INVALID,
                        "HTML field with read='attribute' requires 'attribute' name"
                    )
                element.attr(attr)
            }
            else -> throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Unsupported HTML read mode '$readMode'"
            )
        }
    }

    private fun selectSafe(root: Element, selector: String): List<Element> {
        return try {
            root.select(selector)
        } catch (e: Selector.SelectorParseException) {
            throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "Invalid CSS selector '$selector': ${e.message}",
                cause = e
            )
        } catch (e: IllegalStateException) {
            throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "Invalid CSS selector '$selector': ${e.message}",
                cause = e
            )
        } catch (e: IllegalArgumentException) {
            throw HarborException(
                HarborErrorCode.SELECTOR_INVALID,
                "Invalid CSS selector '$selector': ${e.message}",
                cause = e
            )
        }
    }
}
