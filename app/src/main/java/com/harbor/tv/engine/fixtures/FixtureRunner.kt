package com.harbor.tv.engine.fixtures

import com.harbor.tv.core.model.FixtureRunResult
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.model.SourceDiagnosticsReport
import com.harbor.tv.core.network.HttpResponseData
import com.harbor.tv.core.network.HttpTransport
import com.harbor.tv.core.security.CredentialRedactor
import com.harbor.tv.engine.definition.FixtureDefinition
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.extract.ExtractedRecord
import com.harbor.tv.engine.extract.UnifiedOperationExtractor
import com.harbor.tv.engine.policy.RequestPolicyEngine
import kotlinx.coroutines.runBlocking

/**
 * Deterministic offline HTTP transport used by [FixtureRunner].
 * Serves only pre-recorded responses and throws [HarborException] with
 * [HarborErrorCode.DESTINATION_BLOCKED] on any unrecorded network access.
 */
class OfflineFixtureHttpTransport(
    private val recordedResponsesByUrl: Map<String, String>,
    private val policyEngine: RequestPolicyEngine = RequestPolicyEngine(),
    private val queuedStepPayloads: ArrayDeque<String> = ArrayDeque(),
    private val fallbackUrlLookup: ((String) -> String?)? = null
) : HttpTransport {

    override suspend fun executeGet(
        url: String,
        definition: SourceDefinition,
        purpose: HostPurpose,
        extraHeaders: Map<String, String>,
        userAllowLan: Boolean
    ): HttpResponseData {
        policyEngine.mergeAndValidateHeaders(definition = definition, extraHeaders = extraHeaders)
        policyEngine.validateUrl(
            url = url,
            definition = definition,
            purpose = purpose,
            userAllowLan = userAllowLan,
            resolveDns = false
        )

        val body = recordedResponsesByUrl[url]
            ?: (if (queuedStepPayloads.isNotEmpty()) queuedStepPayloads.removeFirst() else null)
            ?: fallbackUrlLookup?.invoke(url)
            ?: throw HarborException(
                HarborErrorCode.DESTINATION_BLOCKED,
                "Offline fixture isolation blocked unrecorded network access to '$url'",
                sourceId = definition.source.id
            )

        return HttpResponseData(
            requestedUrl = url,
            finalUrl = url,
            statusCode = 200,
            headers = emptyMap(),
            body = body
        )
    }
}

/**
 * Executes all declared [FixtureDefinition] entries on a validated [SourceDefinition]
 * completely offline and returns a sanitized [SourceDiagnosticsReport].
 */
