# Master Specification: Declarative Android TV Video Browser

Specification version: 1.0
Working name: Harbor
Minimum platform: Android TV / Google TV, Android 12, API 31
Application type: General-purpose video catalog and player
Source integration model: Declarative JSON/YAML definitions
Primary input: Television remote
Default deployment: Standalone Android application
Scope: Authorized sources, user-owned media, and permitted public content

---

## 1. Executive Summary

Build an Android TV application that browses categories and plays videos from multiple supported websites through a single remote-friendly interface.

Adding a compatible website must require only a JSON or YAML source definition, not application code changes.

A source definition describes:

- Source identity.
- Approved network destinations.
- Category discovery.
- Video listings.
- Search.
- Pagination.
- Video details.
- Metadata and tag mapping.
- Playback URL extraction.
- Optional approved intermediate-page traversal.
- Request policies.
- Compatibility fixtures.

The application interprets these definitions using a bounded, declarative engine.

“Universal” means reusable extraction primitives across compatible sources. It does not mean unrestricted compatibility with every website.

The MVP must not execute arbitrary JavaScript, download executable plugins, bypass access controls, or promise removal of advertising embedded in third-party video.

---

## 2. Normative Language

MUST indicates a release-blocking requirement.

SHOULD indicates the default implementation unless a documented reason justifies an alternative.

MAY indicates an optional capability.

All schemas and examples in this document describe the intended application contract. They are not claims that a ready-made library already implements the complete engine.

---

## 3. Product Goals

### 3.1 Primary goals

1. Add compatible websites using configuration files.
2. Browse categories from multiple sources.
3. Search within supported sources.
4. Apply user preferences and exclusions consistently.
5. Play supported streams with a reliable native player.
6. Work completely with a basic television remote.
7. Keep personal preferences and history local by default.
8. Make source failures diagnosable without exposing credentials.

### 3.2 Non-goals

The initial release MUST NOT attempt:

- Guaranteed compatibility with arbitrary websites.
- Arbitrary website JavaScript execution.
- CAPTCHA or anti-bot circumvention.
- DRM or subscription bypass.
- Automatic discovery of undocumented private APIs.
- Unbounded recursive crawling.
- Generic video-ad segment removal.
- Remote executable plugins.
- General-purpose web browsing.
- Cloud accounts or synchronization.

---

## 4. Recommended Technology Stack

- Kotlin.
- Jetpack Compose for TV.
- AndroidX Media3 ExoPlayer.
- ViewModel, coroutines, and StateFlow.
- Room for structured persistence.
- DataStore for simple settings.
- OkHttp for controlled HTTP requests.
- jsoup for HTML parsing and selectors.
- A JSON parser with a strict application data model.
- A YAML parser configured for safe, data-only loading.
- A JSON Schema validator compatible with the selected schema dialect.
- Coil or equivalent for policy-controlled image loading.

The exact dependency versions MUST be pinned during implementation and updated through a tested dependency process.

### 4.1 Android configuration

- minSdk MUST be 31.
- compileSdk and targetSdk MUST be selected separately from minSdk.
- The application MUST declare TV support.
- Touchscreen hardware MUST NOT be required.
- The manifest MUST include the appropriate TV launcher entry and artwork.
- Store-specific requirements MUST be rechecked before distribution.

---

## 5. Architecture

### 5.1 Major components

1. Source definition importer.
2. Schema validator.
3. Semantic validator.
4. Source registry.
5. Request policy engine.
6. HTTP transport.
7. HTML extraction engine.
8. JSON extraction engine.
9. Transformation engine.
10. Catalog normalizer.
11. Preference and exclusion engine.
12. Catalog repository.
13. Playback resolver.
14. Native playback engine.
15. TV presentation layer.
16. Diagnostics and fixture runner.

### 5.2 Data flow

Source definition
→ safe parsing
→ schema validation
→ semantic validation
→ user approval
→ source registry

Browse request
→ source operation
→ validated HTTP request
→ bounded extraction
→ metadata normalization
→ preference filtering
→ image loading
→ display

Play request
→ fresh preference check
→ source playback resolution
→ destination validation
→ native player

### 5.3 Module boundaries

Suggested structure:

:app
:core:model
:core:network
:core:storage
:core:security
:core:tvui
:engine:definition
:engine:extract
:engine:transform
:engine:policy
:engine:fixtures
:domain:catalog
:domain:preferences
:domain:playback
:feature:onboarding
:feature:catalog
:feature:search
:feature:details
:feature:player
:feature:sources
:feature:settings

A small implementation MAY initially use packages instead of separate Gradle modules.

---

## 6. Definition Format and Versioning

### 6.1 Input formats

The importer MUST accept:

- JSON.
- YAML using a restricted JSON-compatible data model.

