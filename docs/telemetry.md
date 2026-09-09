# Telemetry reference

Firebase Analytics + Crashlytics. **Collection is off in debuggable builds** (`Telemetry.init`
checks `FLAG_DEBUGGABLE`); only release installs report. Every value below is a coarse
enum, count, boolean, or duration — **never** a username, filename, frame name, share path,
or response body (Requirements §8 / §13.3).

## Analytics events

| Event | Params | Fired from |
|---|---|---|
| `login_success` | — | `SessionRepository.logIn` |
| `login_failure` | `reason` (`invalid_credentials` \| `unreachable` \| `server` \| `malformed`), `code` | `SessionRepository.logIn` |
| `session_revoked` | `at` (`launch` \| `refresh`) | `SessionRepository` |
| `destinations_refresh` | `outcome` (`success` \| `session_expired` \| `unreachable` \| `server_error` \| `malformed`), `count`, `code` | `SessionRepository.refreshDestinations` |
| `gallery_open` | `count` | `SessionRepository.listFolder` |
| `gallery_item_deleted` | `outcome` (`Success` \| `Forbidden` \| `AlreadyGone` \| `SessionExpired` \| `Unreachable` \| `ServerProblem`) | `SessionRepository.deleteItem` |
| `upload_enqueued` | `count`, `stage_failed` | `UploadEnqueuer.enqueue` |
| `upload_result` | `outcome` (`success` \| `auth` \| `forbidden` \| `dest_missing` \| `quota` \| `server_<code>` \| `network`), `chunked` (bool), `size_mb`, `attempt`, `duration_ms`, `kb_per_sec` (success only) | `UploadWorker.report` — once per file |

## Crashlytics custom keys (attached to every crash report)

| Key | Values | Set by |
|---|---|---|
| `screen` | `loading` \| `login` \| `destinations` \| `pick_destination` \| `upload_status` \| `gallery` \| `viewer` | `MainActivity` on navigation |
| `flag_gallery` / `flag_delete` | bool | `MainActivity` after `RemoteFlags.load()` |
| `destination_count` | int | `SessionRepository.refreshDestinations` on success |

## Crashlytics breadcrumbs

- `nav → <screen>` on every screen change.

## Non-fatal reports (`recordException`)

Surfaced so they're actionable without a full crash:

- **`API drift at <where>: <detail>`** — a parseable-but-unexpected server response
  (`bootstrap:/cloud/user`, `login:/cloud/user`, `discovery:/shares`, `gallery:PROPFIND`).
  This is the canary for the server's API changing under the client. `<detail>` is a parser
  exception message, not a response body.
- **`orphaned upload staging: cleanup returned <code>`** — a chunked upload failed and the
  best-effort `DELETE` of its staging collection also failed, so a `/uploads/<user>/<uuid>/`
  collection may be orphaned server-side (API Contract §4).

## Adding more

Keep to the §8 rule: coarse enums / counts / booleans / durations, release-only, nothing
that identifies a user, file, or folder. Route new events through `Telemetry.event`,
context through `Telemetry.setKey`, and swallowed errors through `Telemetry.recordNonFatal`.
