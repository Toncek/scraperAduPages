# AGENTS.md — Harbor Android TV Development Guide

Harbor is a declarative Android TV / Google TV video catalog and player application (`minSdk = 31`, Android 12+). Compatible sources are integrated through validated JSON/YAML configuration files interpreted by a bounded engine—never by executable plugins or arbitrary JavaScript.

All design and implementation work MUST conform to [MasterAppSpecification.md](MasterAppSpecification.md) and [schemas/source-definition.schema.json](schemas/source-definition.schema.json).

---

## 1. Architecture & 16 Core Components

Harbor separates definition parsing, policy enforcement, extraction, domain logic, and Compose TV presentation across 16 components (Section 5.1 of `MasterAppSpecification.md`):

1. **Source definition importer** — Reads local JSON/YAML or user-entered HTTPS definition URLs without triggering network requests during import.
2. **Schema validator** — Validates normalized definitions against `source-definition.schema.json` (`additionalProperties: false`, strict `schemaVersion: "1.0"`).
3. **Semantic validator** — Enforces cross-field rules: supported schema version, required `playback` operation when `capabilities.playback` is true, declared hosts only, acyclic `reuse` references, valid transforms/selectors/JSON Pointers, declared variables only, bounded execution budgets, and zero embedded credentials.
4. **Source registry** — Manages lifecycle states (`Imported`, `Validated`, `Awaiting approval`, `Enabled`, `Disabled`, `Incompatible`, `Broken`, `Pending update`) and atomic updates/rollbacks.
5. **Request policy engine** — Enforces `httpsOnly`, exact host matching by purpose (`catalog`, `images`, `media`, `authentication`), redirect limits (`maxHops <= 3` default), rate limits, header allowlists, GET-only MVP, and connection-time address safety.
6. **HTTP transport** — Controlled OkHttp client executing policy-approved requests with bounded timeouts and response byte limits (`2 MiB` default for catalog responses).
7. **HTML extraction engine** — `jsoup`-based CSS selector extraction (`text`, `ownText`, `attribute`, `data` for embedded JSON `<script>` blocks). Never executes JavaScript.
8. **JSON extraction engine** — Strict JSON parser with JSON Pointer (`/...`) collection and field extraction. Never evaluates JSON as JavaScript.
9. **Transformation engine** — Bounded chains (max 10 ops) over the 12 MVP ops: `trim`, `collapseWhitespace`, `lowercase`, `uppercase`, `resolveUrl`, `canonicalUrl`, `parseInteger`, `parseDecimal`, `parseDuration`, `split`, `mapValues`, `deduplicate`.
10. **Catalog normalizer** — Maps extracted records into canonical `CatalogItem` models with stable `(sourceId, sourceItemId)` identities and tag provenance (`explicit` vs `derived`).
11. **Preference and exclusion engine** — Evaluates source authorization, profile restrictions, hard exclusions, required metadata policy (Standard vs Strict), search/category constraints, and ranking across every entry point before artwork loading.
12. **Catalog repository** — Coordinates Room persistence, source-versioned cache invalidation, favorites, optional history/resume state, and `nextLink` / cursor pagination.
13. **Playback resolver** — Resolves direct or bounded intermediate-page (`<= 3` steps) media candidates, validates candidate scheme/host/length/policy, and handles single-retry re-resolution on expired signed URLs.
14. **Native playback engine** — AndroidX Media3 ExoPlayer supporting HLS, DASH, progressive MP4, subtitles, and audio track selection.
15. **TV presentation layer** — Jetpack Compose for TV UI navigable entirely by 5-way D-pad (`Up`, `Down`, `Left`, `Right`, `Center/OK`, `Back`) with visible focus, focus restoration, and zero pointer/hover/long-press dependencies.
16. **Diagnostics and fixture runner** — Executes offline fixtures with real network disabled, verifies output counts and required fields, tracks source health, and surfaces redacted error taxonomy codes.

### Suggested Module Boundaries (Section 5.3)
`:app`, `:core:model`, `:core:network`, `:core:storage`, `:core:security`, `:core:tvui`, `:engine:definition`, `:engine:extract`, `:engine:transform`, `:engine:policy`, `:engine:fixtures`, `:domain:catalog`, `:domain:preferences`, `:domain:playback`, `:feature:onboarding`, `:feature:catalog`, `:feature:search`, `:feature:details`, `:feature:player`, `:feature:sources`, `:feature:settings`.

### Core Data Flows (Section 5.2)
- **Import**: `Source definition -> safe parsing -> schema validation -> semantic validation -> user approval -> source registry`
- **Browse**: `Browse request -> source operation -> validated HTTP request -> bounded extraction -> metadata normalization -> preference filtering -> image loading -> display`
- **Play**: `Play request -> fresh preference check -> source playback resolution -> destination validation -> native player`

---

## 2. Source DSL Rules