YAML MUST NOT allow:

- Custom object tags.
- Arbitrary object construction.
- Executable expressions.
- Unbounded alias expansion.
- Duplicate mapping keys.
- Non-string object keys.

Both formats MUST normalize to the same internal definition model.

### 6.2 Versions

Definitions MUST include:

- schemaVersion: definition language version.
- source.version: source configuration revision.

Example:

schemaVersion: "1.0"
source:
  id: "example-library"
  version: "1.0.0"

An unsupported major schema version MUST be rejected.

Unknown configuration properties MUST be rejected unless explicitly allowed by the schema.

### 6.3 Definition lifecycle

A definition can be:

- Imported.
- Validated.
- Awaiting approval.
- Enabled.
- Disabled.
- Incompatible.
- Broken.
- Pending update.

Importing a definition MUST NOT immediately execute network requests.

---

## 7. Source Compatibility Levels

### Level 1: Static HTML

Capabilities:

- Categories in returned HTML.
- Video cards in returned HTML.
- Detail metadata in returned HTML.
- Direct media URLs in video/source elements.

MVP requirement.

### Level 2: JSON endpoints

Capabilities:

- Documented or otherwise authorized JSON catalog endpoints.
- JSON-based categories, listings, and playback metadata.

MVP requirement.

### Level 3: Embedded JSON

Capabilities:

- Parse valid JSON from a selected script element.
- Extract fields using JSON Pointer.
- No JavaScript evaluation.

MVP requirement.

### Level 4: Approved intermediate pages

Capabilities:

- Follow a declared link or iframe.
- Validate each destination.
- Apply a declared extraction rule to the next page.
- Respect strict traversal limits.

Optional after the basic resolver is stable.

### Unsupported by configuration alone

- Arbitrary JavaScript player execution.
- Custom cryptographic URL derivation.
- Proprietary executable player logic.
- Unsupported interactive authentication.
- CAPTCHA challenges.
- Undeclared host traversal.
- DRM without an authorized integration.

Unsupported sources MUST receive a clear compatibility explanation.

---

## 8. Complete YAML Source Template

The following is a fictional source definition.

All example hostnames are placeholders.

