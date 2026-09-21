# Security

How the client protects credentials, photos and the connection to the server.

## Threat model

Private family tool, distributed to a handful of known devices. Each install holds one
long-lived bearer credential — a **Nextcloud app password** — sent via HTTP Basic auth over
TLS. The credential can be revoked server-side at any time. Not in scope: nation-state
adversaries, a hostile Nextcloud server, physical forensic extraction from a powered-off
device.

## Credentials

- The app password is encrypted with an **AES-256-GCM key in the Android Keystore**
  (StrongBox-backed when the device has it, TEE otherwise); only IV + ciphertext are stored.
- The key is not gated on the device being unlocked, because background uploads must decrypt
  while the screen is locked. The blob stays protected by the app sandbox, file-based encryption
  and the device-bound key.
- A transient Keystore error never destroys the stored credential; only an unreadable or
  tampered blob does. `Credentials.toString()` masks the password.
- A 401 from a data call signs the user out only if a follow-up session check also fails, so a
  proxy or rate limiter can't wipe the credential.
- Sign-out cancels queued uploads and deletes staged copies and cached thumbnails/photos.
- Backup and device-to-device transfer are off for the credential and cache files
  (`allowBackup="false"`, plus `data_extraction_rules.xml` / `backup_rules.xml`).
- `FLAG_SECURE` is set on the login screen: no screenshots, blank in the recents switcher.

## Network

- No cleartext anywhere; **system trust anchors only**, so user-installed CAs (a MITM proxy)
  are ignored. User CAs are allowed only in debug builds.
- All clients share one OkHttp base restricted to TLS 1.2/1.3 with strong ciphers, with
  https→http redirects disabled. Uploads use a stall timeout rather than a whole-transfer
  deadline, so a dead connection fails instead of hanging.
- The server origin is a build-time value. It can be migrated through Remote Config, but only
  to an HTTPS, origin-only URL whose host matches the compiled-in allowlist, so a compromised
  config can't send the app password to another server. Changes apply on the next launch.
- Hrefs supplied by the server are reduced to a path and resolved against the configured
  origin, so a hostile response can't send the `Authorization` header to another host.
- Certificate pinning is deliberately not used: certificates rotate every ~90 days and a
  botched pin rotation would lock every device out — a worse outage than the attack it
  prevents, given the platform already validates the chain and rejects user CAs.

## Untrusted input

- **Shared files.** Incoming share URIs must be `content://`, must not belong to this app, and
  must resolve to an image or video; at most 50 per share and 4 GiB per file. This closes the
  "malicious app shares a `file://` path to our private data" route.
- **File names.** Names from other apps are stripped of path separators, leading dots, control
  and server-reserved characters and in-flight suffixes, and capped by bytes with the
  extension preserved, before becoming a WebDAV path segment.
- **XML.** The PROPFIND parser disables DTD processing and validation (no XXE).
- **Staging.** Picked files are copied into app-private cache and deleted on every terminal
  upload outcome; stale batches with no queued work are swept on start.

## Upload safety

- An upload never silently replaces an existing file: requests carry `If-None-Match: *` /
  `Overwrite: F`, and name clashes are numbered (`IMG (1).jpg`).
- Retries are safe and resumable: chunked staging is keyed to the work request, so a retry
  continues where it stopped, and an already-uploaded file is recognised rather than duplicated.
- Deleting from a frame is double-gated (a Remote Config flag **and** the share's Delete
  permission) behind a confirmation dialog.

## Data minimisation and telemetry

- Minimal permissions: no storage, media, camera, contacts or location; photos come through the
  system Photo Picker or the share sheet. No WebView, no dynamic code loading, no advertising
  ID permissions.
- Crash and analytics collection is off by default and enabled only in non-debuggable builds.
  Reports contain coarse events and response *shape* (status, content type, size, field
  names) — never credentials, file names, paths or response content. Details in
  [`telemetry.md`](telemetry.md); the user-facing summary is the privacy policy.
- Release builds contain no logging; debug builds add a header-level HTTP logger with the
  `Authorization` header redacted.

## Build and release integrity

- Release builds are signed, shrunk and obfuscated with R8, and refuse to build against the
  placeholder server configuration.
- The Gradle wrapper is checksum-pinned and validated in CI; CI actions are pinned to commit
  SHAs; Dependabot tracks dependency and action updates; CI builds the shrunk release on every
  change alongside lint, detekt and unit tests. See [`static-analysis.md`](static-analysis.md).
- Signing keys, deployment config (`secrets.properties`) and Firebase config
  (`google-services.json`) are gitignored and never committed.

## Reporting a vulnerability

Email <mantel@eeinspired.com>. Please don't open a public issue for security problems.
