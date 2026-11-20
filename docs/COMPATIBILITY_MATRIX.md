# Compatibility Matrix

This matrix documents Harbor's declarative compatibility levels, all 11 bundled YAML definitions (7 active live 4K/HD sources + 4 demo/reference definitions), and the 27-platform `reference-only` catalog derived from `Magic-PH` (`5764d037d9b52009218815152ef4e976c2c62255`) and `platform-dataset.json`.

---

## 1. Compatibility Levels

| Level | Mechanism | Status | Bundled Examples |
| --- | --- | --- | --- |
| **Level 1** | Static HTML (`jsoup` CSS selectors + `<video><source>` / `<script>`) | Supported | `example-library.yaml`, `xnxx-html.yaml`, `xvideos-html.yaml`, `porntrex-html.yaml`, `xhamster-html.yaml` |
| **Level 2** | Documented/authorized JSON endpoints + RFC 6901 JSON Pointer | Supported | `eporner-api-v2.yaml`, `redtube-api.yaml`, `pornhub-webmaster.yaml`, `chaturbate-affiliates.yaml` |
| **Level 3** | Embedded JSON inside `<script>` tag (`document.embeddedJson`) | Supported | `embedded-json-catalog.yaml` |
| **Level 4** | Bounded 2–3 step intermediate page/embed traversal | Supported | `intermediate-embed-demo.yaml`, `eporner-api-v2.yaml`, `redtube-api.yaml` |
| **Unsupported** | Arbitrary JS players, custom crypto URL signing, CAPTCHA/Cloudflare challenges, DRM bypass, interactive login scraping | Rejected with clear diagnostics | OnlyFans, Fansly, Pornhub Premium, SpankBang |

---

## 2. Bundled YAML Source Definitions (11 Total)

All 11 bundled definitions include sanitized offline HTML/JSON test fixtures in `app/src/main/assets/fixtures/` and pass `DefinitionImporter` + `FixtureRunner`.

### 2.1 Active Live Playable Sources (7 Live 4K/HD Sources)

| Source ID | Level | Categories | Search | Details | Playback | Max Verified Quality | Storyboard |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `eporner-api-v2` | Level 2/4 | Yes | Yes | Yes | Yes (2-step embed -> XHR MP4) | **4K (2160p) MP4** | 6-frame |
| `porntrex-html` | Level 1/3 | Yes | Yes | Yes | Yes (`<script>` MP4 extraction) | **4K (2160p) MP4** | 6-frame |
| `xhamster-html` | Level 1/3 | Yes | Yes | Yes | Yes (`<script>` Adaptive HLS) | **4K (2160p) HLS** | — |
| `pornhub-webmaster` | Level 2/3 | Yes | Yes | Yes | Yes (Embed `<script>` HLS) | **HD/1080p HLS** | 6-frame |
| `xvideos-html` | Level 1/3 | Yes | Yes | Yes | Yes (`<script>` HLS & MP4) | **4K/HD HLS & MP4** | — |
| `xnxx-html` | Level 1/3 | Yes | Yes | Yes | Yes (`<script>` HLS & MP4) | **HD HLS & MP4** | — |
| `redtube-api` | Level 2/4 | Yes | Yes | Yes | Yes (2-step embed -> HLS JSON) | **HD HLS** | 6-frame |

### 2.2 Demo & Reference Definitions (4 Bundled Definitions)

| Source ID | Level | Categories | Search | Details | Playback | Live | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `chaturbate-affiliates` | Level 2 | No | Yes | No | No | Yes | Public Affiliates Online Rooms JSON discovery |
| `example-library` | Level 1 | Yes | Yes | Yes | Yes (HLS/MP4) | No | Fictional static HTML reference fixture source |
| `embedded-json-catalog` | Level 3 | Yes | Yes | Yes | Yes (HLS/MP4) | No | `<script id="catalog-data">` JSON Pointer reference |
| `intermediate-embed-demo` | Level 4 | Yes | No | Yes | Yes (2-step) | No | Detail page -> declared `embed.example.org` reference |

---

## 3. Reference-Only Platform Profiles (27 Platforms)