```yaml
schemaVersion: "1.0"

source:
  id: "example-library"
  name: "Example Video Library"
  version: "1.0.0"
  description: "An authorized example video catalog."
  language: "en"
  baseUrl: "[https://video.example.org](https://video.example.org)"
  enabledByDefault: false

  permission:
    basis: "publisher-permission"
    reference: "Local permission record"

  capabilities:
    categories: true
    search: true
    details: true
    playback: true
    live: false

network:
  httpsOnly: true

  allowedHosts:
    catalog:
      - "video.example.org"
    images:
      - "images.example.org"
    media:
      - "media.example.org"

  redirects:
    maxHops: 3

  timeouts:
    connectMs: 10000
    readMs: 15000
    operationMs: 30000

  limits:
    maxConcurrentRequests: 2
    minRequestIntervalMs: 500
    maxCatalogResponseBytes: 2097152
    maxItemsPerPage: 100

  headers:
    Accept: "text/html,application/json"

authentication:
  type: "none"

taxonomy:
  namespace: "example-library"
  mappings:
    "Documentaries":
      - "genre.documentary"
    "Nature":
      - "topic.nature"
    "Education":
      - "genre.educational"
    "English":
      - "language.en"

operations:
  categories:
    request:
      method: "GET"
      url: "${source.baseUrl}/categories"

    response:
      type: "html"

    extract:
      items:
        selector: "nav.categories a.category-link"
        limit: 100

        fields:
          id:
            read: "attribute"
            attribute: "href"
            transforms:
              - op: "resolveUrl"
              - op: "canonicalUrl"
            required: true

          title:
            read: "text"
            transforms:
              - op: "trim"
              - op: "collapseWhitespace"
            required: true

          url:
            read: "attribute"
            attribute: "href"
            transforms:
              - op: "resolveUrl"
            required: true

  listing:
    request:
      method: "GET"
      url: "${input.url}"

    response:
      type: "html"

    extract:
      items:
        selector: "article.video-card"
        limit: 100

        fields:
          id:
            selector: "a.video-link"
            read: "attribute"
            attribute: "href"
            transforms:
              - op: "resolveUrl"
              - op: "canonicalUrl"
            required: true

          title:
            selector: "h2.title"
            read: "text"
            transforms:
              - op: "trim"
              - op: "collapseWhitespace"
            required: true

          detailUrl:
            selector: "a.video-link"
            read: "attribute"
            attribute: "href"
            transforms:
              - op: "resolveUrl"
            required: true

          thumbnailUrl:
            selector: "img.thumbnail"
            read: "attribute"
            attribute: "data-src"
            fallback:
              selector: "img.thumbnail"
              read: "attribute"
              attribute: "src"
            transforms:
              - op: "resolveUrl"
            required: false

          durationSeconds:
            selector: ".duration"
            read: "text"
            transforms:
              - op: "trim"
              - op: "parseDuration"
            required: false

          sourceTags:
            selector: ".tags a"
            read: "text"
            cardinality: "many"
            transforms:
              - op: "trim"
            required: false

    pagination:
      mode: "nextLink"
      next:
        selector: "a[rel=next]"
        read: "attribute"
        attribute: "href"
        transforms:
          - op: "resolveUrl"

  search:
    request:
      method: "GET"
      url: "${source.baseUrl}/search"
      query:
        q: "${input.query}"

    response:
      type: "html"

    reuse:
      extractFrom: "listing"
      paginationFrom: "listing"

  details:
    request:
      method: "GET"
      url: "${input.detailUrl}"

    response:
      type: "html"

    extract:
      fields:
        title:
          selector: "h1"
          read: "text"
          transforms:
            - op: "trim"
          required: true

        description:
          selector: ".video-description"
          read: "text"
          required: false

        sourceTags:
          selector: ".video-tags a"
          read: "text"
          cardinality: "many"
          transforms:
            - op: "trim"
          required: false

  playback:
    steps:
      - id: "detailPage"

        request:
          method: "GET"
          url: "${input.detailUrl}"

        response:
          type: "html"

        extract:
          candidates:
            selector: "video source[src]"
            limit: 20

            fields:
              url:
                read: "attribute"
                attribute: "src"
                transforms:
                  - op: "resolveUrl"
                required: true

              mimeType:
                read: "attribute"
                attribute: "type"
                required: false

    result:
      candidatesFrom: "detailPage.candidates"

playback:
  allowedTypes:
    - "hls"
    - "dash"
    - "progressive"

  preserveMediaQueryParameters: true

  onExpired:
    action: "resolveAgain"
    maxAttempts: 1

fixtures:
  - id: "categories-basic"
    operation: "categories"
    responseFile: "fixtures/categories.html"
    expected:
      minimumItems: 1
      requiredFields:
        - "id"
        - "title"
        - "url"

  - id: "listing-basic"
    operation: "listing"
    responseFile: "fixtures/listing.html"
    input:
      url: "[https://video.example.org/categories/nature](https://video.example.org/categories/nature)"
    expected:
      minimumItems: 1
      requiredFields:
        - "id"
        - "title"
        - "detailUrl"

  - id: "playback-basic"
    operation: "playback"
    responseFile: "fixtures/details.html"
    input:
      detailUrl: "[https://video.example.org/watch/example](https://video.example.org/watch/example)"
    expected:
      minimumCandidates: 1
```

---

## 9. Template Semantics

### 9.1 Operation inputs

Allowed inputs are operation-specific:

categories:
- No user URL input.

listing:
- input.url: approved category URL or validated pagination URL.

search:
- input.query: user text.

details:
- input.detailUrl: normalized catalog item URL.

playback:
- input.detailUrl: normalized catalog item URL.

The engine MUST reject undeclared variables.

### 9.2 Interpolation

Supported interpolation:

- ${source.baseUrl}
- ${input.url}
- ${input.query}
- ${input.detailUrl}
- Explicitly declared previous-step outputs.

Interpolation MUST NOT evaluate expressions.

Query parameter values MUST be encoded as query parameters, not concatenated directly into URLs.

A URL produced by interpolation MUST pass request-policy validation before use.

### 9.3 Field extraction

Each field can declare:

- selector.
- read.
- attribute.
- cardinality.
- fallback.
- transforms.
- required.

Supported read operations:

- text.
- ownText.
- attribute.

For HTML:

- A missing selector means the current item element.
- cardinality defaults to one.
- cardinality many returns an array.
- Required missing fields invalidate the item.
- Optional missing fields return null or an empty array.
- Fallback is attempted only when the primary extraction is absent or blank.

For collection fields, transforms apply independently to each extracted value.

### 9.4 Resolution context

Relative URLs MUST resolve against the final validated response URL after redirects.

Resolved URLs MUST be checked against the appropriate host policy.

The engine MUST NOT trust a page-provided base element to expand network permissions.

### 9.5 Stable identity

The internal identity MUST be:

(sourceId, sourceItemId)

A canonical source page URL MAY be used as sourceItemId when no publisher ID exists.

canonicalUrl MUST:

- Normalize scheme and hostname casing.
- Remove fragments where they do not identify distinct content.
- Preserve query parameters by default.
- Avoid assumptions about trailing slash equivalence.

Playback URLs MUST NOT be used as permanent catalog IDs.

### 9.6 Reuse

reuse.extractFrom and reuse.paginationFrom refer only to operations in the same definition.

