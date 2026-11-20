# Harbor Architecture

Harbor is an Android TV / Google TV (`minSdk = 31`) declarative video catalog and player built around a bounded, data-driven engine. Adding a compatible website requires only a validated JSON or YAML source definition.

---

## 1. Recommended Technology Stack

- **Language & Concurrency**: Kotlin, ViewModel, coroutines, `StateFlow`.
- **UI & Media**: Jetpack Compose for TV, AndroidX Media3 ExoPlayer, Coil (policy-controlled image loading).
- **Persistence**: Room (profiles, approved definitions, source health, versioned catalog cache, favorites, optional history/resume), DataStore (simple settings).
- **Network & Parsing**: OkHttp, `jsoup` (HTML selectors), strict JSON parser, safe data-only YAML parser, JSON Schema Draft 2020-12 validator.

---

## 2. Sixteen Core Components

1. **Source Definition Importer**: Loads local JSON/YAML files or user-entered HTTPS definition URLs without initiating source network traffic at import time.
2. **Schema Validator**: Validates against [source-definition.schema.json](../schemas/source-definition.schema.json) (`additionalProperties: false`).
3. **Semantic Validator**: Verifies schema version (`1.0`), capability-to-operation consistency, exact declared hosts, acyclic `reuse`, valid transforms/selectors/JSON Pointers, declared interpolation variables, bounded execution limits, and absence of embedded credentials.
4. **Source Registry**: Tracks lifecycle states (`Imported`, `Validated`, `Awaiting approval`, `Enabled`, `Disabled`, `Incompatible`, `Broken`, `Pending update`) and performs atomic updates with rollback.
5. **Request Policy Engine**: Enforces `httpsOnly`, purpose-scoped exact host allowlists (`catalog`, `images`, `media`, `authentication`), redirect hop caps, rate limits, header restrictions, `GET`-only requests, and connection-time IP safety.
6. **HTTP Transport**: OkHttp layer applying policy timeouts, response byte caps, and redirect verification.
7. **HTML Extraction Engine**: Uses `jsoup` selectors (`text`, `ownText`, `attribute`, `data`) without evaluating JavaScript.
8. **JSON Extraction Engine**: Parses JSON strictly as data and extracts collections/fields via JSON Pointer.
9. **Transformation Engine**: Runs bounded chains (`<= 10`) of the 12 MVP ops (`trim`, `collapseWhitespace`, `lowercase`, `uppercase`, `resolveUrl`, `canonicalUrl`, `parseInteger`, `parseDecimal`, `parseDuration`, `split`, `mapValues`, `deduplicate`).
10. **Catalog Normalizer**: Produces canonical `CatalogItem` instances keyed by `(sourceId, sourceItemId)` with tag provenance (`explicit` or `derived`).
11. **Preference and Exclusion Engine**: Applies source authorization, profile rules, hard exclusions, Standard/Strict metadata checks, category/search filters, and ranking across all entry points.
12. **Catalog Repository**: Manages Room caching (invalidated on definition version changes), pagination cursors (`nextLink`, `pageQuery`, `offsetLimit`, `jsonCursor`), favorites, and optional history.
13. **Playback Resolver**: Resolves direct or bounded (`<= 3` steps) intermediate-page media URLs, validates candidate hosts/schemes/types, and supports one retry on expired signed URLs.
14. **Native Playback Engine**: Media3 ExoPlayer for HLS, DASH, progressive MP4, subtitles, and audio track selection.
15. **TV Presentation Layer**: Jetpack Compose for TV screens (`Welcome`, `Profile selection`, `Preference setup`, `Home`, `Categories`, `Search`, `Video details`, `Player`, `Favorites`, `Sources`, `Definition import`, `Definition diagnostics`, `Settings`).
16. **Diagnostics and Fixture Runner**: Runs offline fixtures with real network disabled, validates minimum item/candidate counts and required fields, and exposes redacted error codes (`DEFINITION_INVALID`, `DESTINATION_BLOCKED`, `SOURCE_LAYOUT_CHANGED`, `STREAM_EXPIRED`, etc.).

---

## 3. Module Boundaries

```text
:app
├── :core:model        :core:network      :core:storage
├── :core:security     :core:tvui
├── :engine:definition :engine:extract    :engine:transform
├── :engine:policy     :engine:fixtures
├── :domain:catalog    :domain:preferences :domain:playback
└── :feature:{onboarding,catalog,search,details,player,sources,settings}
```

---

## 4. Data Flows

### 4.1 Definition Import
`Source definition -> safe parsing -> schema validation -> semantic validation -> user approval -> source registry`

### 4.2 Browse Request
`Browse request -> source operation -> validated HTTP request -> bounded extraction -> metadata normalization -> preference filtering -> image loading -> display`

### 4.3 Play Request
`Play request -> fresh preference check -> source playback resolution -> destination validation -> native player`

---

## 5. Validation Stages & Default Budgets

Every definition passes 9 sequential validation stages:
1. File size check (`<= 512 KiB`)
2. Safe syntax parsing (restricted YAML data model)
3. Duplicate-key rejection
4. JSON Schema validation
5. Cross-field semantic validation
6. Capability compatibility check
7. Host and URL validation
8. Offline fixture execution
9. Optional user-approved live probe

Default engine execution budgets:
- Definition size: `512 KiB`
- Catalog response: `2 MiB`
- Items per page: `100`
- Playback candidates: `20`
- Redirects: `3`
- Playback resolver HTTP steps: `3`
- Transform chain length: `10`
- Overall operation deadline: `30s`
