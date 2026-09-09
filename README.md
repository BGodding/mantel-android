# Mantel for Android

The Android client for **Mantel**, a private digital-photo-frame system built on
[Nextcloud](https://nextcloud.com/). You pick photos/videos (the system share
sheet or the Photo Picker), choose a destination folder, and the app uploads them
to that Nextcloud share over WebDAV — where a frame, screensaver, or anyone with
the share can pick them up. An optional, feature-flagged gallery shows what's
already in a folder.

- Kotlin + Jetpack Compose, single activity, `minSdk 33`
- `applicationId` `com.eeinspired.mantel`
- No local database. Destinations are discovered live from the server's share
  graph — no static client config.

## Configure

Two things aren't in the repo:

1. **`secrets.properties`** — copy `secrets.properties.example` and set your
   Nextcloud origin and the host allowlist suffix:
   ```bash
   cp secrets.properties.example secrets.properties
   ```
   ```properties
   MANTEL_BASE_URL=https://nextcloud.yourdomain.tld
   MANTEL_ALLOWED_HOST_SUFFIX=yourdomain.tld
   ```
   (The build falls back to `secrets.properties.example` if this is absent, so a
   fresh clone still compiles.)

2. **`app/google-services.json`** — from your own Firebase project. Crashlytics +
   Analytics collect only in release builds; Remote Config carries three keys:
   `gallery_enabled` (bool), `delete_enabled` (bool), `server_base_url` (string).

## Build

```bash
./gradlew assembleDebug        # JAVA_HOME: a JDK 17–21 (the Android Studio JBR 25 works for everything except detekt)
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew dependencyUpdates
```

## Docs

- [`docs/security.md`](docs/security.md) — threat model, hardening, remaining owner actions
- [`docs/telemetry.md`](docs/telemetry.md) — every event / key / breadcrumb
- [`docs/static-analysis.md`](docs/static-analysis.md) — lint, detekt, version checks