The validator MUST:

- Resolve references before execution.
- Reject missing references.
- Reject cyclic reuse.
- Reject incompatible reuse.
- Expand reuse into a validated execution plan.

---

## 10. JSON Extraction

### 10.1 JSON Pointer

JSON extraction MUST use JSON Pointer for the initial implementation.

Example:

```yaml
response:
  type: "json"

extract:
  items:
    pointer: "/data/videos"
    limit: 100

    fields:
      id:
        pointer: "/id"
        required: true

      title:
        pointer: "/title"
        required: true

      detailUrl:
        pointer: "/url"
        transforms:
          - op: "resolveUrl"
        required: true
```

Rules:

- The collection pointer is relative to the response root.
- Field pointers are relative to each collection item.
- Missing required values invalidate an item.
- Invalid JSON fails the operation.
- The engine MUST NOT silently interpret JSON as JavaScript.

### 10.2 Embedded JSON

Example:

```yaml
response:
  type: "html"

document:
  embeddedJson:
    selector: "script#catalog-data"
    read: "data"

extract:
  items:
    pointer: "/videos"
    limit: 100

    fields:
      id:
        pointer: "/id"
        required: true

      title:
        pointer: "/title"
        required: true
```

The engine MUST extract the script data as text and parse it as JSON.

It MUST NOT execute script contents.

Assignments such as window.DATA = {...} are unsupported in MVP unless a dedicated, bounded parser is later specified.

---

## 11. Transformation Engine

### 11.1 MVP transformations

Supported transformations:

- trim.
- collapseWhitespace.
- lowercase.
- uppercase.
- resolveUrl.
- canonicalUrl.
- parseInteger.
- parseDecimal.
- parseDuration.
- split.
- mapValues.
- deduplicate.

### 11.2 Transform constraints

- Transform chains MUST be bounded.
- Transform argument types MUST be validated.
- Unknown transformations MUST fail definition validation.
- Transform failures MUST produce structured errors.
- No transform may issue network requests.
- No transform may execute code.

### 11.3 Duration parsing

parseDuration MUST support:

- SS.
- MM:SS.
- HH:MM:SS.

Output MUST be a non-negative integer number of seconds.

Unrecognized values such as LIVE MUST NOT silently become zero.

Live status MUST be represented separately.

### 11.4 Regular expressions

General-purpose regex extraction SHOULD be omitted from MVP.

If later introduced:

- Use a bounded or non-backtracking implementation.
- Limit input size.
- Limit execution cost.
- Do not use regex as a substitute for HTML parsing.

---

## 12. Pagination

### 12.1 MVP mode: nextLink

Behavior:

1. Extract the next-page URL.
2. Resolve it.
3. Validate its destination.
4. Return it as an opaque cursor.
5. Fetch only when requested.

The engine MUST stop if:

- No next link exists.
- The next URL was already visited in the current pagination session.
- The user cancels.
- A configured page budget is reached.

### 12.2 Later modes

Optional future modes:

- Page-number query parameter.
- Offset/limit.
- JSON cursor token.

Each mode requires a defined schema and conformance tests before use.

### 12.3 Safety

- Do not crawl every page automatically.
- Do not download full catalogs on startup.
- Do not mix cursors between sources.
- Do not turn an empty parsed page into an infinite retry loop.

---

## 13. Playback Resolver

### 13.1 Supported extraction targets

MVP:

- HTML video/source elements.
- Authorized direct media links.
- JSON media URL fields.
- Embedded JSON media URL fields.

Optional later:

- Explicit intermediate-page traversal.

### 13.2 Resolver output

The resolver MUST return:

- Stable item identity.
- Source page URL.
- Candidate media URLs.
- MIME type hints.
- Optional quality labels.
- Optional subtitle references.
- Optional expiration information.
- Request policy reference.

### 13.3 Candidate validation

Each candidate MUST pass:

- Allowed scheme check.
- Allowed host check.
- URL length limit.
- Supported playback-type assessment.
- Source policy check.

MIME type hints are advisory.

A URL extension alone MUST NOT determine compatibility.

### 13.4 Native playback

Use Media3 ExoPlayer.

MVP playback targets:

- HLS.
- DASH.
- Progressive MP4.
- Supported subtitles.
- Audio-track selection where available.

Codec, HDR, and resolution compatibility MUST be tested on actual target devices.

### 13.5 Expired URLs

When playback fails because a signed URL expired:

1. Re-resolve the same stable item.
2. Validate the new candidate.
3. Retry once.
4. Preserve position when appropriate.
5. Stop with a clear error if unsuccessful.

Never substitute another video silently.

### 13.6 URL integrity

The app MUST NOT:

- Add affiliate parameters.
- Open unrelated destinations.
- Strip required signed query parameters.
- Change the content identity during retry.

