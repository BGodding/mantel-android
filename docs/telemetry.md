# Telemetry reference

Firebase Analytics + Crashlytics. **Collection is off by default** (manifest meta-data) and
`Telemetry.init` — called from `MantelApp.onCreate`, so it also runs in WorkManager-only
processes — enables it for non-debuggable builds only. Every value below is a coarse enum,
count, boolean, duration, or response *shape* — **never** a username, filename, frame name,
share path, or response content (Requirements §8 / §13.3).

## Analytics events

| Event | Params | Fired from |
|---|---|---|
| `login_success` | — | `SessionRepository.logIn` |
| `login_failure` | `reason` (`invalid_credentials` \| `unreachable` \| `server` \| `malformed`), `code` | `SessionRepository.logIn` |
| `session_revoked` | `at` (`launch` \| `refresh`) | `SessionRepository` |
| `destinations_refresh` | `outcome` (`success` \| `session_expired` \| `unreachable` \| `server_error` \| `malformed`), `count`, `code` | `SessionRepository.refreshDestinations` |
| `gallery_open` | `count` | `SessionRepository.listFolder` |
| `gallery_item_deleted` | `outcome` (`success` \| `forbidden` \| `already_gone` \| `session_expired` \| `unreachable` \| `server_error`) — explicit labels, since R8 obfuscates class names | `SessionRepository.deleteItem` |
| `upload_enqueued` | `count`, `stage_failed` | `UploadEnqueuer.enqueue` |
| `upload_stage_failed` | `reason` (`not_content_uri` \| `too_large` \| `no_space` \| `unreadable` \| `permission`), `count` | `UploadEnqueuer.enqueue` |
| `upload_result` | `outcome` (`success` \| `auth` \| `forbidden` \| `conflict` \| `dest_missing` \| `quota` \| `rejected_<code>` \| `server_<code>` \| `network`), `chunked` (bool), `size_mb`, `attempt`, `duration_ms`, `kb_per_sec` (success only) | `UploadWorker.report` — once per file |

## Crashlytics custom keys (attached to every crash report)

| Key | Values | Set by |
|---|---|---|
| `screen` | `loading` \| `login` \| `destinations` \| `pick_destination` \| `upload_status` \| `gallery` \| `viewer` | `MainActivity` on navigation |
| `flag_gallery` / `flag_delete` | bool | `MainActivity` after `RemoteFlags.load()` |
| `destination_count` | int | `SessionRepository.refreshDestinations` on success |
| `server_host` | host of the configured server | `Config.initialize` |
| `drift_where` / `drift_detail` | endpoint label / response shape (below) | `Telemetry.recordApiDrift` |
| `upload_last_outcome` | outcome label | `UploadWorker.report`, on an unexpected failure |
| `credential_failure` | `permanent:<ExceptionClass>` \| `transient:<ExceptionClass>` | `CredentialStore` |

## Crashlytics breadcrumbs

- `nav → <screen>` on every screen change.
- `METHOD route -> status (ms)` for each API/WebDAV request (`HttpClients.BreadcrumbInterceptor`).
  `route` is one of `ocs/user`, `ocs/shares`, `dav/files`, `dav/staging`, `dav/assemble` —
  never a path, user or file name. Thumbnail fetches are skipped.

## Non-fatal reports (`recordException`)

Surfaced so they're actionable without a full crash:

- **`ApiDriftException: API drift at <where>: <detail>`** — a parseable-but-unexpected server
  response (`bootstrap:/cloud/user`, `login:/cloud/user`, `discovery:/shares`,
  `gallery:PROPFIND`). This is the canary for the server's API changing under the client.
  `<detail>` comes from `ApiDiagnostics` and is **shape only**: `status=200 ct=text/html len=1234
  error=JSONException shape=html`, or `shape=json{ocs{meta{...},data[3]{id,item_type,...}}}`, or
  `(No value for ocs)` for a renamed key. That is enough to tell an HTML captive-portal page from
  a renamed field from an empty body — without a single value. Parser exception *messages* are
  never forwarded: `org.json` and the XML parser quote the whole document in them.
  A `MalformedResponse` unit test asserts that no value from the body can leak.
- **`UploadFailedException: upload failed: outcome=… chunked=… size_mb=… attempt=…`** — an
  upload ended in an unexpected way (a non-retryable 4xx, or a server/network failure that
  outlasted every retry). Grouped by exception type; the outcome is in the message and the
  `upload_last_outcome` key.
- **`CredentialStoreException: credentials unreadable, cleared: <Class>` / `credential read
  failed, kept: <Class>`** — Keystore trouble, with the exception class only.
- **`orphaned upload staging: cleanup returned <code>`** — a chunked upload failed and the
  best-effort `DELETE` of its staging collection also failed, so a `/uploads/<user>/<uuid>/`
  collection may be orphaned server-side (API Contract §4).

## Adding more

Keep to the §8 rule: coarse enums / counts / booleans / durations, release-only, nothing
that identifies a user, file, or folder. Route new events through `Telemetry.event`,
context through `Telemetry.setKey`, and swallowed errors through `Telemetry.recordNonFatal`.
