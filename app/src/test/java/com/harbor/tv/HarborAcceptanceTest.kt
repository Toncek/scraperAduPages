package com.harbor.tv

import com.harbor.tv.core.model.CatalogItem
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.HostPurpose
import com.harbor.tv.core.model.MetadataCompleteness
import com.harbor.tv.core.model.PlaybackCandidate
import com.harbor.tv.core.model.PlaybackStreamType
import com.harbor.tv.core.model.PreferenceConfig
import com.harbor.tv.core.model.QualityPreference
import com.harbor.tv.core.model.TagPreferenceState
import com.harbor.tv.core.model.UnknownMetadataMode
import com.harbor.tv.core.model.UserProfile
import com.harbor.tv.core.model.UserTasteProfile
import com.harbor.tv.core.network.ControlledHttpTransport
import com.harbor.tv.core.network.HttpResponseData
import com.harbor.tv.core.network.HttpTransport
import com.harbor.tv.core.security.AddressSafetyValidator
import com.harbor.tv.core.security.CredentialRedactor
import com.harbor.tv.domain.catalog.CatalogNormalizer
import com.harbor.tv.domain.playback.PlaybackResolver
import com.harbor.tv.domain.preferences.PreferenceFilterEngine
import com.harbor.tv.domain.preferences.UserTasteEngine
import com.harbor.tv.domain.sources.MagicPhSettingsAdapter
import com.harbor.tv.engine.definition.DefinitionImporter
import com.harbor.tv.engine.definition.RequestDef
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.definition.TransformDef
import com.harbor.tv.engine.extract.HtmlExtractionEngine
import com.harbor.tv.engine.extract.UnifiedOperationExtractor
import com.harbor.tv.engine.fixtures.FixtureRunner
import com.harbor.tv.engine.fixtures.OfflineFixtureHttpTransport
import com.harbor.tv.engine.policy.RequestPolicyEngine
import com.harbor.tv.engine.transform.TransformationEngine
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress

class HarborAcceptanceTest {

    private val importer = DefinitionImporter()
    private val transformationEngine = TransformationEngine()
    private val fixtureRunner = FixtureRunner()
    private val filterEngine = PreferenceFilterEngine()

    private val bundledDefinitionFiles = listOf(
        "definitions/example-library.yaml",
        "definitions/eporner-api-v2.yaml",
        "definitions/redtube-api.yaml",
        "definitions/pornhub-webmaster.yaml",
        "definitions/chaturbate-affiliates.yaml",
        "definitions/embedded-json-catalog.yaml",
        "definitions/intermediate-embed-demo.yaml",
        "definitions/xnxx-html.yaml",
        "definitions/xvideos-html.yaml",
        "definitions/porntrex-html.yaml",
        "definitions/xhamster-html.yaml"
    )

    private fun resolveAssetsDir(): File {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Unable to locate app/src/main/assets directory from ${File(".").absolutePath}")
    }

