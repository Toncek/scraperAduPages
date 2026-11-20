# Compatibility Matrix

This matrix documents Harbor's declarative compatibility levels, bundled YAML definitions, and `reference-only` platform profiles derived from `Magic-PH` (`5764d037d9b52009218815152ef4e976c2c62255`) and `platform-dataset.json`.

---

## 1. Compatibility Levels

| Level | Mechanism | MVP Status | Bundled Example |
| --- | --- | --- | --- |
| **Level 1** | Static HTML (`jsoup` CSS selectors + `<video><source>`) | Supported (MVP) | `example-library.yaml` |
| **Level 2** | Documented/authorized JSON endpoints + JSON Pointer | Supported (MVP) | `eporner-api-v2.yaml`, `redtube-api.yaml`, `pornhub-webmaster.yaml`, `chaturbate-affiliates.yaml` |
| **Level 3** | Embedded JSON inside `<script>` tag (`document.embeddedJson`) | Supported (MVP) | `embedded-json-catalog.yaml` |
| **Level 4** | Bounded 2–3 step intermediate page/embed traversal | Optional post-basic resolver | `intermediate-embed-demo.yaml` |
| **Unsupported** | Arbitrary JS players, custom crypto URL signing, CAPTCHA, DRM bypass, interactive login scraping | Rejected with clear diagnostics | OnlyFans, Fansly, JS-obfuscated sites |

---

## 2. Bundled YAML Source Definitions

All bundled definitions default to `enabledByDefault: false` and include sanitized offline fixtures in `app/src/main/assets/fixtures/`.

| Source ID | Level | Categories | Search | Details | Playback | Live | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `example-library` | Level 1 | Yes | Yes | Yes | Yes (HLS/MP4) | No | Fictional HTML reference source |
| `eporner-api-v2` | Level 2 | Yes | Yes | Yes | No | No | Official REST API v2 metadata; external playback uses hosted player |
| `redtube-api` | Level 2 | Yes | Yes | Yes | No | No | Official GET Query API metadata; raw stream signing undisclosed |
| `pornhub-webmaster` | Level 2 | Yes | Yes | Yes | No | No | Official Webmasters JSON metadata; raw stream signing undisclosed |
| `chaturbate-affiliates` | Level 2 | No | Yes | No | No | Yes | Public Affiliates Online Rooms JSON discovery; stream tokens internal |
| `embedded-json-catalog` | Level 3 | Yes | Yes | Yes | Yes (HLS/MP4) | No | `<script id="catalog-data">` JSON Pointer extraction |
| `intermediate-embed-demo` | Level 4 | Yes | No | Yes | Yes (2-step) | No | Detail page -> declared `embed.example.org` -> `<video><source>` |

---

## 3. Reference-Only Platform Profiles (27 Platforms)

All entries in [magic-ph-settings.yaml](../app/src/main/assets/reference/magic-ph-settings.yaml) carry `sourceStatus: "reference-only"` and **cannot auto-activate**.

| Platform | Origin | Official Public API | Raw Stream Support | Primary Constraint / Policy Status |
| --- | --- | --- | --- | --- |
| Pornhub | Magic-PH + Dataset | Webmasters JSON + CSV/RSS | Undisclosed | Metadata via Webmasters API; scraping/downloading prohibited by ToS |
| Pornhub Premium | Magic-PH | None documented | Unsupported | Subscription-gated; auth/DRM bypass unsupported |
| RedTube | Magic-PH + Dataset | HTTP GET Query API (JSON/XML) | Undisclosed | Metadata + `embed_url` supported; raw stream signing undisclosed |
| Tube8 | Magic-PH | None documented | Undisclosed | Requires explicit declarative definition & authorization review |
| Thumbzilla | Magic-PH | None documented | Undisclosed | Requires explicit declarative definition & authorization review |
| xHamster | Magic-PH + Dataset | Gated Partner CSV/RSS | Undisclosed | Partner approval required; ToS 2.3.5/2.3.15 forbid scrapers/custom players |
| XNXX | Magic-PH | None documented | Undisclosed | Automated scraping restricted by ToS |
| XVideos | Magic-PH + Dataset | Daily bulk CSV exports only | Undisclosed | No live REST API; ToS Section 2 restricts automated scrapers |
| YouPorn | Magic-PH + Dataset | Hubtraffic-family Webmaster API | Undisclosed | Metadata only; raw stream signing undisclosed |
| 91Porn | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| 91Porna | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| HQPorner | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| SpankBang | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| PornTrex | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| AnalDin | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| sxyprn | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| Porn00 | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| Eporner | Magic-PH + Dataset | Official REST API v2 + CSV/RSS | Undisclosed | Sequential API allowed; hosted player required; AI/ML use banned |
| YouJizz | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| Beeg | Magic-PH | None documented | Undisclosed | Marked WIP/unsupported in Magic-PH; independent review required |
| Jable | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| MissAV | Magic-PH | None documented | Undisclosed | Reference-only host mapping; unverified authorization |
| Chaturbate | Dataset | Affiliates Online Rooms + Events API | Undocumented | Live room discovery supported; internal viewer stream tokens private |
| Stripchat | Dataset | Stripcash Models/Dicts/Stats API | Regional CDNConfig HLS | Requires dynamic `CDNConfig` substitution & privacy/geo enforcement |
| LiveJasmin | Dataset | AWEmpire Video Promotion API | Official player only | Authenticated affiliate API; mandatory official player |
| OnlyFans | Magic-PH + Dataset | None (closed platform) | Unsupported (DRM) | ToS 8.7 & DRM strictly prohibit scraping or circumvention |
| Fansly | Dataset | None (closed platform) | Unsupported | Private session web API only; ToS 01.6/04.1/04.2 forbid bots/extraction |
