# Harbor Android TV — System Audit & Engineering Backlog

**Audit Date:** 2026-11-20  
**Baseline Commit:** `7284213`  
**Target Hardware Profile:** 4K UHD (3840×2160, 60 Hz, HDR) Android TV (Android 14 / API 34, `armeabi-v7a`, ~2 GB RAM, 14 GB free `/data` flash storage)

---

## 1. What Is Working Well (Verified End-to-End)

| Area | Status | Evidence |
| --- | --- | --- |
| **Declarative Engine & Security Policy** | Working | Safe YAML/JSON parsing, JSON Schema Draft 2020-12 validation, semantic validator, zero JS execution, exact/explicit host allowlists, connection-time DNS address safety, and redacted diagnostics. |
| **Core Architecture (16 Components / 13 TV Screens)** | Working | All 16 spec components and all 13 D-pad navigable Compose TV screens implemented, passing 12/12 unit + live acceptance tests (`./gradlew :app:testDebugUnitTest`). |
| **Live Sources (5 Active)** | Working | `eporner-api-v2`, `pornhub-webmaster`, `redtube-api`, `xnxx-html`, and `xvideos-html` all load live categories, listings, thumbnails, and playable HLS/MP4 streams on the physical TV. |
| **Main-Thread Safety** | Fixed & Working | `ControlledHttpTransport`, `CatalogRepository`, and `PlaybackResolver` execute network, DNS, and extraction strictly on `Dispatchers.IO` (eliminated `NetworkOnMainThreadException`). |
| **2 GB RAM Optimization & Persistent ROM Cache** | Working | `TOTAL PSS` measured at **~106–126 MB** during live browsing and 720p HD hardware playback (`Java Heap ~25 MB`). Media3 `SimpleCache` persists to `no_backup/harbor_media_cache` (1.0–2.0 GB configurable via Settings) with Coil RGB565 downsampling + 128 MB disk cache and 32 MB OkHttp disk cache. |
| **Background Video Preloader** | Working | `HarborMediaPreloader` preloads Progressive MP4 & HLS segments into persistent ROM with a 64 KiB scratch buffer on Video Details open, and `playVideo()` reuses freshly resolved signed URLs for immediate cache-hit startup. |
| **Private Session Mode** | Working | Bypasses disk media cache, cancels active preloads, skips history/resume persistence, and clears ephemeral session cookies. |

---

## 2. Audit Findings — What Needs to Be Done Better

### P0 — Critical for 4K Playback, Quality Visibility & Expanded Sources (Completed & Verified)

1. **[DONE] True 4K UHD (2160p) Playback Pipeline**
   - Unlocked `DefaultTrackSelector.setMaxVideoSize(3840, 2160)` in `HarborPlayerController` and `MAX_HLS_HEIGHT = 2160` in `HarborMediaPreloader`.
   - Updated `UserProfile.preferredResolutionHeight` default to `2160` (4K) in `HarborModels.kt` and `HarborDatabase.kt`.
   - Upgraded `eporner-api-v2.yaml` (`v1.2.0`) to `playerHeight=2160&supportedFormats=dash,mp4` and `thumbsize=big` (`640×360`).
   - Added automatic numeric `resolutionHeight` inference from text `qualityLabel` and stream URLs in `UnifiedOperationExtractor` and `PlaybackResolver`.

2. **[DONE] Maximum Video Quality Badge on Every Thumbnail**
   - Added `maxQualityLabel` and `maxResolutionHeight` to `CatalogItem` and `CatalogItemJsonCodec`.
   - Added listing quality extraction to `xvideos-html.yaml` (`span.video-hd-mark`), `xnxx-html.yaml` (`span.video-hd`), `porntrex-html.yaml` (`.quality`), and `xhamster-html.yaml` (`use[id^=format-]`), plus tag/title derivation in `CatalogNormalizer` and candidate-resolution upgrades in `HarborViewModel`.
   - Rendered color-coded quality badges (**Gold `4K`**, **Violet `1440p`**, **Cyan `1080p`**, **Teal `720p`**, **Slate `HD`**) on every video card thumbnail and Video Details header.