    private fun readAsset(relativePath: String): String {
        val file = File(resolveAssetsDir(), relativePath)
        assertTrue("Expected asset file to exist: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun loadValidatedDefinition(assetPath: String): SourceDefinition {
        val result = importer.importAndValidate(readAsset(assetPath))
        assertTrue(
            "Expected '$assetPath' to validate cleanly, but got issues: ${result.issues}",
            result.isValid
        )
        return requireNotNull(result.definition)
    }

    // -------------------------------------------------------------------------
    // Section 25.1: Bundled Asset Conformance & Offline Fixture Isolation
    // -------------------------------------------------------------------------

    @Test
    fun bundledDefinitions_validateAndPassAllOfflineFixtures(): Unit = runBlocking {
        assertEquals(11, bundledDefinitionFiles.size)
        for (defPath in bundledDefinitionFiles) {
            val definition = loadValidatedDefinition(defPath)
            val report = fixtureRunner.runFixtures(definition) { fixturePath ->
                readAsset(fixturePath)
            }
            assertTrue(
                "Expected fixtures for '$defPath' to be non-empty",
                report.fixtureResults.isNotEmpty()
            )
            for (res in report.fixtureResults) {
                assertTrue(
                    "Fixture '${res.fixtureId}' in '$defPath' failed (${res.errorCode}): ${res.message}",
                    res.passed
                )
            }
            assertEquals(
                "Expected zero diagnostic errors for '$defPath', got ${report.sanitizedFailureReason}",
                null,
                report.errorCode
            )
        }
    }

    @Test
    fun offlineFixtureTransport_blocksUnrecordedNetworkRequests(): Unit = runBlocking {
        val definition = loadValidatedDefinition("definitions/example-library.yaml")
        val offlineTransport = OfflineFixtureHttpTransport(
            recordedResponsesByUrl = mapOf("https://video.example.org/categories" to "<html></html>")
        )
        val error = expectHarborException(HarborErrorCode.DESTINATION_BLOCKED) {
            offlineTransport.executeGet(
                url = "https://video.example.org/unrecorded",
                definition = definition,
                purpose = HostPurpose.CATALOG
            )
        }
        assertTrue(error.message.contains("unrecorded", ignoreCase = true))
    }

    @Test
    fun htmlExtractorAndTransforms_ignoreUntrustedBaseTagsAndCanonicalizeUrls() {
        val html = """
            <html>
              <head><base href="https://attacker.example.net/hijack/"></head>
              <body>
                <article class="card">
                  <a class="link" href="/watch/42?ref=home#player">Watch</a>
                </article>
              </body>
            </html>
        """.trimIndent()
        val htmlExtractor = HtmlExtractionEngine(transformationEngine)
        val doc = htmlExtractor.parseDocument(html, "https://video.example.org/categories/nature")
        assertEquals("https://video.example.org/categories/nature", doc.baseUri())

        val resolved = transformationEngine.resolveUrl(
            rawUrl = "/watch/42?ref=home#player",
            contextUrl = "https://video.example.org/categories/nature"
        )
        assertEquals("https://video.example.org/watch/42?ref=home#player", resolved)

        val canonical = transformationEngine.canonicalUrl("HTTPS://VIDEO.Example.ORG/watch/42/?b=2&a=1#frag")
        assertEquals("https://video.example.org/watch/42/?b=2&a=1", canonical)
    }

    // -------------------------------------------------------------------------
    // Section 25.2: Security & Policy Tests
    // -------------------------------------------------------------------------

    @Test
    fun requestPolicy_blocksUnknownHostsAndWildcardSubdomains() {
        val definition = loadValidatedDefinition("definitions/example-library.yaml")
        val policy = RequestPolicyEngine(dnsResolver = { listOf(InetAddress.getByName("93.184.216.34")) })

        expectHarborException(HarborErrorCode.DESTINATION_BLOCKED) {
            policy.validateUrl("https://evil.example.net/api", definition, HostPurpose.CATALOG)
        }
        expectHarborException(HarborErrorCode.DESTINATION_BLOCKED) {
            policy.validateUrl("https://sub.video.example.org/api", definition, HostPurpose.CATALOG)
        }
        expectHarborException(HarborErrorCode.DESTINATION_BLOCKED) {
            policy.validateUrl("http://video.example.org/categories", definition, HostPurpose.CATALOG)
        }
    }

    @Test
    fun requestPolicy_blocksPrivateLoopbackLinkLocalUnlessLanEnabled() {
        val publicDef = loadValidatedDefinition("definitions/example-library.yaml")
        val lanDef = publicDef.copy(network = publicDef.network.copy(allowLan = true))

        val unsafeAddresses = listOf(
            "127.0.0.1",
            "10.0.0.1",
            "172.16.0.5",
            "192.168.1.20",
            "169.254.169.254",
            "::1",
            "fc00::1",
            "fe80::1"
        )
        for (ip in unsafeAddresses) {
            val addr = InetAddress.getByName(ip)
            assertFalse(
                "Expected $ip to be blocked when allowLan=false",
                AddressSafetyValidator.isSafePublicAddress("video.example.org", listOf(addr), allowLan = false)
            )
            val policy = RequestPolicyEngine(dnsResolver = { listOf(addr) })
            expectHarborException(HarborErrorCode.DESTINATION_BLOCKED) {
                policy.validateUrl("https://video.example.org/test", publicDef, HostPurpose.CATALOG, userAllowLan = false)
            }
        }

        val lanPolicy = RequestPolicyEngine(dnsResolver = { listOf(InetAddress.getByName("192.168.1.20")) })
        expectHarborException(HarborErrorCode.DESTINATION_BLOCKED) {
            lanPolicy.validateUrl("https://video.example.org/test", lanDef, HostPurpose.CATALOG, userAllowLan = false)
        }
        val allowed = lanPolicy.validateUrl(
            "https://video.example.org/test",
            lanDef,
            HostPurpose.CATALOG,
            userAllowLan = true
        )
        assertEquals(1, allowed.size)
    }

    @Test
    fun controlledTransport_enforcesRedirectsCrossOriginHeaderStrippingAndByteLimit(): Unit = runBlocking {
        val baseDef = loadValidatedDefinition("definitions/example-library.yaml")
        val server = MockWebServer()
        server.start()
        try {
            val loopback = InetAddress.getByName("127.0.0.1")
            val testClient = OkHttpClient.Builder()
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> = listOf(loopback)
                })
                .build()
            val policy = RequestPolicyEngine(dnsResolver = { listOf(loopback) })
            val transport = ControlledHttpTransport(policy, testClient)

            val port = server.port
            val httpAllowedDef = baseDef.copy(
                network = baseDef.network.copy(
                    httpsOnly = false,
                    allowLan = true,
                    allowedHosts = baseDef.network.allowedHosts.copy(
                        catalog = listOf("api.example.org", "cdn.example.org")
                    ),
                    redirects = baseDef.network.redirects.copy(maxHops = 2),
                    limits = baseDef.network.limits.copy(
                        minRequestIntervalMs = 0L,
                        maxCatalogResponseBytes = 64L
                    )
                )
            )

            // 1. Cross-origin redirect between two approved hosts strips sensitive headers
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://cdn.example.org:$port/final")
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            val res = transport.executeGet(
                url = "http://api.example.org:$port/start",
                definition = httpAllowedDef,
                purpose = HostPurpose.CATALOG,
                extraHeaders = mapOf("X-Api-Key" to "secret-token", "Accept" to "application/json"),
                userAllowLan = true
            )
            assertEquals("ok", res.body)
            val hop1 = server.takeRequest()
            val hop2 = server.takeRequest()
            assertEquals("secret-token", hop1.getHeader("X-Api-Key"))
            assertEquals(null, hop2.getHeader("X-Api-Key"))
            assertEquals("application/json", hop2.getHeader("Accept"))

            // 2. Redirect to unapproved host is blocked
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "http://evil.example.net:$port/steal")
            )
            expectHarborException(HarborErrorCode.DESTINATION_BLOCKED) {
                transport.executeGet(
                    url = "http://api.example.org:$port/start",
                    definition = httpAllowedDef,
                    purpose = HostPurpose.CATALOG,
                    userAllowLan = true
                )
            }
            server.takeRequest()

            // 3. Exceeding maxCatalogResponseBytes fails with RESPONSE_TOO_LARGE
            server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(128)))
            expectHarborException(HarborErrorCode.RESPONSE_TOO_LARGE) {
                transport.executeGet(
                    url = "http://api.example.org:$port/huge",
                    definition = httpAllowedDef,
                    purpose = HostPurpose.CATALOG,
                    userAllowLan = true
                )
            }
            server.takeRequest()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun requestPolicy_rejectsForbiddenHeadersUndeclaredVarsAndEncodesQuery() {
        val definition = loadValidatedDefinition("definitions/example-library.yaml")
        val policy = RequestPolicyEngine()

        for (forbidden in listOf("Cookie", "Authorization", "Host", "Content-Length", "Connection", "Proxy-Authorization")) {
            expectHarborException(HarborErrorCode.POLICY_BLOCKED) {
                policy.validateHeaders(mapOf(forbidden to "value"))
            }
        }
        expectHarborException(HarborErrorCode.DEFINITION_INVALID) {
            policy.validateHeaders(mapOf("X-Custom" to "bad\r\nInjected: 1"))
        }

        val request = RequestDef(
            method = "GET",
            url = "\${source.baseUrl}/search",
            query = mapOf("q" to "\${input.query}")
        )
        val built = policy.buildInterpolatedUrl(
            requestDef = request,
            definition = definition,
            variables = mapOf("input.query" to "space & stars"),
            allowedVariables = setOf("source.baseUrl", "input.query")
        )
        assertEquals("https://video.example.org/search?q=space%20%26%20stars", built)

        expectHarborException(HarborErrorCode.DEFINITION_INVALID) {
            policy.buildInterpolatedUrl(
                requestDef = RequestDef(url = "\${source.baseUrl}/\${input.secret}"),
                definition = definition,
                variables = mapOf("input.secret" to "123"),
                allowedVariables = setOf("source.baseUrl")
            )
        }
    }

    @Test
    fun safeParserAndValidator_rejectMaliciousYamlCyclicReuseUnknownTransformsAndLiveDuration() {
        val baseYaml = readAsset("definitions/example-library.yaml")

        // Malicious YAML tag
        val taggedYaml = baseYaml.replace("name: \"Example Video Library\"", "name: !!java.lang.Runtime \"x\"")
        assertFalse(importer.importAndValidate(taggedYaml).isValid)

        // Duplicate YAML key
        val dupKeyYaml = baseYaml.replace("httpsOnly: true", "httpsOnly: true\n  httpsOnly: false")
        assertFalse(importer.importAndValidate(dupKeyYaml).isValid)

        // Excessive YAML aliases (> 25)
        val anchorYaml = buildString {
            appendLine("schemaVersion: \"1.0\"")
            appendLine("a: &anchor \"val\"")
            repeat(30) { idx -> appendLine("k$idx: *anchor") }
        }
        assertFalse(importer.importAndValidate(anchorYaml).isValid)

        // Cyclic reuse
        val cyclicYaml = baseYaml.replace(
            "extractFrom: \"listing\"",
            "extractFrom: \"search\""
        )
        val cyclicRes = importer.importAndValidate(cyclicYaml)
        assertFalse(cyclicRes.isValid)
        assertTrue(cyclicRes.issues.any { it.message.contains("Cyclic", ignoreCase = true) })

        // Unknown transform
        val unknownTransformYaml = baseYaml.replace("op: \"collapseWhitespace\"", "op: \"evalJs\"")
        assertFalse(importer.importAndValidate(unknownTransformYaml).isValid)

        // LIVE duration must throw DEFINITION_INVALID instead of returning 0
        expectHarborException(HarborErrorCode.DEFINITION_INVALID) {
            transformationEngine.applySingle("LIVE", listOf(TransformDef(op = "parseDuration")))
        }
        assertEquals(860, transformationEngine.parseDurationSeconds("14:20"))
        assertEquals(3661, transformationEngine.parseDurationSeconds("01:01:01"))
        assertEquals(1200, transformationEngine.parseDurationSeconds("20 min"))
        assertEquals(480, transformationEngine.parseDurationSeconds("8min"))
        assertEquals(4320, transformationEngine.parseDurationSeconds("1 h 12 min"))
        assertEquals(750, transformationEngine.parseDurationSeconds("12m 30s"))
        assertEquals(45, transformationEngine.parseDurationSeconds("45 sec"))

        // Redaction check
        val redacted = CredentialRedactor.redact("Failed GET https://user:pass@video.example.org/x?token=abc123 Authorization: Bearer secretToken")
        assertFalse(redacted.contains("pass"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("secretToken"))
    }

    // -------------------------------------------------------------------------
    // Section 25.3: Preference & Exclusion Tests
    // -------------------------------------------------------------------------

    @Test
    fun preferenceEngine_filtersExcludedItemsStripsArtworkEnforcesStrictModeAndRanksPreferred() {
        val enabled = setOf("example-library")
        val excludedItem = CatalogItem(
            sourceId = "example-library",
            sourceItemId = "v1",
            sourcePageUrl = "https://video.example.org/watch/1",
            title = "Excluded Video",
            canonicalTags = listOf("genre.documentary", "topic.banned"),
            thumbnailReferences = listOf("https://images.example.org/1.jpg"),
            metadataCompleteness = MetadataCompleteness.COMPLETE
        )
        val partialItem = CatalogItem(
            sourceId = "example-library",
            sourceItemId = "v2",
            sourcePageUrl = "https://video.example.org/watch/2",
            title = "Partial Video",
            canonicalTags = emptyList(),
            thumbnailReferences = listOf("https://images.example.org/2.jpg"),
            metadataCompleteness = MetadataCompleteness.PARTIAL
        )
        val neutralItem = CatalogItem(
            sourceId = "example-library",
            sourceItemId = "v3",
            sourcePageUrl = "https://video.example.org/watch/3",
            title = "Neutral Video",
            canonicalTags = listOf("genre.educational"),
            thumbnailReferences = listOf("https://images.example.org/3.jpg"),
            metadataCompleteness = MetadataCompleteness.COMPLETE
        )
        val preferredItem = CatalogItem(
            sourceId = "example-library",
            sourceItemId = "v4",
            sourcePageUrl = "https://video.example.org/watch/4",
            title = "Preferred Video",
            canonicalTags = listOf("genre.documentary", "topic.nature"),
            thumbnailReferences = listOf("https://images.example.org/4.jpg"),
            metadataCompleteness = MetadataCompleteness.COMPLETE
        )

        val prefs = PreferenceConfig(
            tagPreferences = mapOf(
                "topic.banned" to TagPreferenceState.EXCLUDED,
                "genre.documentary" to TagPreferenceState.PREFERRED,
                "topic.nature" to TagPreferenceState.PREFERRED
            ),
            unknownMetadataMode = UnknownMetadataMode.STRICT
        )

        val partition = filterEngine.partitionAndStripRejectedArtwork(
            items = listOf(excludedItem, neutralItem, partialItem, preferredItem),
            preferences = prefs,
            enabledSourceIds = enabled
        )

        // Excluded & partial items hidden in strict mode; preferred ranked above neutral
        assertEquals(listOf("v4", "v3"), partition.eligible.map { it.sourceItemId })
        // Excluded item cannot be resurrected even if it also had a preferred tag
        assertFalse(partition.eligible.any { it.sourceItemId == "v1" })
        // Artwork stripped on every rejected item (Section 16.4)
        assertEquals(2, partition.rejectedWithoutArtwork.size)
        assertTrue(partition.rejectedWithoutArtwork.all { it.thumbnailReferences.isEmpty() })
    }

    @Test
    fun minResolutionRestriction_blocksLowResInCatalogAndPlayback(): Unit = runBlocking {
        val heights = listOf(144, 240, 360, 480, 720, 1080, 2160)
        val items = heights.map { h ->
            CatalogItem(
                sourceId = "example-library",
                sourceItemId = "vid-$h",
                sourcePageUrl = "https://video.example.org/watch/vid-$h",
                title = "Sample ${h}p Video",
                canonicalTags = listOf("topic.nature"),
                thumbnailReferences = listOf("https://images.example.org/$h.jpg"),
                metadataCompleteness = MetadataCompleteness.COMPLETE,
                maxQualityLabel = if (h >= 2160) "4K" else "${h}p",
                maxResolutionHeight = h
            )
        }

        val restrictionPrefs = PreferenceConfig(minResolutionHeight = 480)
        val partition = filterEngine.partitionAndStripRejectedArtwork(
            items = items,
            preferences = restrictionPrefs,
            enabledSourceIds = setOf("example-library")
        )

        assertEquals(listOf(480, 720, 1080, 2160), partition.eligible.map { it.maxResolutionHeight })
        assertEquals(setOf(144, 240, 360), partition.rejectedWithoutArtwork.map { it.maxResolutionHeight }.toSet())
        assertTrue(partition.rejectedWithoutArtwork.all { it.thumbnailReferences.isEmpty() })

        val definition = loadValidatedDefinition("definitions/example-library.yaml")
        val targetItem = items.first { it.maxResolutionHeight == 720 }
        val profile = UserProfile(id = "p1", name = "Default", qualityPreference = QualityPreference.HIGHEST_AVAILABLE)

        val mixedTransport = object : HttpTransport {
            override suspend fun executeGet(
                url: String,
                definition: SourceDefinition,
                purpose: HostPurpose,
                extraHeaders: Map<String, String>,
                userAllowLan: Boolean
            ): HttpResponseData {
                val html = """
                    <html><body>
                      <video controls>
                        <source src="https://media.example.org/streams/240p.mp4" type="video/mp4">
                        <source src="https://media.example.org/streams/360p.mp4" type="video/mp4">
                        <source src="https://media.example.org/streams/720p.mp4" type="video/mp4">
                      </video>
                    </body></html>
                """.trimIndent()
                return HttpResponseData(url, url, 200, emptyMap(), html)
            }
        }
        val mixedResult = PlaybackResolver(mixedTransport, resolveMediaDns = false).resolvePlayback(
            item = targetItem,
            definition = definition,
            profile = profile,
            preferences = restrictionPrefs,
            enabledSourceIds = setOf("example-library")
        )
        assertEquals(1, mixedResult.candidates.size)
        assertEquals(720, mixedResult.selectedCandidate?.resolutionHeight)

        val lowOnlyTransport = object : HttpTransport {
            override suspend fun executeGet(
                url: String,
                definition: SourceDefinition,
                purpose: HostPurpose,
                extraHeaders: Map<String, String>,
                userAllowLan: Boolean
            ): HttpResponseData {
                val html = """
                    <html><body>
                      <video controls>
                        <source src="https://media.example.org/streams/240p.mp4" type="video/mp4">
                        <source src="https://media.example.org/streams/360p.mp4" type="video/mp4">
                      </video>
                    </body></html>
                """.trimIndent()
                return HttpResponseData(url, url, 200, emptyMap(), html)
            }
        }
        expectHarborException(HarborErrorCode.POLICY_BLOCKED) {
            PlaybackResolver(lowOnlyTransport, resolveMediaDns = false).resolvePlayback(
                item = targetItem,
                definition = definition,
                profile = profile,
                preferences = restrictionPrefs,
                enabledSourceIds = setOf("example-library")
            )
        }
        Unit
    }

    @Test
    fun userTasteEngine_tracksLabelStatsBuildsTopCombosAndAvoidsRepeats() {
        val tasteEngine = UserTasteEngine()
        var taste = UserTasteProfile(profileId = "taste-test")

        val item4kAmateur1 = CatalogItem(
            sourceId = "src1",
            sourceItemId = "a1",
            sourcePageUrl = "https://video.example.org/watch/a1",
            title = "4K Amateur Session 1",
            sourceTags = listOf("4K", "Amateur"),
            maxResolutionHeight = 2160
        )
        val item4kAmateur2 = CatalogItem(
            sourceId = "src1",
            sourceItemId = "a2",
            sourcePageUrl = "https://video.example.org/watch/a2",
            title = "4K Amateur Session 2",
            sourceTags = listOf("4K", "Amateur"),
            maxResolutionHeight = 2160
        )
        val item1080Nature = CatalogItem(
            sourceId = "src2",
            sourceItemId = "n1",
            sourcePageUrl = "https://video.example.org/watch/n1",
            title = "1080p Nature Documentary",
            sourceTags = listOf("1080p", "Nature"),
            maxResolutionHeight = 1080
        )

        taste = tasteEngine.recordInteraction(taste, item4kAmateur1, weight = 3.0)
        taste = tasteEngine.recordInteraction(taste, item4kAmateur2, weight = 3.0)
        taste = tasteEngine.recordInteraction(taste, item1080Nature, weight = 1.0)

        assertTrue((taste.labelScores["4k"] ?: 0.0) > (taste.labelScores["nature"] ?: 0.0))

        val combos = tasteEngine.buildTopCombinations(taste)
        assertTrue(combos.isNotEmpty())
        val topCombo = combos.first()
        assertEquals(setOf("4k", "amateur"), topCombo.labels.toSet())
        assertTrue(topCombo.searchQuery.contains("4k", ignoreCase = true))
        assertTrue(topCombo.searchQuery.contains("amateur", ignoreCase = true))

        val watched = CatalogItem(
            sourceId = "src1",
            sourceItemId = "w1",
            sourcePageUrl = "https://video.example.org/watch/w1",
            title = "Already Watched 4K Amateur",
            sourceTags = listOf("4K", "Amateur"),
            maxResolutionHeight = 2160
        )
        val freshMatch = CatalogItem(
            sourceId = "src1",
            sourceItemId = "f1",
            sourcePageUrl = "https://video.example.org/watch/f1",
            title = "Fresh 4K Amateur Discovery",
            sourceTags = listOf("4K", "Amateur"),
            maxResolutionHeight = 2160
        )
        val freshNeutral = CatalogItem(
            sourceId = "src2",
            sourceItemId = "f2",
            sourcePageUrl = "https://video.example.org/watch/f2",
            title = "Fresh Neutral Discovery",
            sourceTags = listOf("Documentary"),
            maxResolutionHeight = 1080
        )

        val ranked = tasteEngine.rankFreshAndPersonalized(
            items = listOf(watched, item4kAmateur1, freshNeutral, freshMatch),
            taste = taste,
            watchedCompositeIds = setOf(watched.compositeId),
            avoidRepeats = true,
            excludeWatched = true
        )

        assertFalse(ranked.any { it.compositeId == watched.compositeId })
        assertEquals(
            listOf(freshMatch.compositeId, freshNeutral.compositeId, item4kAmateur1.compositeId),
            ranked.map { it.compositeId }
        )
    }

    // -------------------------------------------------------------------------
    // Section 25.4: Playback & URL Expiry Tests
    // -------------------------------------------------------------------------

    @Test
    fun playbackResolver_classifiesSelectsPreservesSignedQueryAndRetriesOnceOnExpiry(): Unit = runBlocking {
        val definition = loadValidatedDefinition("definitions/example-library.yaml")
        val item = CatalogItem(
            sourceId = "example-library",
            sourceItemId = "alpine-rivers",
            sourcePageUrl = "https://video.example.org/watch/alpine-rivers",
            title = "Alpine Rivers",
            canonicalTags = listOf("topic.nature"),
            metadataCompleteness = MetadataCompleteness.COMPLETE
        )
        var callCount = 0
        val fakeTransport = object : HttpTransport {
            override suspend fun executeGet(
                url: String,
                definition: SourceDefinition,
                purpose: HostPurpose,
                extraHeaders: Map<String, String>,
                userAllowLan: Boolean
            ): HttpResponseData {
                callCount++
                val token = if (callCount == 1) "sig1" else "sig2"
                val html = """
                    <html><body>
                      <video controls>
                        <source src="https://media.example.org/streams/alpine/720p.mp4?token=$token&amp;exp=999" type="video/mp4">
                        <source src="https://media.example.org/streams/alpine/master.m3u8?token=$token&amp;exp=999" type="application/x-mpegURL">
                        <source src="https://media.example.org/streams/alpine/manifest.mpd?token=$token&amp;exp=999" type="application/dash+xml">
                      </video>
                    </body></html>
                """.trimIndent()
                return HttpResponseData(url, url, 200, emptyMap(), html)
            }
        }

        val resolver = PlaybackResolver(fakeTransport, resolveMediaDns = false)
        val profile = UserProfile(id = "p1", name = "Default", qualityPreference = QualityPreference.AUTO_FALLBACK)
        val prefs = PreferenceConfig()

        val initial = resolver.resolvePlayback(item, definition, profile, prefs, setOf("example-library"))
        assertEquals(3, initial.candidates.size)
        assertEquals(
            setOf(PlaybackStreamType.PROGRESSIVE, PlaybackStreamType.HLS, PlaybackStreamType.DASH),
            initial.candidates.map { it.streamType }.toSet()
        )
        assertNotNull(initial.selectedCandidate)
        assertEquals(PlaybackStreamType.HLS, initial.selectedCandidate!!.streamType)
        assertTrue(initial.selectedCandidate!!.url.contains("token=sig1&exp=999"))

        // Quality preference checks
        val resolutionSelected = resolver.selectCandidate(
            listOf(
                PlaybackCandidate("https://media.example.org/1080.mp4", resolutionHeight = 1080, bitrateKbps = 5000),
                PlaybackCandidate("https://media.example.org/720.mp4", resolutionHeight = 720, bitrateKbps = 2500)
            ),
            profile.copy(qualityPreference = QualityPreference.PREFERRED_RESOLUTION, preferredResolutionHeight = 720)
        )
        assertEquals(720, resolutionSelected?.resolutionHeight)

        // 1-time resolveAgainOnExpired preserves identity and updates signed query
        val refreshed = resolver.resolveAgainOnExpired(
            previousResult = initial,
            item = item,
            definition = definition,
            profile = profile,
            preferences = prefs,
            enabledSourceIds = setOf("example-library"),
            attemptNumber = 1
        )
        assertEquals(initial.sourceId, refreshed.sourceId)
        assertEquals(initial.sourceItemId, refreshed.sourceItemId)
        assertTrue(refreshed.selectedCandidate!!.url.contains("token=sig2&exp=999"))

        // Second retry exceeds budget
        expectHarborException(HarborErrorCode.STREAM_EXPIRED) {
            resolver.resolveAgainOnExpired(
                previousResult = refreshed,
                item = item,
                definition = definition,
                profile = profile,
                preferences = prefs,
                enabledSourceIds = setOf("example-library"),
                attemptNumber = 2
            )
        }

        // Identity substitution rejected
        expectHarborException(HarborErrorCode.POLICY_BLOCKED) {
            resolver.resolveAgainOnExpired(
                previousResult = initial,
                item = item.copy(sourceItemId = "different-video"),
                definition = definition,
                profile = profile,
                preferences = prefs,
                enabledSourceIds = setOf("example-library"),
                attemptNumber = 1
            )
        }
    }

    @Test
    fun codecCapabilities_deprioritizeSoftwareAv1AndInferNumericPaths() {
        val noAv1Resolver = PlaybackResolver(
            transport = OfflineFixtureHttpTransport(emptyMap(), RequestPolicyEngine()),
            resolveMediaDns = false,
            codecCapabilitiesProvider = {
                com.harbor.tv.core.model.DeviceCodecCapabilities(
                    hwAvc4k = true,
                    hwHevc4k = true,
                    hwAv14k = false,
                    summary = "HW 4K: H.264, HEVC"
                )
            }
        )
        val hwAv1Resolver = PlaybackResolver(
            transport = OfflineFixtureHttpTransport(emptyMap(), RequestPolicyEngine()),
            resolveMediaDns = false,
            codecCapabilitiesProvider = {
                com.harbor.tv.core.model.DeviceCodecCapabilities(
                    hwAvc4k = true,
                    hwHevc4k = true,
                    hwAv14k = true,
                    summary = "HW 4K: H.264, HEVC, AV1"
                )
            }
        )
        val profile = UserProfile(id = "codec", name = "Codec", qualityPreference = QualityPreference.HIGHEST_AVAILABLE)
        val candidates = listOf(
            PlaybackCandidate("https://media.example.org/stream/2160.av1.mp4.m3u8", streamType = PlaybackStreamType.HLS, resolutionHeight = 2160),
            PlaybackCandidate("https://media.example.org/stream/1080.mp4", streamType = PlaybackStreamType.PROGRESSIVE, resolutionHeight = 1080)
        )

        val pickedWithoutAv1 = noAv1Resolver.selectCandidate(candidates, profile)
        assertEquals(1080, pickedWithoutAv1?.resolutionHeight)

        val pickedWithAv1 = hwAv1Resolver.selectCandidate(candidates, profile)
        assertEquals(2160, pickedWithAv1?.resolutionHeight)

        // Verify numeric path height inference (/2160.mp4 and -1440-)
        assertEquals(2160, noAv1Resolver.inferResolutionHeight(null, "https://cdn.example.org/pubs/abc/2160.mp4"))
        assertEquals(1440, noAv1Resolver.inferResolutionHeight(null, "https://cdn.example.org/v/clip-2560-1440-8000-h264.mp4"))
    }

    // -------------------------------------------------------------------------
    // Section 29: Magic-PH Reference Catalog Tests
    // -------------------------------------------------------------------------

    @Test
    fun magicPhReferenceCatalog_enforcesPinnedCommitAndReferenceOnlyActivationGuard() {
        assertEquals("5764d037d9b52009218815152ef4e976c2c62255", MagicPhSettingsAdapter.PINNED_COMMIT)
        val profiles = MagicPhSettingsAdapter.allProfiles()
        assertEquals(27, profiles.size)
        assertTrue(profiles.all { it.sourceStatus == "reference-only" })

        val pornhub = MagicPhSettingsAdapter.findById("pornhub")
        assertNotNull(pornhub)
        val validatedDef = loadValidatedDefinition("definitions/pornhub-webmaster.yaml")

        assertFalse(MagicPhSettingsAdapter.canActivateProfile(pornhub!!, validatedDefinition = null, userApproved = true))
        assertFalse(MagicPhSettingsAdapter.canActivateProfile(pornhub, validatedDefinition = validatedDef, userApproved = false))
        assertTrue(MagicPhSettingsAdapter.canActivateProfile(pornhub, validatedDefinition = validatedDef, userApproved = true))

        expectHarborException(HarborErrorCode.POLICY_BLOCKED) {
            MagicPhSettingsAdapter.assertActivationAllowed(pornhub, validatedDef, userApproved = false)
        }
    }

    // -------------------------------------------------------------------------
    // Live End-to-End Verification for 7 Live Sources (including 4K sources)
    // -------------------------------------------------------------------------

    @Test
    fun offline4kSources_extract4kCandidatesAndMaxQuality(): Unit = runBlocking {
        val extractor = UnifiedOperationExtractor()
        val policyEngine = RequestPolicyEngine()
        val resolver = PlaybackResolver(
            transport = OfflineFixtureHttpTransport(
                recordedResponsesByUrl = mapOf(
                    "https://www.eporner.com/embed/AbCdEfGhIjK/" to readAsset("fixtures/eporner-api-v2/details.html"),
                    "https://www.porntrex.com/video/100001/sanitized-demo-4k" to readAsset("fixtures/porntrex-html/details.html"),
                    "https://xhamster.com/videos/sanitized-demo-4k-100001" to readAsset("fixtures/xhamster-html/details.html")
                ),
                policyEngine = policyEngine,
                queuedStepPayloads = ArrayDeque(listOf(readAsset("fixtures/eporner-api-v2/xhr-video.json")))
            ),
            policyEngine = policyEngine,
            extractor = extractor,
            filterEngine = filterEngine,
            resolveMediaDns = false
        )
        val profile = UserProfile(id = "4k-test", name = "4K Test", qualityPreference = QualityPreference.HIGHEST_AVAILABLE)
        val prefs = PreferenceConfig()

        // Eporner 4K (2160p)
        val epornerDef = loadValidatedDefinition("definitions/eporner-api-v2.yaml")
        val epornerItem = CatalogItem(
            sourceId = epornerDef.source.id,
            sourceItemId = "AbCdEfGhIjK",
            sourcePageUrl = "https://www.eporner.com/embed/AbCdEfGhIjK/",
            title = "Eporner 4K",
            canonicalTags = listOf("quality.4k"),
            metadataCompleteness = MetadataCompleteness.COMPLETE
        )
        val epornerPlayback = resolver.resolvePlayback(epornerItem, epornerDef, profile, prefs, setOf(epornerDef.source.id))
        assertTrue(epornerPlayback.candidates.any { (it.resolutionHeight ?: 0) >= 2160 || it.qualityLabel == "2160p" })

        // PornTrex 4K (2160p MP4)
        val porntrexDef = loadValidatedDefinition("definitions/porntrex-html.yaml")
        val porntrexItem = CatalogItem(
            sourceId = porntrexDef.source.id,
            sourceItemId = "100001",
            sourcePageUrl = "https://www.porntrex.com/video/100001/sanitized-demo-4k",
            title = "PornTrex 4K",
            canonicalTags = listOf("quality.4k"),
            metadataCompleteness = MetadataCompleteness.COMPLETE
        )
        val porntrexPlayback = resolver.resolvePlayback(porntrexItem, porntrexDef, profile, prefs, setOf(porntrexDef.source.id))
        assertTrue(porntrexPlayback.candidates.any { it.url.contains("2160p") })

        // xHamster 4K HLS
        val xhamsterDef = loadValidatedDefinition("definitions/xhamster-html.yaml")
        val xhamsterItem = CatalogItem(
            sourceId = xhamsterDef.source.id,
            sourceItemId = "100001",
            sourcePageUrl = "https://xhamster.com/videos/sanitized-demo-4k-100001",
            title = "xHamster 4K",
            canonicalTags = listOf("quality.4k"),
            metadataCompleteness = MetadataCompleteness.COMPLETE
        )
        val xhamsterPlayback = resolver.resolvePlayback(xhamsterItem, xhamsterDef, profile, prefs, setOf(xhamsterDef.source.id))
        assertTrue(xhamsterPlayback.candidates.any { it.streamType == PlaybackStreamType.HLS })
    }

    @Test
    fun liveSources_categoriesListingThumbnailsAndPlaybackResolve(): Unit = runBlocking {
        val liveDefinitions = listOf(
            "definitions/eporner-api-v2.yaml",
            "definitions/redtube-api.yaml",
            "definitions/pornhub-webmaster.yaml",
            "definitions/xnxx-html.yaml",
            "definitions/xvideos-html.yaml",
            "definitions/porntrex-html.yaml",
            "definitions/xhamster-html.yaml"
        )
        val policyEngine = RequestPolicyEngine()
        val transport = ControlledHttpTransport(policyEngine)
        val extractor = UnifiedOperationExtractor(policyEngine = policyEngine)
        val normalizer = CatalogNormalizer(policyEngine = policyEngine)
        val resolver = PlaybackResolver(transport, policyEngine, extractor, filterEngine, resolveMediaDns = false)
        val profile = UserProfile(id = "live-test", name = "Live Test", qualityPreference = QualityPreference.HIGHEST_AVAILABLE)
        val preferences = PreferenceConfig()

        for (defPath in liveDefinitions) {
            val definition = loadValidatedDefinition(defPath)
            val sourceId = definition.source.id
            val catOp = requireNotNull(definition.operations.categories)
            val catUrl = policyEngine.buildInterpolatedUrl(
                requestDef = catOp.request,
                definition = definition,
                variables = emptyMap(),
                allowedVariables = setOf("source.baseUrl")
            )
            val catRes = transport.executeGet(catUrl, definition, HostPurpose.CATALOG, catOp.request.headers)
            val catExtracted = extractor.extractCategories(definition, catRes.body, catRes.finalUrl)
            assertTrue(
                "[$sourceId] expected >= 5 categories, got ${catExtracted.categories.size}",
                catExtracted.categories.size >= 5
            )

            // Pick a clean non-empty category (preferring a popular one or first few until >= 5 videos)
            val listingOp = requireNotNull(definition.operations.listing)
            var matchedItems = emptyList<CatalogItem>()
            for (cat in catExtracted.categories.take(5)) {
                val listUrl = policyEngine.buildInterpolatedUrl(
                    requestDef = listingOp.request,
                    definition = definition,
                    variables = mapOf("input.url" to cat.url),
                    allowedVariables = setOf("source.baseUrl", "input.url")
                )
                val listRes = transport.executeGet(listUrl, definition, HostPurpose.CATALOG, listingOp.request.headers)
                val listExtracted = extractor.extractListing(definition, "listing", listRes.body, listRes.finalUrl)
                val normalized = normalizer.normalizeCatalogItems(listExtracted.listing.items, definition)
                if (normalized.size >= 5) {
                    matchedItems = normalized
                    break
                }
            }
            assertTrue(
                "[$sourceId] expected >= 5 normalized listing items with valid thumbnails",
                matchedItems.size >= 5
            )
            val itemsWithThumbnails = matchedItems.filter { it.thumbnailReferences.isNotEmpty() }
            assertTrue(
                "[$sourceId] expected items to have valid thumbnails matching allowedHosts.images, got 0/${matchedItems.size}",
                itemsWithThumbnails.isNotEmpty()
            )

            // Resolve playback on the first few items until we find one with a valid HLS/MP4 stream
            var resolvedPlayback: com.harbor.tv.core.model.PlaybackResolutionResult? = null
            var lastError: Throwable? = null
            for (video in itemsWithThumbnails.take(3)) {
                try {
                    val res = resolver.resolvePlayback(
                        item = video,
                        definition = definition,
                        profile = profile,
                        preferences = preferences,
                        enabledSourceIds = setOf(sourceId)
                    )
                    if (res.candidates.any {
                            it.streamType == PlaybackStreamType.HLS || it.streamType == PlaybackStreamType.PROGRESSIVE
                        }
                    ) {
                        resolvedPlayback = res
                        break
                    }
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            assertNotNull(
                "[$sourceId] expected at least 1 playable HLS or MP4 candidate; lastError=${lastError?.message}",
                resolvedPlayback
            )
            println(
                "LIVE OK [$sourceId]: categories=${catExtracted.categories.size}, " +
                    "items=${matchedItems.size}, thumbs=${itemsWithThumbnails.size}, " +
                    "selectedStream=${resolvedPlayback!!.selectedCandidate?.streamType} -> " +
                    resolvedPlayback.selectedCandidate?.url?.take(100)
            )
        }
    }

    private inline fun expectHarborException(
        expectedCode: HarborErrorCode,
        block: () -> Unit
    ): HarborException {
        try {
            block()
            fail("Expected HarborException($expectedCode), but call succeeded")
            throw AssertionError("Unreachable")
        } catch (e: HarborException) {
            assertEquals("Expected error code $expectedCode, got ${e.code}: ${e.message}", expectedCode, e.code)
            return e
        }
    }
}