class FixtureRunner(
    private val extractor: UnifiedOperationExtractor = UnifiedOperationExtractor(),
    private val policyEngine: RequestPolicyEngine = RequestPolicyEngine(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    fun runFixturesBlocking(
        definition: SourceDefinition,
        fixtureLookup: (String) -> String
    ): SourceDiagnosticsReport = runBlocking {
        runFixtures(definition, fixtureLookup)
    }

    suspend fun runFixtures(
        definition: SourceDefinition,
        fixtureLookup: (String) -> String
    ): SourceDiagnosticsReport {
        val results = mutableListOf<FixtureRunResult>()
        val blockedDestinations = mutableListOf<String>()
        val allMissingFields = linkedSetOf<String>()
        var totalItemsExtracted = 0
        var totalItemsRejected = 0
        var totalCandidatesExtracted = 0

        for (fixture in definition.fixtures) {
            val result = runSingleFixture(definition, fixture, fixtureLookup, blockedDestinations)
            results.add(result)
            totalItemsExtracted += result.itemsExtracted
            totalItemsRejected += result.itemsRejected
            totalCandidatesExtracted += result.candidatesExtracted
            allMissingFields.addAll(result.missingFields)
        }

        val failedResult = results.firstOrNull { !it.passed }
        val sanitizedFailure = failedResult?.message?.let { CredentialRedactor.redact(it) }
        val now = clockMs()

        return SourceDiagnosticsReport(
            sourceId = definition.source.id,
            activeVersion = definition.source.version,
            lastSuccessfulRequestMs = if (results.isNotEmpty() && failedResult == null) now else null,
            lastSuccessfulParseMs = if (results.isNotEmpty() && failedResult == null) now else null,
            itemsExtracted = totalItemsExtracted,
            itemsRejected = totalItemsRejected,
            missingRequiredFields = allMissingFields.toList(),
            blockedDestinations = blockedDestinations.distinct(),
            playbackCandidateCount = totalCandidatesExtracted,
            sanitizedFailureReason = sanitizedFailure,
            errorCode = failedResult?.errorCode,
            fixtureResults = results,
            referenceRepository = definition.settingsReference?.repository,
            pinnedCommit = definition.settingsReference?.pinnedCommit,
            normalizedSourceStatus = definition.settingsReference?.sourceStatus
        )
    }

    private suspend fun runSingleFixture(
        definition: SourceDefinition,
        fixture: FixtureDefinition,
        fixtureLookup: (String) -> String,
        blockedDestinations: MutableList<String>
    ): FixtureRunResult {
        return try {
            val initialFile = fixture.stepFiles[definition.operations.playback?.steps?.firstOrNull()?.id]
                ?.takeIf { fixture.operation == "playback" }
                ?: fixture.responseFile
            val payload = fixtureLookup(initialFile)
            when (fixture.operation) {
                "categories" -> evaluateCategoriesFixture(definition, fixture, payload)
                "listing" -> evaluateListingFixture(definition, fixture, "listing", payload)
                "search" -> evaluateListingFixture(definition, fixture, "search", payload)
                "details" -> evaluateDetailsFixture(definition, fixture, payload)
                "playback" -> evaluatePlaybackFixture(definition, fixture, payload, fixtureLookup)
                else -> FixtureRunResult(
                    fixtureId = fixture.id,
                    operation = fixture.operation,
                    passed = false,
                    itemsExtracted = 0,
                    itemsRejected = 0,
                    candidatesExtracted = 0,
                    missingFields = emptyList(),
                    errorCode = HarborErrorCode.DEFINITION_INVALID,
                    message = CredentialRedactor.redact("Unsupported fixture operation '${fixture.operation}'")
                )
            }
        } catch (e: HarborException) {
            if (e.code == HarborErrorCode.DESTINATION_BLOCKED) {
                blockedDestinations.add(CredentialRedactor.redact(e.message))
            }
            FixtureRunResult(
                fixtureId = fixture.id,
                operation = fixture.operation,
                passed = false,
                itemsExtracted = 0,
                itemsRejected = 0,
                candidatesExtracted = 0,
                missingFields = emptyList(),
                errorCode = e.code,
                message = CredentialRedactor.redact(e.message)
            )
        } catch (e: Exception) {
            FixtureRunResult(
                fixtureId = fixture.id,
                operation = fixture.operation,
                passed = false,
                itemsExtracted = 0,
                itemsRejected = 0,
                candidatesExtracted = 0,
                missingFields = emptyList(),
                errorCode = HarborErrorCode.DEFINITION_INVALID,
                message = CredentialRedactor.redact(e.message ?: "Unexpected fixture execution error")
            )
        }
    }

    private fun evaluateCategoriesFixture(
        definition: SourceDefinition,
        fixture: FixtureDefinition,
        payload: String
    ): FixtureRunResult {
        val op = definition.operations.categories
            ?: throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Operation 'categories' is not defined",
                sourceId = definition.source.id,
                operation = "categories"
            )
        val requestUrl = policyEngine.buildInterpolatedUrl(
            requestDef = op.request,
            definition = definition,
            variables = buildInputVariables(fixture.input),
            allowedVariables = setOf("source.baseUrl")
        )
        policyEngine.validateUrl(
            url = requestUrl,
            definition = definition,
            purpose = HostPurpose.CATALOG,
            resolveDns = false
        )

        val extracted = extractor.extractCategories(definition, payload, requestUrl)
        return checkExpectedItems(
            fixture = fixture,
            itemsExtracted = extracted.categories.size,
            itemsRejected = extracted.itemsRejected,
            records = extracted.records,
            extractorMissing = extracted.missingRequiredFields
        )
    }

    private fun evaluateListingFixture(
        definition: SourceDefinition,
        fixture: FixtureDefinition,
        operationName: String,
        payload: String
    ): FixtureRunResult {
        val op = when (operationName) {
            "listing" -> definition.operations.listing
            "search" -> definition.operations.search
            else -> null
        } ?: throw HarborException(
            HarborErrorCode.UNSUPPORTED_CAPABILITY,
            "Operation '$operationName' is not defined",
            sourceId = definition.source.id,
            operation = operationName
        )

        val allowedVars = if (operationName == "search") {
            setOf("source.baseUrl", "input.query")
        } else {
            setOf("source.baseUrl", "input.url")
        }
        val requestUrl = policyEngine.buildInterpolatedUrl(
            requestDef = op.request,
            definition = definition,
            variables = buildInputVariables(fixture.input),
            allowedVariables = allowedVars
        )
        policyEngine.validateUrl(
            url = requestUrl,
            definition = definition,
            purpose = HostPurpose.CATALOG,
            resolveDns = false
        )

        val extracted = extractor.extractListing(definition, operationName, payload, requestUrl)
        return checkExpectedItems(
            fixture = fixture,
            itemsExtracted = extracted.listing.items.size,
            itemsRejected = extracted.listing.totalRejected,
            records = extracted.records,
            extractorMissing = extracted.missingRequiredFields
        )
    }

    private fun evaluateDetailsFixture(
        definition: SourceDefinition,
        fixture: FixtureDefinition,
        payload: String
    ): FixtureRunResult {
        val op = definition.operations.details
            ?: throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Operation 'details' is not defined",
                sourceId = definition.source.id,
                operation = "details"
            )
        val requestUrl = policyEngine.buildInterpolatedUrl(
            requestDef = op.request,
            definition = definition,
            variables = buildInputVariables(fixture.input),
            allowedVariables = setOf("source.baseUrl", "input.detailUrl")
        )
        policyEngine.validateUrl(
            url = requestUrl,
            definition = definition,
            purpose = HostPurpose.CATALOG,
            resolveDns = false
        )

        val extracted = extractor.extractDetails(definition, payload, requestUrl)
        return checkExpectedItems(
            fixture = fixture,
            itemsExtracted = 1,
            itemsRejected = 0,
            records = listOf(extracted.record),
            extractorMissing = extracted.missingRequiredFields
        )
    }

    private suspend fun evaluatePlaybackFixture(
        definition: SourceDefinition,
        fixture: FixtureDefinition,
        payload: String,
        fixtureLookup: (String) -> String
    ): FixtureRunResult {
        val op = definition.operations.playback
            ?: throw HarborException(
                HarborErrorCode.UNSUPPORTED_CAPABILITY,
                "Operation 'playback' is not defined",
                sourceId = definition.source.id,
                operation = "playback"
            )
        val detailUrl = fixture.input["detailUrl"] ?: fixture.input["input.detailUrl"]
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Playback fixture '${fixture.id}' requires 'input.detailUrl'",
                sourceId = definition.source.id,
                operation = "playback"
            )

        val firstStep = op.steps.firstOrNull()
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Playback operation has no steps",
                sourceId = definition.source.id,
                operation = "playback"
            )
        val firstStepUrl = policyEngine.buildInterpolatedUrl(
            requestDef = firstStep.request,
            definition = definition,
            variables = mapOf("input.detailUrl" to detailUrl),
            allowedVariables = setOf("source.baseUrl", "input.detailUrl")
        )
        val queuedSteps = ArrayDeque<String>()
        for (step in op.steps.drop(1)) {
            val stepFile = fixture.stepFiles[step.id]
            if (stepFile != null) {
                queuedSteps.addLast(fixtureLookup(stepFile))
            }
        }
        val offlineTransport = OfflineFixtureHttpTransport(
            recordedResponsesByUrl = mapOf(firstStepUrl to payload),
            policyEngine = policyEngine,
            queuedStepPayloads = queuedSteps,
            fallbackUrlLookup = { requestedUrl -> runCatching { fixtureLookup(requestedUrl) }.getOrNull() }
        )

        val result = extractor.executePlayback(
            definition = definition,
            detailUrl = detailUrl,
            transport = offlineTransport
        )
        for (candidate in result.candidates) {
            policyEngine.validateUrl(
                url = candidate.url,
                definition = definition,
                purpose = HostPurpose.MEDIA,
                resolveDns = false
            )
        }

        val missingFields = linkedSetOf<String>()
        missingFields.addAll(result.missingRequiredFields)
        for (reqField in fixture.expected.requiredFields) {
            if (result.candidateRecords.any { !it.hasField(reqField) }) {
                missingFields.add(reqField)
            }
        }

        val minCandidates = fixture.expected.minimumCandidates
        val countInsufficient = minCandidates != null && result.candidates.size < minCandidates
        val passed = missingFields.isEmpty() && !countInsufficient

        val (errorCode, message) = when {
            missingFields.isNotEmpty() -> HarborErrorCode.REQUIRED_FIELD_MISSING to
                "Missing required playback fields: ${missingFields.joinToString(", ")}"
            countInsufficient -> HarborErrorCode.PLAYBACK_NOT_FOUND to
                "Extracted ${result.candidates.size} playback candidates, expected at least $minCandidates"
            else -> null to "Passed (${result.candidates.size} candidates)"
        }

        return FixtureRunResult(
            fixtureId = fixture.id,
            operation = fixture.operation,
            passed = passed,
            itemsExtracted = 0,
            itemsRejected = result.candidatesRejected,
            candidatesExtracted = result.candidates.size,
            missingFields = missingFields.toList(),
            errorCode = errorCode,
            message = CredentialRedactor.redact(message)
        )
    }

    private fun checkExpectedItems(
        fixture: FixtureDefinition,
        itemsExtracted: Int,
        itemsRejected: Int,
        records: List<ExtractedRecord>,
        extractorMissing: List<String>
    ): FixtureRunResult {
        val missingFields = linkedSetOf<String>()
        missingFields.addAll(extractorMissing)
        for (reqField in fixture.expected.requiredFields) {
            if (records.isEmpty() || records.any { !it.hasField(reqField) }) {
                missingFields.add(reqField)
            }
        }

        val minItems = fixture.expected.minimumItems
        val countInsufficient = minItems != null && itemsExtracted < minItems
        val passed = missingFields.isEmpty() && !countInsufficient

        val (errorCode, message) = when {
            missingFields.isNotEmpty() -> HarborErrorCode.REQUIRED_FIELD_MISSING to
                "Missing required fields: ${missingFields.joinToString(", ")}"
            countInsufficient -> HarborErrorCode.SOURCE_LAYOUT_CHANGED to
                "Extracted $itemsExtracted items, expected at least $minItems"
            else -> null to "Passed ($itemsExtracted items)"
        }

        return FixtureRunResult(
            fixtureId = fixture.id,
            operation = fixture.operation,
            passed = passed,
            itemsExtracted = itemsExtracted,
            itemsRejected = itemsRejected,
            candidatesExtracted = 0,
            missingFields = missingFields.toList(),
            errorCode = errorCode,
            message = CredentialRedactor.redact(message)
        )
    }

    private fun buildInputVariables(input: Map<String, String>): Map<String, String> {
        val vars = LinkedHashMap<String, String>(input.size)
        for ((key, value) in input) {
            val normalizedKey = if (key.contains('.')) key else "input.$key"
            vars[normalizedKey] = value
        }
        return vars
    }
}