Legitimate provider redirects and refreshed signed URLs are allowed after validation.

---

## 14. Network Security

### 14.1 Host policies

Separate permissions for:

- Catalog.
- Artwork.
- Media.
- Authentication.
- License services, if later supported.

Hosts MUST use exact matching in MVP.

Subdomain wildcards MUST NOT be implied.

### 14.2 Redirects

Every redirect destination MUST be validated.

A maximum redirect count MUST be enforced.

Credentials MUST NOT be forwarded to an unrelated origin.

### 14.3 Address safety

For ordinary internet sources:

- Reject loopback addresses.
- Reject link-local addresses.
- Reject private-network destinations.
- Validate resolved destinations at connection time.
- Prevent definitions from reaching device-local services.

User-owned LAN sources require a separate explicit permission mode.

### 14.4 Headers

Definitions MAY specify a restricted set of non-sensitive headers.

Definitions MUST NOT directly set:

- Host.
- Content-Length.
- Connection.
- Proxy-Authorization.
- Arbitrary Cookie values.
- Arbitrary Authorization secrets.

Sensitive credentials MUST use an application-managed authentication profile.

Header values MUST reject control characters.

### 14.5 HTTP methods

MVP MUST support GET only.

POST support, if added later, requires:

- Explicit capability declaration.
- A defined body schema.
- User approval.
- Separate security tests.

### 14.6 Rate limits

The engine MUST enforce the stricter of:

- Application-wide policy.
- Source definition limits.
- Server retry instructions.

Retry behavior MUST be bounded.

---

## 15. Authentication

### 15.1 MVP

Supported:

- No authentication.
- Application-managed credentials for explicitly implemented integration types.

A configuration file MUST NOT contain plaintext passwords or session cookies.

### 15.2 Future authentication

Potential future capabilities:

- Provider device authorization.
- OAuth.
- Explicitly scoped API tokens.

Each authentication method requires a real engine implementation.

A definition cannot create an unsupported authentication method by naming it.

---

## 16. Preferences and Filtering

### 16.1 Preference model

Each canonical tag can be:

- Preferred.
- Neutral.
- Excluded.

Preferences MAY also include:

- Language.
- Source.
- Duration range.
- Live/on-demand.
- Content rating.
- Accessibility features.

### 16.2 Evaluation order

1. Source authorization.
2. Profile restrictions.
3. Hard exclusions.
4. Required metadata policy.
5. Search/category constraints.
6. Ranking.

Excluded content MUST NOT return through ranking.

### 16.3 Unknown metadata

Offer:

Standard mode:
- Unknown optional tags can remain eligible.

Strict mode:
- Items lacking required classification are hidden.

Strict mode MUST explain that classification depends on source metadata and mappings.

### 16.4 Filtering before artwork

Items MUST be filtered before thumbnail requests.

If strict eligibility requires detail metadata:

- Fetch details under a bounded budget.
- Keep artwork and title cards hidden until classification succeeds.
- Exclude the item when required metadata remains unavailable.

### 16.5 Entry-point consistency

The same policy engine MUST govern:

- Home.
- Categories.
- Search.
- Favorites.
- History.
- Deep links.
- Autoplay.
- Restored navigation state.

---

## 17. Canonical Data Model

### 17.1 CatalogItem

- sourceId.
- sourceItemId.
- sourcePageUrl.
- title.
- description.
- durationSeconds.
- liveStatus.
- sourceTags.
- canonicalTags.
- languages.
- contentRating.
- thumbnailReferences.
- metadataCompleteness.
- metadataProvenance.
- fetchedAt.

### 17.2 Tag provenance

Each mapped tag SHOULD retain:

- Source field.
- Original value.
- Mapping version.
- Confidence category: explicit or derived.

Do not represent an inferred tag as publisher-confirmed.

### 17.3 Deduplication

MVP deduplication MUST use exact source identity.

Cross-source matching MAY be added later.

Similar titles alone are insufficient evidence that two videos are identical.

---

## 18. TV Interface

### 18.1 Screens

- Welcome.
- Profile selection.
- Preference setup.
- Home.
- Categories.
- Search.
- Video details.
- Player.
- Favorites.
- Sources.
- Definition import.
- Definition diagnostics.
- Settings.

### 18.2 Remote requirements

Every essential action MUST work using:

- Up.
- Down.
- Left.
- Right.
- Center/OK.
- Back.

Playback keys SHOULD work when provided by the remote.

### 18.3 Focus behavior

- Focus MUST always be visible.
- Dialogs MUST receive a safe initial focus.
- Back navigation MUST restore prior card focus.
- Dynamic results MUST NOT unexpectedly steal focus.
- Essential actions MUST NOT depend on long press.
- No feature may require hover.

