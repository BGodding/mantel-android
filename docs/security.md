# Security

What the client does, what was hardened in the September 2026 review, and what still
needs an owner action before a real rollout.

## Threat model

Private family tool, sideloaded / TestFlight-style distribution to a handful of known
devices. Each install holds one long-lived bearer credential — a **Nextcloud app
password** — sent via HTTP Basic auth over TLS. The credential can be revoked
server-side. Not in scope: nation-state adversaries, a hostile Nextcloud
server, physical forensic extraction from a powered-off device.

## Implemented (review of Sept 2026)

| # | Area | What |
|---|---|---|
| 1 | Untrusted share URIs | `MainActivity.isAcceptableSharedUri` rejects any incoming `EXTRA_STREAM` URI that isn't `content://`, is hosted by this app, or doesn't resolve to `image/*` / `video/*`; count capped at 50. `MediaStaging.stage` re-checks the scheme and enforces a 4 GiB per-file cap. Closes the "malicious app sends `file:///data/data/.../shared_prefs/...` and we upload it" path. |
| 4 | Network trust | `res/xml/network_security_config.xml` applies to all traffic: no cleartext anywhere, **system trust anchors only** (user-installed CAs ignored → defeats a MITM proxy CA on someone's device). User CAs are allowed only in `debug-overrides`. There's no host-specific block — the app talks to one origin, which is a build value (`secrets.properties` → `BuildConfig.BASE_URL`). **Base URL** is also overridable via the Remote Config value `server_base_url` so the host can be migrated without an app update, but the client only accepts a value that is HTTPS, origin-only, and whose host matches the compiled-in `MANTEL_ALLOWED_HOST_SUFFIX` allowlist (`Config.isAllowed`) — a compromised Remote Config can't point the app (and the app password) at an attacker's server. Applied next launch; a rejected value is a Crashlytics non-fatal. |
| 5 | Key storage | `CredentialStore` key is now **StrongBox-backed when available** (TEE fallback), `setRandomizedEncryptionRequired(true)`. `setUnlockedDeviceRequired(true)` is deliberately **not** set — background upload workers must decrypt while the screen is locked; see the comment in `loadOrCreateKey`. |
| 6 | Remote filename | `MediaStaging.safeRemoteName` strips path separators, leading dots (`.`/`..`), and control chars, length-caps at 200, and falls back to a generated name — before it becomes the WebDAV path segment. |
| 7 | Staging cache lifetime | Sweep window cut 24h → **6h**; swept on app start (`MainActivity.onCreate`) as well as on enqueue; each staged file is deleted on every terminal upload outcome (success *or* failure). |
| 8 | Backup / transfer | `data_extraction_rules.xml` + `backup_rules.xml` exclude `mantel_secure.xml` and `mantel_cache.xml` from cloud backup **and** device-to-device transfer. (Cloud backup is already off via `allowBackup="false"`; the Keystore key is device-bound so a copied blob is inert anyway.) |
| 9 | Screen capture | `SecureScreen()` sets `FLAG_SECURE` on the login screen — no screenshots / screen recording, blank in the recents switcher. |
| 10 | XML parsing | PROPFIND parser explicitly sets `FEATURE_PROCESS_DOCDECL = false` and `FEATURE_VALIDATION = false` (KXmlParser defaults, made explicit — no XXE). |
| 11 | TLS floor | Both OkHttp clients (`NextcloudClient`, `FrameImageLoader`) restricted to `RESTRICTED_TLS` + `MODERN_TLS` (TLS 1.2/1.3, strong ciphers). Coil client also gets connect/read/call timeouts. |

Already in place before the review: `allowBackup="false"`, `usesCleartextTraffic="false"`,
minimal permissions (no storage/media — Photo Picker only), no WebView, no dynamic code
loading, no `android.util.Log` calls, no OkHttp logging interceptor, AES-256-GCM Keystore
credential encryption, delete double-gated (Remote Config flag **and** the share's Delete
bit) behind a mandatory confirm dialog.

## Certificate pinning — decision

**Not configured, by choice.** The cert is Let's Encrypt (~90-day rotation) and devices
update slowly; a botched pin rotation bricks every family device — a worse outage than the
MITM it prevents, given the platform already rejects user CAs (finding 4) and validates the
chain. If the threat model hardens, add a `<pin-set>` to `network_security_config.xml`
pinning the **intermediate or a self-managed backup key SPKI** (never the leaf), always with
≥2 pins and an `expiration` safety valve:

```xml
<domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="true">nextcloud.example.com</domain>
    <trust-anchors><certificates src="system" /></trust-anchors>
    <pin-set expiration="2027-01-01">
        <!-- SPKI SHA-256 of the CA intermediate currently signing the leaf -->
        <pin digest="SHA-256">BASE64_INTERMEDIATE_SPKI==</pin>
        <!-- Backup: a key you control and can swap to without an app update -->
        <pin digest="SHA-256">BASE64_BACKUP_SPKI==</pin>
    </pin-set>
</domain-config>
```

Get the pin with (against your host, and against the intermediate, not the leaf):
`openssl s_client -connect "$HOST":443 -showcerts </dev/null | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`
A lower-maintenance alternative is enforcing Certificate Transparency instead of pins.

---

## Owner actions still needed

### 2. Release signing + code shrinking — ✅ done (Sept 2026)

`app/build.gradle.kts` has `signingConfigs.release` reading a gitignored `keystore.properties`,
and `buildTypes.release` has R8 (`optimization { enable = true }`) and `isShrinkResources = true`
on, with `proguard-rules.pro`. Verified: `assembleRelease` produces a real signed APK —
`apksigner verify` confirms one signer, RSA 4096-bit, APK Signature Scheme v2 — and
`app/build/outputs/mapping/release/` has a full R8 mapping. **Not yet done:** the
end-to-end smoke test of the shrunk build (login/discovery/upload/gallery) called out below,
and confirming a symbolicated crash shows up in the Crashlytics console.

Steps below are kept for reference / rebuilding on a new machine.

**Create a signing key** (keep it and its passwords in a password manager / secrets store —
losing it means you can never update the app):

```bash
keytool -genkeypair -v -keystore mantel-release.jks \
  -alias mantel -keyalg RSA -keysize 4096 -validity 10000
```

Put the file **outside the repo** (or anywhere — `*.jks` / `*.keystore` / `keystore.properties`
are gitignored). Create `keystore.properties` (also gitignored):

```properties
storeFile=/absolute/path/mantel-release.jks
storePassword=…
keyAlias=mantel
keyPassword=…
```

Wire it in `app/build.gradle.kts`:

```kotlin
import java.util.Properties

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization { enable = true }          // turn R8 on (currently false)
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

- OkHttp, Coil, Firebase, kotlinx all ship consumer R8 rules; the app uses `org.json`
  (no reflective serialization), so few if any custom `-keep` rules should be needed.
  **Smoke-test the release build end to end** (login, discovery, upload, gallery) — R8 bugs
  surface at runtime, not build time.
- Crashlytics mapping upload for release is already configured
  (`mappingFileUploadEnabled = true`); confirm a symbolicated crash appears in the console.
- Consider Play App Signing (if a private Play track) so Google holds the upload-key backup.

### 3. Firebase lockdown

**Trim analytics collection — ✅ done (Sept 2026).** `AndroidManifest.xml` now explicitly
`tools:node="remove"`s `com.google.android.gms.permission.AD_ID`,
`android.permission.ACCESS_ADSERVICES_ATTRIBUTION`, and
`android.permission.ACCESS_ADSERVICES_AD_ID` — all three are merged in by
`play-services-measurement` (Firebase Analytics) by default for optional ad-personalization
features Mantel doesn't use (no ads, no remarketing). Verified absent from
`processReleaseMainManifest`'s merged manifest. The two `google_analytics_*` meta-data flags
below are also in place. **Not yet done:** the Firebase console retention/Google-signals
settings called out below, and the API key restriction.

**Restrict the API key.** Google Cloud console → *APIs & Services → Credentials* → the
"Android key (auto created by Firebase)":
- *Application restrictions* → **Android apps** → add package `com.eeinspired.mantel`
  with the **release** signing SHA-256 (and the debug SHA-256 while developing).
- *API restrictions* → restrict to only: **Firebase Installations API**, **Firebase Remote
  Config API**, **Token Service API**. Remove everything else.
  (As of Sept 2026 the console's API-restriction dropdown only offers the APIs enabled on
  the project, and neither "Firebase Crashlytics API" nor "Google Analytics for Firebase API"
  appear in it — Crashlytics report upload and Analytics event collection don't go through a
  googleapis.com endpoint gated by this Android key, so they aren't affected by the
  restriction either way. Installations backs all three SDKs (Crashlytics, Analytics, Remote
  Config) for device identity; Remote Config is the explicit `fetch()` call in
  `RemoteFlags.kt`; Token Service is the STS token exchange Installations relies on. The app
  uses no other Firebase-gated API — no FCM (no `FirebaseMessaging`), no Firebase Auth (no
  `Identity Toolkit API`), no Firestore/Storage/Hosting/ML/App Check — so nothing else from
  the project's enabled-API list belongs on the key.)

**Trim analytics collection.** Add to `AndroidManifest.xml` `<application>`:

```xml
<meta-data android:name="google_analytics_default_allow_ad_personalization_signals" android:value="false" />
<meta-data android:name="google_analytics_adid_collection_enabled" android:value="false" />
```

Firebase console → *Analytics → Data settings*: set retention to the minimum (2 months),
disable Google signals, disable granular location/device data collection.

**Crashlytics** collection is already gated to non-debuggable builds in `Telemetry.init`,
and no custom keys/logs are attached. Nothing else required; revisit only if you start
adding `setCustomKey`.

`google-services.json` stays gitignored — each build environment (and CI, as an encrypted
secret file) supplies its own.

### 12. Supply-chain hardening

- **Gradle dependency verification.** Generate once and commit:
  `./gradlew --write-verification-metadata sha256 help` → `gradle/verification-metadata.xml`.
  Gradle then checksum-verifies every dependency. Re-run the same command after any version
  bump to append new entries. (Adds friction on upgrades — accept it or scope it to CI.)
- **Wrapper integrity.** Pin `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`
  and add the `gradle/wrapper-validation-action` GitHub Action.
- **Dependabot** (zero-config CVE + version PRs) — `.github/dependabot.yml`:
  ```yaml
  version: 2
  updates:
    - package-ecosystem: "gradle"
      directory: "/"
      schedule: { interval: "weekly" }
      open-pull-requests-limit: 5
  ```
  This also delivers GitHub security alerts for known-vulnerable dependencies, which
  covers the intent of an OWASP Dependency-Check run without the NVD-API-key setup.
- **Secret scanning.** Enable GitHub secret scanning + push protection on the repo (or a
  `gitleaks` pre-commit hook). Main risk is an accidental commit of the keystore /
  `keystore.properties` / `google-services.json` — all three are gitignored; scanning is
  the backstop.
- See also `docs/static-analysis.md` for lint / detekt / `dependencyUpdates`.
