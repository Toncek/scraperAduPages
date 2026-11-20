# Harbor Architecture

Harbor is an Android TV / Google TV (`minSdk = 31`, `compileSdk = 35`) declarative 4K UHD video catalog and player built around a bounded, data-driven engine. Adding a compatible website requires only a validated JSON or YAML source definition.

---

## 1. Technology Stack & 2 GB RAM Optimization

- **Language & Concurrency**: Kotlin, ViewModel, coroutines (`Dispatchers.IO` enforced for all network/DNS/extraction), `StateFlow`.
- **UI & Media**: Jetpack Compose for TV (OLED living-room rail, Hero Spotlight, 6-frame focus storyboard previews, Cinema Player HUD), AndroidX Media3 ExoPlayer (`DefaultTrackSelector` unlocked to `3840×2160`, `HardwareCodecCapabilities` via `MediaCodecList`), Coil (`RGB565`, 8% heap cap, 128 MB disk cache).
- **ROM-Backed Media & HTTP Caching**: Media3 `SimpleCache` in `no_backup/harbor_media_cache` (1.0–2.0 GB configurable), `HarborMediaPreloader` (32 MB auto / 128 MB deep / 24 MB Up-Next ROM preload), and OkHttp (`ConnectionPool(3, 30s)`, 32 MB ROM HTTP cache).
- **Persistence & Backup**: Room (profiles, approved definitions, source health, versioned catalog cache, favorites, Watch Later, optional history/resume), DataStore (settings, taste profile, preferences), and `HarborBackupManager` (local non-secret JSON backup/restore).
- **Release Packaging**: R8/ProGuard shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`), producing a **7.4 MB** release APK.

---

## 2. Sixteen Core Components

1. **Source Definition Importer**: Loads local JSON/YAML files or user-entered HTTPS definition URLs without initiating source network traffic at import time.
2. **Schema Validator**: Validates against [source-definition.schema.json](../schemas/source-definition.schema.json) (`additionalProperties: false`).
3. **Semantic Validator**: Verifies schema version (`1.0`), capability-to-operation consistency, exact or explicit `*.domain.tld` declared hosts, acyclic `reuse`, valid transforms/selectors/JSON Pointers, declared interpolation variables, bounded execution limits, and absence of embedded credentials.
4. **Source Registry**: Tracks lifecycle states (`Imported`, `Validated`, `Awaiting approval`, `Enabled`, `Disabled`, `Incompatible`, `Broken`, `Pending update`) and performs atomic updates with rollback.
5. **Request Policy Engine**: Enforces `httpsOnly`, purpose-scoped host allowlists (`catalog`, `images`, `media`, `authentication`), redirect hop caps, rate limits, header restrictions, `GET`-only requests, and connection-time IP safety.
6. **HTTP Transport (`ControlledHttpTransport`)**: OkHttp layer executing on `Dispatchers.IO` with lean `ConnectionPool(3, 30s)`, 32 MB ROM disk cache, bounded ephemeral in-memory `CookieJar` (max 100 hosts, clearable for Private Sessions), response byte caps, and manual redirect verification.
7. **HTML Extraction Engine**: Uses `jsoup` selectors (`text`, `ownText`, `attribute`, `data`, `html`), ignores page `<base>` tags, expands bounded multi-candidate matches, and never evaluates JavaScript.
8. **JSON Extraction Engine**: Parses JSON strictly as data and extracts collections, object maps, object tags/thumbnails, and fields via RFC 6901 JSON Pointer.
9. **Transformation Engine**: Runs bounded chains (`<= 10`) of pure ops (`trim`, `collapseWhitespace`, `lowercase`, `uppercase`, `resolveUrl`, `canonicalUrl`, `parseInteger`, `parseDecimal`, `parseDuration`, `split`, `mapValues`, `deduplicate`, `urlEncode`, `template`, `regexExtract`, `regexExtractAll`, `unescapeJson`, `hexChunksToBase36`).
10. **Catalog Normalizer**: Produces canonical `CatalogItem` instances keyed by `(sourceId, sourceItemId)` with tag provenance (`explicit` or `derived`), normalized `maxQualityLabel`/`maxResolutionHeight`, and up to 6 evenly-sampled validated `thumbnailReferences`.
11. **Preference, Exclusion & Taste Engine**: `PreferenceFilterEngine` enforces source authorization, hidden videos, duration bounds, minimum resolution (`480p+`, `720p+`, `1080p+`, `4K Only`), hard tag exclusions (stripping artwork before rendering), and Standard/Strict metadata checks; `UserTasteEngine` tracks local label/pair/source affinities, builds top label combinations, and ranks fresh unseen items (`rankFreshAndPersonalized`).
12. **Catalog Repository**: Manages cold-start Room cache hydration, parallel multi-source search (`coroutineScope` + `async`), pagination (`nextLink`, `pageQuery`, `offsetLimit`, `jsonCursor`), Watch Later queue, Surprise Me shuffle, favorites, and optional history.
13. **Playback Resolver**: Resolves direct or bounded (`<= 3` steps) intermediate-page media URLs, validates candidate hosts/schemes/types, infers `resolutionHeight`, honors `minResolutionHeight` & `HardwareCodecCapabilities`, and supports 1-time retry on expired signed URLs.
14. **Native Playback Engine**: Media3 ExoPlayer for HLS, DASH, progressive MP4, speed, zoom/aspect ratio, quick-jump, sleep timer, loop, mute, Stats-for-Nerds telemetry HUD, Up-Next queue, and 5s auto-advance countdown.
15. **TV Presentation Layer**: Jetpack Compose for TV screens (`Welcome`, `Profile selection`, `Preference setup`, `Home`, `Categories`, `Search` with Android TV Voice Search, `Video details`, `Player`, `Favorites`, `Sources`, `Definition import`, `Definition diagnostics`, `Settings`).
16. **Diagnostics and Fixture Runner**: Runs offline fixtures with real network disabled (`OfflineFixtureHttpTransport`), validates minimum counts/required fields across all 11 bundled definitions, and exposes redacted diagnostics.

---

## 3. Module Boundaries & Data Flows

```text
:app
├── :core:model        :core:network      :core:storage
├── :core:security     :core:tvui
├── :engine:definition :engine:extract    :engine:transform
├── :engine:policy     :engine:fixtures
├── :domain:catalog    :domain:preferences :domain:playback   :domain:sources
└── :feature:{onboarding,catalog,search,details,player,sources,settings}
```

- **Definition Import**: `Source definition -> safe parsing -> schema validation -> semantic validation -> user approval -> source registry`
- **Browse Request**: `Browse request -> source operation -> validated HTTP request -> bounded extraction -> metadata normalization -> preference & taste filtering -> image loading -> display`
- **Play Request**: `Play request -> fresh preference & min-resolution check -> source playback resolution -> codec/destination validation -> ROM preloader / native player`