All 27 entries in [magic-ph-settings.yaml](../app/src/main/assets/reference/magic-ph-settings.yaml) carry `sourceStatus: "reference-only"` and **cannot auto-activate** without an explicit validated `SourceDefinition` and user approval.

| Platform | Origin | Official Public API | Declarative Status | Primary Constraint / Policy Status |
| --- | --- | --- | --- | --- |
| Pornhub | Magic-PH + Dataset | Webmasters JSON + CSV/RSS | **Active Live (`pornhub-webmaster`)** | Official Webmasters JSON + embed HLS |
| Pornhub Premium | Magic-PH | None documented | Unsupported (`reference-only`) | Subscription-gated; auth/DRM bypass unsupported |
| RedTube | Magic-PH + Dataset | HTTP GET Query API (JSON/XML) | **Active Live (`redtube-api`)** | Official Query API + 2-step embed HLS JSON |
| Tube8 | Magic-PH | None documented | Candidate (`reference-only`) | Requires explicit declarative definition & authorization review |
| Thumbzilla | Magic-PH | None documented | Candidate (`reference-only`) | Requires explicit declarative definition & authorization review |
| xHamster | Magic-PH + Dataset | Gated Partner CSV/RSS | **Active Live (`xhamster-html`)** | Public HTML + 4K adaptive HLS on `*.xhmediacdn.com` |
| XNXX | Magic-PH | None documented | **Active Live (`xnxx-html`)** | Public HTML catalog + `setVideoHLS`/`setVideoUrlHigh` |
| XVideos | Magic-PH + Dataset | Daily bulk CSV exports only | **Active Live (`xvideos-html`)** | Public HTML catalog + `setVideoHLS`/`setVideoUrlHigh` |
| YouPorn | Magic-PH + Dataset | Hubtraffic-family Webmaster API | Candidate (`reference-only`) | Requires explicit declarative definition & authorization review |
| 91Porn | Magic-PH | None documented | `reference-only` | Unverified authorization; reference-only host mapping |
| 91Porna | Magic-PH | None documented | `reference-only` | Unverified authorization; reference-only host mapping |
| HQPorner | Magic-PH | None documented | Candidate (`reference-only`) | Requires explicit declarative definition & authorization review |
| SpankBang | Magic-PH | None documented | Unsupported (`reference-only`) | Returns HTTP 403 Cloudflare challenge (no bypass) |
| PornTrex | Magic-PH | None documented | **Active Live (`porntrex-html`)** | Public HTML catalog + 4K (2160p) MP4 + 6-frame storyboard |
| AnalDin | Magic-PH | None documented | `reference-only` | Requires explicit declarative definition & authorization review |
| sxyprn | Magic-PH | None documented | `reference-only` | Requires explicit declarative definition & authorization review |
| Porn00 | Magic-PH | None documented | `reference-only` | Requires explicit declarative definition & authorization review |
| Eporner | Magic-PH + Dataset | Official REST API v2 + CSV/RSS | **Active Live (`eporner-api-v2`)** | Official REST API v2 + 2-step 4K (2160p) MP4 + 6-frame storyboard |
| YouJizz | Magic-PH | None documented | Candidate (`reference-only`) | Requires explicit declarative definition & authorization review |
| Beeg | Magic-PH | None documented | `reference-only` | Marked WIP/unsupported in Magic-PH |
| Jable | Magic-PH | None documented | `reference-only` | Requires explicit declarative definition & authorization review |
| MissAV | Magic-PH | None documented | `reference-only` | Requires explicit declarative definition & authorization review |
| Chaturbate | Dataset | Affiliates Online Rooms + Events API | **Bundled (`chaturbate-affiliates`)** | Live room JSON discovery (`playback: false`) |
| Stripchat | Dataset | Stripcash Models/Dicts/Stats API | `reference-only` | Requires dynamic `CDNConfig` substitution & geo enforcement |
| LiveJasmin | Dataset | AWEmpire Video Promotion API | `reference-only` | Authenticated affiliate API; mandatory official player |
| OnlyFans | Magic-PH + Dataset | None (closed platform) | Unsupported (`reference-only`) | ToS 8.7 & DRM strictly prohibit scraping or circumvention |
| Fansly | Magic-PH + Dataset | None (closed platform) | Unsupported (`reference-only`) | Private session web API only; bots/extraction prohibited |