### 18.4 Player controls

- Center reveals controls when hidden.
- Center activates a focused control when visible.
- Play/pause toggles playback.
- Timeline seeking works only for seekable streams.
- Back closes panels before leaving playback.
- Home retains normal Android behavior.

---

## 19. Privacy

### 19.1 Defaults

- No cloud account.
- No analytics.
- No autoplay previews.
- No casting.
- No launcher recommendations.
- History optional.
- Neutral launcher branding.

Neutral branding MUST NOT be described as making the application invisible.

### 19.2 Private sessions

Private sessions MUST avoid persisting:

- Search history.
- Watch history.
- Resume position.
- Media cache.
- Session-owned browsing state.

Platform-visible playback metadata MUST have an explicit privacy policy.

Private mode MUST NOT claim protection from network operators, source providers, device administrators, or a compromised OS.

### 19.3 Profile lock

An optional PIN MAY restrict casual access.

It MUST NOT be presented as strong protection against a device owner or administrator.

---

## 20. Advertising Policy

The app MUST distinguish:

- App-owned promotions.
- Tracking requests.
- Client-side provider advertising.
- Server-stitched advertising.
- Advertising embedded directly into video frames.

Supported behavior:

- Disable app-owned promotions.
- Avoid unnecessary tracking.
- Reject unexpected popup navigation.
- Respect provider-supported skip controls.
- Use authorized ad-free delivery when available.

The app MUST NOT promise universal removal of in-video advertising.

The initial source DSL MUST NOT include arbitrary manifest-rewriting or heuristic segment-deletion operations.

---

## 21. Definition Import and Updates

### 21.1 MVP import methods

- Local JSON file.
- Local YAML file.
- User-entered HTTPS definition URL.

Pasted text import MAY be offered but is not a remote-friendly primary path.

### 21.2 Approval screen

Before enabling a source, show:

- Source name and identifier.
- Definition author, if provided.
- Requested domains by purpose.
- Authentication capabilities.
- Rate limits.
- Permission declaration.
- Validation status.

A publisher-permission field is a declaration, not proof of permission.

### 21.3 Updates

Updates MUST:

- Validate before activation.
- Preserve the previous working revision.
- Show permission changes.
- Require renewed approval for expanded network or authentication permissions.
- Activate atomically.
- Support rollback.

Remote definitions MUST NOT update silently by default.

### 21.4 Trust

A valid signature proves who signed a definition, not that the source is safe or authorized.

Signed source catalogs MAY be added later.

---

## 22. Validation

### 22.1 Validation stages

1. File size check.
2. Safe syntax parsing.
3. Duplicate-key rejection.
4. JSON Schema validation.
5. Cross-field semantic validation.
6. Capability compatibility check.
7. Host and URL validation.
8. Fixture execution.
9. Optional user-approved live probe.

### 22.2 Required schema artifacts

Implementation MUST include:

- source-definition.schema.json.
- Kotlin models matching the schema.
- Semantic validator.
- Valid examples.
- Invalid examples.
- Migration tests.
- Documentation generated from the schema where practical.

### 22.3 Semantic checks

Reject:

- Unsupported schema versions.
- Missing playback operation when playback is advertised.
- Referenced but undeclared hosts.
- Cyclic reuse.
- Unknown transforms.
- Invalid selectors.
- Invalid JSON pointers.
- Undeclared variables.
- Incompatible output types.
- Excessive budgets.
- Credentials embedded in definitions.

### 22.4 Suggested execution budgets

Initial defaults:

- Definition size: 512 KiB.
- Catalog response: 2 MiB.
- Items per page: 100.
- Playback candidates: 20.
- Redirects: 3.
- Explicit resolver HTTP steps: 3.
- Transform chain length: 10.
- Overall operation deadline: 30 seconds.

Media streaming MUST use separate appropriate limits.

These are starting engineering limits, not universal performance guarantees.

---

## 23. Fixtures and Diagnostics

### 23.1 Fixture testing

Definitions SHOULD ship with sanitized offline fixtures.

Fixtures MUST NOT contain:

- Passwords.
- Session cookies.
- Authorization tokens.
- Private account information.
- Active signed playback links.

### 23.2 Fixture isolation

Fixture runs MUST:

- Disable real network access.
- Serve recorded responses deterministically.
- Fail on an unexpected request.
- Report missing selectors and fields.
- Check expected output counts.
- Compare stable normalized output.

### 23.3 Diagnostics screen

Show:

- Active definition version.
- Last successful request.
- Last successful parse.
- Items extracted.
- Items rejected.
- Missing required fields.
- Blocked destinations.
- Playback candidate count.
- Sanitized failure reason.

### 23.4 Error taxonomy

