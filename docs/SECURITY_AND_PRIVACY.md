# Security and Privacy Policy

Harbor treats every source definition as untrusted data until parsed, validated, and explicitly approved by the user.

---

## 1. Network Security

- **Purpose-Scoped Host Matching**: `network.allowedHosts` separates `catalog`, `images`, `media`, and `authentication`. Bare hostnames enforce exact matching only; subdomain matching is permitted only when explicitly declared as `*.domain.tld` in `allowedHosts`.
- **HTTPS Enforcement**: `httpsOnly: true` rejects plaintext HTTP requests and redirects.
- **Redirect Validation**: Every redirect hop (default `maxHops <= 3`) is re-validated against `httpsOnly`, host rules, and address safety. Sensitive headers (`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`, `X-Session-Token`) are stripped cross-origin.
- **Connection-Time Address Safety**: `ControlledHttpTransport` and `RequestPolicyEngine` execute on `Dispatchers.IO` and reject loopback, link-local, and private-network IP addresses at DNS resolution and socket connection time unless `lanAccess` / `allowLan` is explicitly enabled.
- **GET-Only MVP & Header Controls**: MVP supports `GET` only. Definitions cannot set `Host`, `Content-Length`, `Connection`, `Proxy-Authorization`, `Cookie`, or `Authorization`, and control characters are rejected.
- **Ephemeral Session Cookies**: `ControlledHttpTransport` uses an in-memory `CookieJar` bounded to 100 hosts (never persisted to disk) with `clearEphemeralState()` for Private Sessions.

---

## 2. Parsing & Extraction Safety

- **No JavaScript Execution**: Harbor never executes website JavaScript, userscripts, bookmarklets, or arbitrary script engines.
- **Restricted YAML Model**: Custom tags, arbitrary object instantiation, executable expressions, unbounded alias expansion (`> 25`), duplicate keys, and non-string keys are rejected.
- **JSON-as-Data**: JSON and Level 3 `embeddedJson` `<script>` contents are parsed strictly as JSON and queried via RFC 6901 JSON Pointer.
- **Bounded Transforms**: Transform chains are capped at 10 pure operations; `regexExtract` and `regexExtractAll` operate on bounded input (`512 KiB`, max 20 matches) with no network access or code execution. Page `<base>` elements are stripped/ignored during HTML extraction.

---

## 3. Authentication & Credential Hygiene

- Definitions and offline fixtures MUST NOT contain plaintext passwords, session cookies, API secrets, or active signed playback URLs.
- Diagnostics and error logs redact credentials, cookies, and authorization headers via `CredentialRedactor`.
- **Non-Secret Local Backup**: `HarborBackupManager` exports/imports only non-secret profile state (`PreferenceConfig`, `UserTasteProfile`, `favorites`, `watchLater`), never PIN hashes, cookies, or credentials.

---

## 4. Filtering Before Artwork & Entry-Point Consistency

- Hidden videos (`hiddenItemIds`), minimum resolution (`minResolutionHeight`), minimum duration (`minDurationSeconds`), tag exclusions (`Block Tag`), and Strict-mode required-metadata policies run **before** thumbnail requests (`Coil`) or title card rendering, stripping `thumbnailReferences` on every rejected item.
- `PlaybackResolver` re-verifies eligibility and `minResolutionHeight` prior to playback resolution and preserves `(sourceId, sourceItemId)` identity on 1-time expired-URL retries.

---

## 5. Privacy Defaults

- **Local-Only by Default**: No cloud account, no analytics, no autoplay previews, no casting, no launcher recommendations, and all taste/affinity learning (`UserTasteEngine`) runs 100% on-device.
- **Private Sessions**: Bypass disk media cache, cancel active preloads, skip history/resume/taste persistence, and clear ephemeral in-memory session cookies and HTTP cache (`clearEphemeralState(evictHttpCache = true)`).
- **Profile Lock**: Optional SHA-256 domain-salted PIN (`PinHasher`) restricts casual access only.

---

## 6. `magicoflolis/Magic-PH` Isolation Policy

- **Pinned Commit**: `5764d037d9b52009218815152ef4e976c2c62255` (MIT License).
- **Permitted Use**: Non-executable hostnames, desktop/mobile capability flags, and compatibility metadata stored in [magic-ph-settings.yaml](../app/src/main/assets/reference/magic-ph-settings.yaml) with `sourceStatus: "reference-only"`.
- **Forbidden Use**: Copying/executing Magic-PH JS, `GM_*` userscript APIs, browser globals, adblock/downloader scripts, wildcard `@match` expansion, or auto-updating upstream JS.
