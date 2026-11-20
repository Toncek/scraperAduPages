# Harbor — Declarative Android TV Video Browser

**Harbor** is a standalone Android TV / Google TV (`minSdk = 31`, Android 12+) 4K UHD video catalog and native player application. Compatible video sources are integrated entirely through validated, data-only JSON or YAML definitions—no Kotlin code changes, executable plugins, or arbitrary JavaScript execution are permitted.

## Key Principles

- **Declarative, Not Executable**: Sources describe approved hosts, HTTP `GET` operations, HTML selectors (`jsoup`) or RFC 6901 JSON Pointers, bounded transforms, and Media3 ExoPlayer playback resolution rules.
- **4K UHD & 2 GB RAM TV Optimization**: Full 3840×2160 playback with `MediaCodecList` hardware 4K codec matching, 1–2 GB persistent ROM `SimpleCache` & background preloader, Coil RGB565 downsampling (128 MB disk cache), 32 MB OkHttp disk cache, lean `ConnectionPool(3, 30s)`, and a 7.4 MB R8-shrunk release APK.
- **Security & Privacy First**: Exact or explicit `*.domain.tld` host matching per purpose (`catalog`, `images`, `media`, `authentication`), connection-time private-IP blocking, strict filtering **before** artwork requests, no cloud accounts, no analytics, Private Sessions, and non-secret local backup/restore.
- **TV Remote Native**: Living-room OLED Compose for TV UI with auto-expanding D-pad rail, Hero Spotlight, 6-frame storyboard hover previews, Watch Later queue, Surprise Me shuffle, local taste combos & anti-repeat freshness ranking, Android TV Voice Search, and Cinema Player (speed, zoom, quick-jump, sleep timer, loop, mute, Stats-for-Nerds, and 5s Up-Next auto-advance).
- **Reference Isolation**: Compatibility research from `magicoflolis/Magic-PH` (pinned at `5764d037d9b52009218815152ef4e976c2c62255`) and `dataset.json` is stored strictly as non-executable `reference-only` metadata.

## Repository Layout

- [MasterAppSpecification.md](MasterAppSpecification.md) — Normative Harbor v1.0 specification.
- [AGENTS.md](AGENTS.md) — Architecture, DSL rules, security constraints, and agent workflow.
- [BACKLOG.md](BACKLOG.md) — Engineering audit, completed P0–P3 & UX upgrades, and remaining backlog.
- [schemas/source-definition.schema.json](schemas/source-definition.schema.json) — Canonical JSON Schema (Draft 2020-12, `additionalProperties: false`) mirrored at [app/src/main/assets/schemas/source-definition.schema.json](app/src/main/assets/schemas/source-definition.schema.json).
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 16-component architecture, module boundaries, data flows, and validation stages.
- [docs/SECURITY_AND_PRIVACY.md](docs/SECURITY_AND_PRIVACY.md) — Network policy, address safety, credential hygiene, privacy defaults, and Magic-PH isolation rules.
- [docs/COMPATIBILITY_MATRIX.md](docs/COMPATIBILITY_MATRIX.md) — Compatibility levels (1–4), all 11 bundled definitions (7 live 4K/HD + 4 demo/reference), and 27-platform `reference-only` matrix.
- [app/src/main/assets/reference/magic-ph-settings.yaml](app/src/main/assets/reference/magic-ph-settings.yaml) — Declarative `reference-only` host/capability profiles for 27 platforms.
- [app/src/main/assets/reference/platform-dataset.json](app/src/main/assets/reference/platform-dataset.json) — Public interface & ToS compliance dataset.
- [app/src/main/assets/definitions/](app/src/main/assets/definitions/) — 11 schema-validated bundled YAML source definitions.
- [app/src/main/assets/fixtures/](app/src/main/assets/fixtures/) — Sanitized offline HTML/JSON test fixtures for all 11 bundled definitions.

## Bundled Source Definitions (11 Total: 7 Live 4K/HD + 4 Demo/Reference)

| Definition | Level | Response Format | Playback Support |
| --- | --- | --- | --- |
| `eporner-api-v2.yaml` | Level 2/4 | JSON REST API v2 + Embed/XHR | Live 4K (2160p) MP4 + 6-frame storyboard |
| `porntrex-html.yaml` | Level 1/3 | HTML + `<script>` MP4 | Live 4K (2160p) MP4 + 6-frame storyboard |
| `xhamster-html.yaml` | Level 1/3 | HTML + `<script>` Adaptive HLS | Live 4K (2160p) HLS |
| `pornhub-webmaster.yaml` | Level 2/3 | Webmasters JSON + Embed `<script>` | Live HD HLS + 6-frame storyboard |
| `xvideos-html.yaml` | Level 1/3 | HTML + `<script>` HLS/MP4 | Live 4K/HD HLS & MP4 |
| `xnxx-html.yaml` | Level 1/3 | HTML + `<script>` HLS/MP4 | Live HD HLS & MP4 |
| `redtube-api.yaml` | Level 2/4 | Query JSON + Embed/HLS JSON | Live HD HLS + 6-frame storyboard |
| `chaturbate-affiliates.yaml` | Level 2 | Live Rooms JSON API | Demo/Reference live discovery (`playback: false`) |
| `example-library.yaml` | Level 1 | Static HTML | Offline reference `<video><source>` |
| `embedded-json-catalog.yaml` | Level 3 | HTML + Embedded JSON `<script>` | Offline reference JSON Pointer extraction |
| `intermediate-embed-demo.yaml` | Level 4 | HTML 2-Step Traversal | Offline reference declared embed traversal |