- DEFINITION_INVALID.
- UNSUPPORTED_SCHEMA.
- UNSUPPORTED_CAPABILITY.
- NETWORK_UNAVAILABLE.
- RATE_LIMITED.
- DESTINATION_BLOCKED.
- RESPONSE_TOO_LARGE.
- SELECTOR_INVALID.
- REQUIRED_FIELD_MISSING.
- SOURCE_LAYOUT_CHANGED.
- AUTHENTICATION_REQUIRED.
- PLAYBACK_NOT_FOUND.
- STREAM_EXPIRED.
- FORMAT_UNSUPPORTED.
- CODEC_UNSUPPORTED.
- POLICY_BLOCKED.
- METADATA_INSUFFICIENT.

---

## 24. Persistence

Store:

- Profiles.
- Preferences.
- Approved source definitions.
- Definition approval state.
- Source health.
- Catalog cache.
- Favorites.
- Optional history and resume state.

Do not treat these as permanent catalog data:

- Expiring playback URLs.
- Temporary cookies.
- Active authorization headers.

Cache entries MUST include:

- sourceId.
- definition version.
- normalized item identity.
- fetchedAt.
- expiration policy.

Definition changes MUST invalidate incompatible cache entries.

---

## 25. Acceptance Tests

### 25.1 Definition-only integration

Acceptance scenario:

1. Install the app.
2. Import a valid definition.
3. Approve its permissions.
4. Browse categories.
5. Open a category.
6. Load another page.
7. Open video details.
8. Play a supported stream.

No Kotlin code change, rebuild, or APK update may be required for this scenario.

### 25.2 Security

- Unknown hosts are blocked.
- Redirects cannot escape approved hosts.
- Private addresses are blocked unless explicitly approved for a LAN source.
- Credentials do not leak across origins.
- Malicious YAML is rejected.
- Excessively large responses are stopped.
- Cyclic definition references are rejected.
- Unsupported transformations are rejected.

### 25.3 Filtering

- Excluded items never appear.
- Excluded artwork is never requested.
- Favorites obey updated exclusions.
- Deep links obey exclusions.
- Autoplay obeys exclusions.
- Strict mode hides insufficient metadata.
- Switching profiles clears old visible results.

### 25.4 Playback

- HLS.
- DASH.
- Progressive MP4.
- Live stream.
- Subtitle selection.
- Alternate audio.
- Network interruption.
- URL expiration.
- Unsupported codec.
- Home and return.
- Sleep and wake.

### 25.5 Remote

All core tasks MUST pass using a basic D-pad remote without pointer input.

---

## 26. MVP Delivery Plan

### Milestone 1: Engine feasibility

- Safe JSON/YAML import.
- Schema validation.
- HTML listing extraction.
- JSON extraction.
- Offline fixtures.
- One fictional fixture source and one permitted live test source.

### Milestone 2: Catalog

- Categories.
- Listings.
- Next-link pagination.
- Search.
- Metadata normalization.
- Preference filtering.

### Milestone 3: Playback

- Direct playback resolution.
- HLS, DASH, MP4.
- Track selection.
- Error handling.
- URL-expiry recovery.

### Milestone 4: TV UX

- Full remote navigation.
- Focus restoration.
- Onboarding.
- Source management.
- Diagnostics.

### Milestone 5: Hardening

- Private sessions.
- Credential review.
- Network security tests.
- Physical-device compatibility matrix.
- Definition rollback.
- Release documentation.

---

## 27. Definition of Done

The application is complete for MVP when:

1. A compatible source can be added entirely through JSON or YAML.
2. Definitions are data, not executable plugins.
3. Categories, listings, pagination, details, and playback work through the shared engine.
4. Every request passes destination and permission checks.
5. Exclusions apply before artwork and across all entry points.
6. The app is fully usable by television remote.
7. Playback compatibility is documented and tested.
8. Source failures do not crash the application.
9. Diagnostics redact sensitive information.
10. Unsupported sources fail clearly instead of triggering insecure fallbacks.

---

## 28. Key Implementation Principle

Implement reusable primitives, not a collection of hidden website-specific exceptions.

When a new source does not fit the DSL:

1. Determine whether it needs a generally useful capability.
2. Specify that capability.
3. Extend the schema.
4. Implement it in the engine.
5. Add security and conformance tests.
6. Then update the source definition.

Do not smuggle executable code or arbitrary expressions into configuration files to preserve the appearance of configuration-only integration.

## 29. Reference Integration: magicoflolis/Magic-PH

The Android application MAY use `magicoflolis/Magic-PH` only as a **reference implementation for supported website settings and compatibility metadata**. It MUST NOT import or execute the repository’s JavaScript, userscript APIs, download logic, or browser extensions.

Repository: https://github.com/magicoflolis/Magic-PH
License: MIT
Primary language: JavaScript
Default branch: `master`
Reference commit: MUST be recorded and pinned before implementation.