3. **[DONE] Expanded Declarative 4K Sources Using `Magic-PH` Research (Strictly Declarative, Zero JS)**
   - Live compatibility audit of `Magic-PH` (`5764d037d9b52009218815152ef4e976c2c62255`) candidate platforms:
     - **PornTrex (`porntrex-html`) — LIVE & VERIFIED (4K 2160p MP4)**: HTTP 200, no Cloudflare, 100 items/page, explicit `.quality` badge (`4K`/`1080p`/`720p`), direct HTTP 200 `2160p 4K` / `1080p` / `720p` / `480p` MP4 streams via `get_file/...` -> `*.cdntrex.com`.
     - **xHamster (`xhamster-html`) — LIVE & VERIFIED (4K 2160p Adaptive HLS)**: HTTP 200, no Cloudflare, categories + `/search/` + `1280x720` thumbnails + `format-4k-text` badge + HTTP 200 multi-bitrate `3840x2160:2160p` adaptive HLS on `*.xhmediacdn.com`.
     - **YouPorn, Tube8, HQPorner, YouJizz, Thumbzilla — P2 CANDIDATES**: All return HTTP 200 without Cloudflare; queued for selector/embed verification in P2.
     - **SpankBang (`spankbang.com`) — UNSUPPORTED**: Returns HTTP 403 Cloudflare challenge; intentionally excluded per Harbor's no-anti-bot-bypass policy.
     - **OnlyFans / Fansly / Pornhub Premium — UNSUPPORTED**: Closed subscription / DRM / authenticated platforms; kept `reference-only`.
   - **Schema-First DSL Enhancement**: Added bounded `regexExtractAll` transform (capped at 20 matches) and multi-candidate expansion in `HtmlExtractionEngine` (zero JS execution).

### P1 — Faster, Better Looking, More Powerful TV Experience (Completed & Verified)

4. **[DONE] Perceived Speed & Concurrency**
   - **Cold-start Room cache hydration**: Added `CatalogRepository.getCachedHomeRows()` so Home renders cached videos immediately while refreshing live sources incrementally in the background.
   - **Parallel multi-source Search**: Parallelized `CatalogRepository.search()` via `coroutineScope` + `async` with 15s per-source timeout.
   - **Sharper, Faster Thumbnails**: Switched `eporner-api-v2` to `thumbsize=big` (`640×360`) and configured Coil with `ConnectionPool(6, 30s)` while keeping 8% heap RAM cap + RGB565 + 128 MB disk cache.
   - **4K-Ready ROM Preloading**: Raised Details auto-preload to `32 MB` and manual deep preload to `128 MB`, with `18 MiB` ExoPlayer RAM buffer backed by the 1.5–2.0 GB persistent ROM cache.

5. **[DONE] Modern 4K TV Cinema UI & Power Features**
   - **Featured Cinema Spotlight on Home**: Displays the currently focused or highest-resolution 4K video at top of Home with 16:9 artwork, quality badge, duration, and tags.
   - **4K UHD Quick-Filter Mode**: Added `"4K Only"` toggle on Home, Categories, and Search + 4K quick-search chips (`4k`, `2160p`, `1080p`, `60fps`).
   - **Upgraded Video Cards**: Bottom gradient scrim, bold color-coded Max Quality badge, clean human-readable tags, and refined focus glow/scale.
   - **Live Player Telemetry HUD**: Displays real hardware-decoded resolution & FPS (e.g., `4K UHD • 3840×2160 @ 60fps`) in `PlayerScreen`.

---

## 3. Completed P2 / P3 Upgrades & Remaining Backlog

- **[DONE] [P2] Hardware Codec Capability Matching (`MediaCodecList`)**: Added `HardwareCodecCapabilities.detect()` inspecting Android hardware 4K decoders (`video/avc`, `video/hevc`, `video/av01`), wired into `PlaybackResolver` to deprioritize software-only AV1 when a hardware H.264/HEVC `>= 720p` stream is available, and surfaced in `SettingsScreen`.
- **[DONE] [P2] Up-Next Background ROM Queue**: Added playback queue tracking (`Prev` / `Next` D-pad controls + Up-Next HUD in `PlayerScreen`) and automatic 24 MB background ROM preloading of the next video once active playback passes 25% (or 30s) while healthy and not in Private Session.
- **[DONE] [P2] Category & Search Sorting**: Added `CatalogSortMode` (`DEFAULT`, `QUALITY_FIRST` = 4K First, `LONGEST_FIRST` = Longest) chips across `CategoriesScreen` and `SearchScreen`.
- **[DONE] [P3] Release Build Optimization (R8 / ProGuard)**: Enabled `isMinifyEnabled = true` + `isShrinkResources = true` in `app/build.gradle.kts` with full Room, DataStore, Coil, Media3, OkHttp, Jsoup, and kotlinx.serialization keep rules in `app/proguard-rules.pro`.
- **[P2] Additional `Magic-PH` Declarative Sources**: Author & fixture-test additional HTTP-200 verified platforms (`youporn-html.yaml`, `tube8-html.yaml`, `thumbzilla-html.yaml`, etc.) where clean declarative streams exist.
- **[P3] Thumbnail Hover/Focus Preview Strip**: Optional low-memory storyboard cycling on focused cards when multiple `thumbnailReferences` exist.
- **[P3] Voice Search Integration**: Wire Android TV remote microphone (`RecognizerIntent`) into `SearchScreen`.
