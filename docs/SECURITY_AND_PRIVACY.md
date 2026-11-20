# Security and Privacy Policy

Harbor treats every source definition as untrusted data until parsed, validated, and explicitly approved by the user.

---

## 1. Network Security

- **Purpose-Scoped Exact Host Matching**: `network.allowedHosts` separates `catalog`, `images`, `media`, and `authentication`. Subdomain wildcards (`*.example.org`) are forbidden and never inferred.
- **HTTPS Enforcement**: `httpsOnly: true` rejects plaintext HTTP requests and redirects.
- **Redirect Validation**: Every redirect hop (default `maxHops <= 3`) is re-validated against `httpsOnly`, exact host rules, and address safety. Credentials are never forwarded cross-origin.
- **Connection-Time Address Safety**: For ordinary internet sources (`lanAccess: false`), loopback, link-local, and private-network IP addresses are rejected at connection time to prevent SSRF against device-local or LAN services. User-owned LAN sources require explicit `lanAccess` opt-in.
- **GET-Only MVP & Header Controls**: MVP supports `GET` only. Definitions cannot set `Host`, `Content-Length`, `Connection`, `Proxy-Authorization`, `Cookie`, or `Authorization`, and control characters in header values are rejected.
- **Bounded Rate Limits**: The engine enforces the strictest limit among application-wide policy, `network.limits`, and server `Retry-After` responses.

---

## 2. Parsing & Extraction Safety

- **No JavaScript Execution**: Harbor never executes website JavaScript, userscripts, bookmarklets, or `window.DATA = ...` assignments.
- **Restricted YAML Model**: Custom tags, arbitrary object instantiation, executable expressions, unbounded alias expansion, duplicate keys, and non-string keys are rejected.
- **JSON-as-Data**: JSON and Level 3 `embeddedJson` `<script>` contents are parsed strictly as JSON and queried via JSON Pointer.
- **Bounded Transforms**: Transform chains are capped at 10 pure operations with no network access and no general-purpose regex in MVP.

---

## 3. Authentication & Credential Hygiene

- Definitions and offline fixtures MUST NOT contain plaintext passwords, session cookies, API secrets, or active signed playback URLs.
- Sensitive credentials must use application-managed authentication profiles (`authentication.profileId`). A definition cannot invent an unimplemented authentication method.
- Diagnostics and error logs redact credentials, cookies, and authorization headers.

---

## 4. Filtering Before Artwork & Entry-Point Consistency

- Exclusions, profile restrictions, and Strict-mode required-metadata policies run **before** thumbnail requests (`Coil`) or title card rendering.
- If Strict mode requires detail metadata, details are fetched under a bounded budget while artwork remains hidden until classification succeeds.
- The same preference and exclusion engine governs `Home`, `Categories`, `Search`, `Favorites`, `History`, `Deep links`, `Autoplay`, and restored navigation state.

---

## 5. Privacy Defaults

- **Local-Only by Default**: No cloud account, no analytics, no autoplay previews, no casting, no launcher recommendations, and optional local history.
- **Private Sessions**: Avoid persisting search history, watch history, resume position, media cache, or session-owned browsing state. Private mode does not claim immunity from network operators, source providers, device administrators, or a compromised OS.
- **Profile Lock**: Optional PIN restricts casual access only.
- **Advertising Policy**: App-owned promotions are disabled; arbitrary manifest-rewriting or heuristic video-segment deletion is excluded from the DSL.

---

## 6. `magicoflolis/Magic-PH` Isolation Policy

- **Pinned Commit**: `5764d037d9b52009218815152ef4e976c2c62255` (MIT License).
- **Permitted Use**: Non-executable hostnames, desktop/mobile capability flags, and compatibility metadata stored in [magic-ph-settings.yaml](../app/src/main/assets/reference/magic-ph-settings.yaml) with `sourceStatus: "reference-only"`.
- **Forbidden Use**: Copying/executing Magic-PH JS, `GM_*` userscript APIs, browser globals, adblock/downloader scripts, wildcard `@match` expansion, or auto-updating upstream JS.
- **Update Policy**: Updates require a pinned commit, diff review, license check, schema validation, permission review, renewed user approval on expanded hosts/auth, and atomic activation with rollback.
