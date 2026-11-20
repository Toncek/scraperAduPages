# Harbor — Declarative Android TV Video Browser

**Harbor** is a standalone Android TV / Google TV (`minSdk = 31`, Android 12+) video catalog and native player application. Compatible video sources are integrated entirely through validated, data-only JSON or YAML definitions—no Kotlin code changes, executable plugins, or arbitrary JavaScript execution are permitted.

## Key Principles

- **Declarative, Not Executable**: Sources describe approved hosts, HTTP `GET` operations, HTML selectors (`jsoup`) or JSON Pointers, bounded transforms, and Media3 ExoPlayer playback resolution rules.
- **Security & Privacy First**: Exact host matching per purpose (`catalog`, `images`, `media`, `authentication`), connection-time private-IP blocking, strict filtering **before** artwork requests, no cloud accounts, and no analytics.
- **TV Remote Native**: Designed for Jetpack Compose for TV with 5-way D-pad navigation, visible focus states, and focus restoration.
- **Reference Isolation**: Compatibility research from `magicoflolis/Magic-PH` (pinned at `5764d037d9b52009218815152ef4e976c2c62255`) and `dataset.json` is stored strictly as non-executable `reference-only` metadata.

## Repository Layout

- [MasterAppSpecification.md](MasterAppSpecification.md) — Normative Harbor v1.0 specification.
- [AGENTS.md](AGENTS.md) — Architecture, DSL rules, security constraints, and agent workflow.
- [schemas/source-definition.schema.json](schemas/source-definition.schema.json) — Canonical JSON Schema (Draft 2020-12, `additionalProperties: false`) mirrored at [app/src/main/assets/schemas/source-definition.schema.json](app/src/main/assets/schemas/source-definition.schema.json).
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 16-component architecture, module boundaries, data flows, and validation stages.
- [docs/SECURITY_AND_PRIVACY.md](docs/SECURITY_AND_PRIVACY.md) — Network policy, address safety, credential hygiene, privacy defaults, and Magic-PH isolation rules.
- [docs/COMPATIBILITY_MATRIX.md](docs/COMPATIBILITY_MATRIX.md) — Compatibility levels (1–4), bundled definitions, and reference-only platform matrix.
- [app/src/main/assets/reference/magic-ph-settings.yaml](app/src/main/assets/reference/magic-ph-settings.yaml) — Declarative `reference-only` host/capability profiles for 27 platforms.
- [app/src/main/assets/reference/platform-dataset.json](app/src/main/assets/reference/platform-dataset.json) — Public interface & ToS compliance dataset.
- [app/src/main/assets/definitions/](app/src/main/assets/definitions/) — 7 schema-validated bundled YAML source definitions.
- [app/src/main/assets/fixtures/](app/src/main/assets/fixtures/) — Sanitized offline HTML/JSON test fixtures for all 7 bundled definitions.

## Bundled Source Definitions

| Definition | Level | Response Format | Playback Support |
| --- | --- | --- | --- |
| `example-library.yaml` | Level 1 | Static HTML | Direct `<video><source>` |
| `eporner-api-v2.yaml` | Level 2 | JSON REST API v2 | Metadata only (`playback: false`) |
| `redtube-api.yaml` | Level 2 | JSON Query API | Metadata only (`playback: false`) |
| `pornhub-webmaster.yaml` | Level 2 | Webmasters JSON API | Metadata only (`playback: false`) |
| `chaturbate-affiliates.yaml` | Level 2 | Live Rooms JSON API | Live discovery (`playback: false`) |
| `embedded-json-catalog.yaml` | Level 3 | HTML + Embedded JSON `<script>` | JSON Pointer stream extraction |
| `intermediate-embed-demo.yaml` | Level 4 | HTML 2-Step Traversal | Declared embed host -> stream extraction |
