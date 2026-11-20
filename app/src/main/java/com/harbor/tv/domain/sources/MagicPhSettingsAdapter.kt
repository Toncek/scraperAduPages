package com.harbor.tv.domain.sources

import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.model.MagicPhWebsiteProfile
import com.harbor.tv.core.model.QualityPreference
import com.harbor.tv.engine.definition.SourceDefinition

/**
 * Declarative settings adapter derived from `magicoflolis/Magic-PH` (pinned commit
 * `5764d037d9b52009218815152ef4e976c2c62255`) and `dataset.json` (Master Spec Section 29).
 *
 * Every profile is strictly `sourceStatus = "reference-only"` and data-only: no JavaScript,
 * userscript APIs, or download logic are imported or executed, and no profile can be activated
 * without an explicit, schema/semantically validated [SourceDefinition] and user approval.
 */
object MagicPhSettingsAdapter {

    const val PROVIDER = "magicoflolis/Magic-PH"
    const val REPOSITORY_URL = "https://github.com/magicoflolis/Magic-PH"
    const val PINNED_COMMIT = "5764d037d9b52009218815152ef4e976c2c62255"
    const val LICENSE = "MIT"
    const val REFERENCE_ONLY_STATUS = "reference-only"

    private val profiles: List<MagicPhWebsiteProfile> = listOf(
        MagicPhWebsiteProfile(
            id = "pornhub",
            name = "Pornhub",
            operator = "Aylo (formerly MindGeek)",
            catalogHosts = listOf("www.pornhub.com", "pornhub.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = "Webmasters / legacy Hubtraffic JSON endpoints (GET /webmasters/search, /webmasters/categories)",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Public webmasters JSON API covers catalog/categories/embed lookup; raw stream URL signing is undisclosed.",
            bundledDefinitionAsset = "definitions/pornhub-webmaster.yaml"
        ),
        MagicPhWebsiteProfile(
            id = "pornhub-premium",
            name = "Pornhub Premium",
            operator = "Aylo (formerly MindGeek)",
            catalogHosts = listOf("www.pornhubpremium.com", "pornhubpremium.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Subscription-gated content; interactive login and DRM/access bypass are unsupported."
        ),
        MagicPhWebsiteProfile(
            id = "redtube",
            name = "RedTube",
            operator = "Aylo (formerly MindGeek)",
            catalogHosts = listOf("api.redtube.com", "www.redtube.com", "redtube.com", "embed.redtube.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = "Official HTTP GET Query API (https://api.redtube.com/)",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Official Query API provides metadata and embed URLs; raw stream URL signing is undisclosed.",
            bundledDefinitionAsset = "definitions/redtube-api.yaml"
        ),
        MagicPhWebsiteProfile(
            id = "tube8",
            name = "Tube8",
            operator = "Aylo (formerly MindGeek)",
            catalogHosts = listOf("www.tube8.com", "tube8.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorized endpoint review before activation."
        ),
        MagicPhWebsiteProfile(
            id = "thumbzilla",
            name = "Thumbzilla",
            operator = "Aylo (formerly MindGeek)",
            catalogHosts = listOf("www.thumbzilla.com", "thumbzilla.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorized endpoint review before activation."
        ),
        MagicPhWebsiteProfile(
            id = "xhamster",
            name = "xHamster",
            operator = "xHamster / Partner Affiliate Network",
            catalogHosts = listOf("xhamster.com", "www.xhamster.com", "partners.xhamster.com", "xh.video"),
            imageHosts = listOf("thumb-v7.xhcdn.com"),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = "Gated Partner Portal (CSV dumps, embeds, thumbnails, RSS feeds)",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Partner portal requires registration and domain approval; ToS prohibits unauthorized scrapers and custom players."
        ),
        MagicPhWebsiteProfile(
            id = "xnxx",
            name = "XNXX",
            operator = "WGCZ / WebGroup",
            catalogHosts = listOf("www.xnxx.com", "xnxx.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "No documented public JSON API; automated scraping restricted by Terms of Service."
        ),
        MagicPhWebsiteProfile(
            id = "xvideos",
            name = "XVideos",
            operator = "WebGroup Czech Republic, a.s.",
            catalogHosts = listOf(
                "www.xvideos.com",
                "xvideos.com",
                "info.xvideos.net",
                "public-assets.xvideos-cdn.com"
            ),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = "Daily bulk CSV database exports (https://info.xvideos.net/db)",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "No public live REST API; distributes daily CSV bulk dumps only; automated web scraping prohibited by ToS."
        ),
        MagicPhWebsiteProfile(
            id = "youporn",
            name = "YouPorn",
            operator = "Aylo (formerly MindGeek)",
            catalogHosts = listOf("www.youporn.com", "youporn.com", "youporngay.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = "Hubtraffic-family Webmaster API",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Hubtraffic-family webmaster API metadata only; raw stream signing is undisclosed."
        ),
        MagicPhWebsiteProfile(
            id = "91porn",
            name = "91Porn",
            operator = null,
            catalogHosts = listOf("91porn.com", "www.91porn.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Unverified public API and ToS status; reference-only host mapping."
        ),
        MagicPhWebsiteProfile(
            id = "91porna",
            name = "91Porna",
            operator = null,
            catalogHosts = listOf("91porna.com", "www.91porna.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Unverified public API and ToS status; reference-only host mapping."
        ),
        MagicPhWebsiteProfile(
            id = "hqporner",
            name = "HQPorner",
            operator = null,
            catalogHosts = listOf("hqporner.com", "www.hqporner.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "spankbang",
            name = "SpankBang",
            operator = null,
            catalogHosts = listOf("spankbang.com", "www.spankbang.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "porntrex",
            name = "PornTrex",
            operator = null,
            catalogHosts = listOf("www.porntrex.com", "porntrex.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "analdin",
            name = "AnalDin",
            operator = null,
            catalogHosts = listOf("www.analdin.com", "analdin.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "sxyprn",
            name = "sxyprn",
            operator = null,
            catalogHosts = listOf("sxyprn.com", "www.sxyprn.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "porn00",
            name = "Porn00",
            operator = null,
            catalogHosts = listOf("www.porn00.org", "porn00.org"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "eporner",
            name = "Eporner",
            operator = "Eporner",
            catalogHosts = listOf("www.eporner.com", "eporner.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = "Official RESTful API v2 (https://www.eporner.com/api/v2/)",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Official REST API v2 supports categories, search, video/id, and removals; sequential requests required; external playback uses hosted iframe.",
            bundledDefinitionAsset = "definitions/eporner-api-v2.yaml"
        ),
        MagicPhWebsiteProfile(
            id = "youjizz",
            name = "YouJizz",
            operator = null,
            catalogHosts = listOf("www.youjizz.com", "youjizz.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "beeg",
            name = "Beeg",
            operator = null,
            catalogHosts = listOf("beeg.com", "www.beeg.com", "store.externulls.com"),
            imageHosts = listOf("thumbs.externulls.com"),
            mediaHosts = listOf("video.beeg.com"),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Marked work-in-progress/unsupported in Magic-PH README; requires independent API authorization."
        ),
        MagicPhWebsiteProfile(
            id = "jable",
            name = "Jable",
            operator = null,
            catalogHosts = listOf("jable.tv", "fs1.app"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "missav",
            name = "MissAV",
            operator = null,
            catalogHosts = listOf("missav.ai", "missav.com", "missav.ws"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = null,
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires explicit declarative definition and authorization review."
        ),
        MagicPhWebsiteProfile(
            id = "chaturbate",
            name = "Chaturbate",
            operator = "Chaturbate (highwebmedia.com)",
            catalogHosts = listOf("chaturbate.com", "www.chaturbate.com", "eventsapi.chaturbate.com"),
            imageHosts = emptyList(),
            mediaHosts = listOf("live.stream.highwebmedia.com"),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = true,
            directVideoSupported = false,
            officialApiType = "Public Affiliates Online Rooms API + Official Events API v1.0.0",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Public Affiliates Online Rooms API provides live room discovery; internal viewer stream tokens are undocumented.",
            bundledDefinitionAsset = "definitions/chaturbate-affiliates.yaml"
        ),
        MagicPhWebsiteProfile(
            id = "stripchat",
            name = "Stripchat",
            operator = "Stripchat / Stripcash Affiliate Network",
            catalogHosts = listOf("stripchat.com", "go.rmhfrtnd.com", "api.stripcash.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = "Client-Side Online Models API + Server-Side Aggregators Models API + Statistics API",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Dynamic CDNConfig host resolution and geo-privacy enforcement required before any active integration."
        ),
        MagicPhWebsiteProfile(
            id = "livejasmin",
            name = "LiveJasmin",
            operator = "LiveJasmin / AWEmpire Affiliate Program",
            catalogHosts = listOf("www.livejasmin.com", "livejasmin.com", "auth.awempire.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = "Authenticated Video Promotion API + Live Promotional Feeds & Link Validation API",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Requires authenticated AWEmpire account and mandatory official video player."
        ),
        MagicPhWebsiteProfile(
            id = "onlyfans",
            name = "OnlyFans",
            operator = "Fenix International Limited",
            catalogHosts = listOf("onlyfans.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = "None (No official public REST, GraphQL, or WebSocket API)",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Closed subscription platform with no official public API; DRM protection and ToS Section 8.7 strictly prohibit automated scraping or access-control circumvention."
        ),
        MagicPhWebsiteProfile(
            id = "fansly",
            name = "Fansly",
            operator = "Select Media LLC / CY Media LTD",
            catalogHosts = listOf("fansly.com", "www.fansly.com"),
            imageHosts = emptyList(),
            mediaHosts = emptyList(),
            desktopSupported = true,
            mobileSupported = true,
            searchSupported = false,
            directVideoSupported = false,
            officialApiType = "None (No official public developer API)",
            sourceStatus = REFERENCE_ONLY_STATUS,
            compatibilityNotes = "Closed subscription platform with no official public developer API; ToS Sections 01.6/04.1/04.2 strictly prohibit bots and automated extraction."
        )
    )

    private val profilesById: Map<String, MagicPhWebsiteProfile> = profiles.associateBy { it.id }

    fun allProfiles(): List<MagicPhWebsiteProfile> = profiles

    fun findById(id: String): MagicPhWebsiteProfile? = profilesById[id.trim().lowercase()]

    fun supportedQualityModes(): List<QualityPreference> = listOf(
        QualityPreference.HIGHEST_AVAILABLE,
        QualityPreference.PREFERRED_RESOLUTION,
        QualityPreference.PREFERRED_BITRATE,
        QualityPreference.MANUAL,
        QualityPreference.AUTO_FALLBACK
    )

    /**
     * Enforces Section 29.4: `reference-only` profiles MUST NOT be activated without an explicit
     * schema/semantically validated [SourceDefinition] and explicit user approval.
     */
    fun canActivateProfile(
        profile: MagicPhWebsiteProfile,
        validatedDefinition: SourceDefinition?,
        userApproved: Boolean
    ): Boolean {
        if (profile.sourceStatus == REFERENCE_ONLY_STATUS && (validatedDefinition == null || !userApproved)) {
            return false
        }
        return validatedDefinition != null && userApproved
    }

    fun assertActivationAllowed(
        profile: MagicPhWebsiteProfile,
        validatedDefinition: SourceDefinition?,
        userApproved: Boolean
    ) {
        if (!canActivateProfile(profile, validatedDefinition, userApproved)) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Reference-only profile '${profile.id}' cannot be activated without an explicit validated SourceDefinition and user approval",
                sourceId = profile.id
            )
        }
    }
}