- **Safe Parsing**: YAML must use a restricted JSON-compatible data model. Reject custom object tags, arbitrary object construction, executable expressions, unbounded alias expansion, duplicate mapping keys, and non-string object keys.
- **Compatibility Levels**:
  - **Level 1**: Static HTML (`jsoup` selectors + `<video><source src>`).
  - **Level 2**: Documented/authorized JSON endpoints (JSON Pointer).
  - **Level 3**: Embedded JSON inside a `<script>` element parsed strictly as JSON (`document.embeddedJson`).
  - **Level 4**: Bounded multi-step intermediate-page traversal (`operations.playback.steps`, max 3 steps, every host declared in `network.allowedHosts`).
  - **Unsupported**: Arbitrary JS players, custom crypto URL derivation, CAPTCHA/anti-bot bypass, DRM bypass, undeclared host traversal.
- **Interpolation**: Only `${source.baseUrl}`, `${input.url}`, `${input.query}`, `${input.detailUrl}`, and declared previous-step outputs (e.g., `${detailPage.embedUrl}`). Query parameters must be encoded via `request.query`, never raw string concatenation.
- **Stable Identity**: `(sourceId, sourceItemId)`. Playback URLs must never be used as permanent catalog IDs.
- **Reuse**: `reuse.extractFrom` and `reuse.paginationFrom` may only reference operations in the same definition; cyclic or incompatible reuse must be rejected.
- **Default Execution Budgets**: Definition `<= 512 KiB`, catalog response `<= 2 MiB`, items per page `<= 100`, playback candidates `<= 20`, redirects `<= 3`, resolver steps `<= 3`, transform chain `<= 10`, operation deadline `<= 30s`.

---

## 3. Security & Privacy Constraints

1. **No JavaScript Execution**: Never execute website JS, userscripts, bookmarklets, or `window.DATA = ...` assignments.
2. **Exact Host Matching**: `network.allowedHosts` (`catalog`, `images`, `media`, `authentication`) MUST list exact hostnames. Never imply wildcard subdomains (`*.example.com`).
3. **Address Safety**: For internet sources (`lanAccess: false`), reject loopback, link-local, and private-network IPs at connection time. Validate every redirect hop and never forward credentials cross-origin.
4. **Filtering Before Artwork**: Evaluate exclusions and Strict-mode metadata requirements **before** requesting thumbnails or displaying title cards across Home, Categories, Search, Favorites, History, Deep Links, Autoplay, and restored state.
5. **GET-Only MVP & Header Restrictions**: Only `GET` is allowed in MVP. Reject definitions attempting to set `Host`, `Content-Length`, `Connection`, `Proxy-Authorization`, `Cookie`, or `Authorization`, or containing header control characters.
6. **Zero Embedded Secrets**: Definitions and fixtures MUST NOT contain passwords, session cookies, API secrets, or active signed playback URLs.
7. **Privacy by Default**: No cloud account, no analytics, no autoplay previews, no casting, no launcher recommendations, optional local history, and non-persisting Private Sessions.

---

## 4. `magicoflolis/Magic-PH` Reference Policy

`Magic-PH` (MIT License) is present in this repository strictly as **metadata and compatibility research**.

- **Pinned Commit**: `5764d037d9b52009218815152ef4e976c2c62255`
- **Declarative Reference File**: `app/src/main/assets/reference/magic-ph-settings.yaml`
- **Platform Dataset**: `app/src/main/assets/reference/platform-dataset.json` (copied from `dataset.json`)
- **Mandatory Rules**:
  - Entries derived from `Magic-PH` MUST carry `sourceStatus: "reference-only"` and MUST NOT auto-activate.
  - NEVER copy, import, translate, or execute `Magic-PH` JavaScript, userscript APIs (`GM_*`), browser extension globals, or download/adblock/player-override scripts.
  - NEVER infer wildcard host permissions from userscript `@match` patterns.
  - Promoting a reference entry to an active source requires independent authorization/ToS review, an explicit YAML/JSON definition, exact host lists, sanitized offline fixtures, schema/semantic validation, and explicit user approval.

---

## 5. Agent Development Workflow

1. **Schema-First Extensions (Section 28)**: Never add website-specific Kotlin hacks or smuggle code into YAML. If a capability is missing, specify the general primitive, update `schemas/source-definition.schema.json` (and its asset copy in `app/src/main/assets/schemas/`), implement it in `:engine:*`, add security + conformance tests, and then update the definition.
2. **Validate Every Definition**: Every YAML/JSON definition in `app/src/main/assets/definitions/` must validate against `source-definition.schema.json` and pass offline fixture tests in `app/src/main/assets/fixtures/` with real networking disabled.
3. **URL Hygiene in YAML**: Always write plain valid URL strings (e.g., `"https://video.example.org"`), never Markdown link syntax inside YAML values.
4. **TV-First Verification**: All UI changes must preserve `minSdk = 31`, TV launcher configuration, visible focus indicators, D-pad navigation, and Back-stack focus restoration.
5. **Redacted Diagnostics**: Never log cookies, authorization tokens, or session credentials in diagnostics or error reports.