### 29.1 Permitted Use

The implementation MAY inspect:

- Supported website hostnames.
- Desktop and mobile support metadata.
- Userscript configuration structure.
- Media-quality preference concepts.
- Published source behavior and compatibility limitations.
- Public filter and exclusion conventions.

The Android application MUST translate useful concepts into the declarative source-definition format specified by this document.

### 29.2 Required Settings Source

A settings adapter SHOULD derive the following Android settings data:

- Available source hosts.
- Exact catalog hostnames.
- Image hostnames.
- Media hostnames.
- Supported website capabilities.
- Desktop/mobile compatibility status.
- Media-quality preference options.
- Search and direct-video URL formats.
- Publicly documented source limitations.

The settings adapter MUST NOT copy executable userscript code or arbitrary browser APIs.

### 29.3 Website Mapping

Magic-PH’s supported website metadata may inform initial source profiles, including:

- Pornhub.
- Pornhub Premium.
- RedTube.
- Tube8.
- Thumbzilla.
- xHamster.
- XNXX.
- XVideos.
- YouPorn.
- 91Porn.
- 91Porna.
- HQPorner.
- SpankBang.
- PornTrex.
- AnalDin.
- sxyprn.
- Porn00.
- Eporner.
- YouJizz.
- Beeg.
- Jable.
- MissAV.

Each website MUST receive an explicit source definition before activation.

Hostnames MUST be mapped exactly into source, image, or media host groups. Wildcard permissions MUST NOT be inferred from userscript match patterns.

### 29.4 Declarative Settings Model

Reference settings MUST be represented in Android as data-only configuration:

```yaml
settingsReference:
  provider: "magicoflolis/Magic-PH"
  repository: "https://github.com/magicoflolis/Magic-PH"
  pinnedCommit: "<commit-sha>"
  license: "MIT"
  format: "normalized-source-capabilities"
The normalized model SHOULD include:

YAML
website:
  id: "<stable-source-id>"
  name: "<publisher-name>"
  catalogHosts:
    - "<exact-host>"
  imageHosts:
    - "<exact-host>"
  mediaHosts:
    - "<exact-host>"
  desktopSupported: true
  mobileSupported: true
  searchSupported: true
  directVideoSupported: true
  sourceStatus: "reference-only"
sourceStatus: reference-only MUST prevent automatic activation.

29.5 Quality Preferences
The application MAY expose a normalized quality preference:

Highest available.
Preferred resolution.
Preferred bitrate.
Offline/manual choice.
Automatic fallback.
The actual quality URLs and selection behavior MUST come only from a validated source definition and candidate media response.

The DSL MUST NOT import quality-selection JavaScript or proprietary URL derivation from Magic-PH.

29.6 Source Import Rules
Before a website profile from Magic-PH can become an active source:

Pin a reviewed upstream revision.
Record the reviewed license and provenance.
Verify the source is authorized for use.
Define exact catalog, image, and media hosts.
Define category, listing, search, details, and playback operations.
Add sanitized offline fixtures.
Validate against the source-definition schema.
Request user approval.
Run a permitted live probe before enabling it.
The existence of a userscript match entry is NOT evidence of authorization or current API stability.

29.7 Forbidden Translation
The Android implementation MUST NOT:

Copy arbitrary JavaScript.
Execute userscript code.
Inject browser globals.
Use Greasemonkey or Tampermonkey APIs.
Import bookmarklet payloads.
Derive download URLs through undocumented JavaScript.
Reimplement anti-bot or CAPTCHA circumvention.
Impose browser-only permissions.
Automatically download or execute upstream JavaScript updates.
Copy active signed URLs.
Interpret Magic-PH’s match patterns as unrestricted host permissions.
29.8 Update and Security Policy
Reference settings MUST NOT update silently.

Updates SHALL require:

A pinned upstream commit or release.
Diff review.
License verification.
Automated source normalization.
Schema validation.
Network permission review.
Renewed user approval for expanded hosts or authentication.
Fixture and live-source testing.
Atomic activation with rollback.
29.9 Diagnostics Metadata
Diagnostics MAY show:

Source reference repository.
Pinned revision.
Normalized source status.
Desktop/mobile reference capability.
Missing or unsupported metadata.
Host validation failures.
Diagnostics MUST redact credentials and MUST NOT expose private cookies or session data.

29.10 Repository Caveat
Magic-PH explicitly identifies itself as a video downloader and warns that use may violate website content policies. Accordingly:

It is unsuitable as a general downloader implementation.
It provides useful compatibility research only.
Active source support requires independent authorization and policy review.
The Android application MUST operate as a validated catalog/player integration, not as a universal adult-site downloader.
The final source definition remains the sole executable contract for network behavior.
