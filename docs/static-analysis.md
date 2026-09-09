# Static analysis

Tooling wired into the Gradle build. Run these before pushing / in CI.

| Command | Tool | What it checks |
|---|---|---|
| `./gradlew lintDebug` | Android Lint (+ [compose-lints](https://slackhq.github.io/compose-lints/)) | Android/manifest/resource/API misuse, Compose anti-patterns. Fails the build on **errors**; warnings are reported only. HTML + SARIF at `app/build/reports/lint-results-debug.*`. |
| `./gradlew detekt` | [detekt](https://detekt.dev) 1.23.8 (+ `detekt-formatting` = ktlint rules) | Kotlin code smells, complexity, formatting. Config: `config/detekt/detekt.yml` (merged over detekt defaults). |
| `./gradlew dependencyUpdates` | [ben-manes versions](https://github.com/ben-manes/gradle-versions-plugin) | Reports newer **stable** dependency versions (pre-releases filtered). Report at `build/dependencyUpdates/report.txt`. |

## detekt needs a JDK ≤ 21

detekt 1.23.x runs in-process on the Gradle daemon's JVM and does not support JDK 25 (the
Android Studio JBR). Run its task with the daemon on **JDK 17**:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew detekt
```

or set `org.gradle.java.home` for the CI job that runs detekt. The rest of the build is fine on
the JBR. First run: `./gradlew detektBaseline` to snapshot the current findings into
`config/detekt/baseline.xml` so CI only flags *new* issues.

## Deliberate rule opt-outs

- **`ComposeModifierMissing`** (compose-lints) is disabled in `app/build.gradle.kts`. The six
  top-level screen composables are navigation entry points, never composed with a
  caller-supplied `Modifier`; compose-lints documents this as a valid exception.
- **`MagicNumber`** (detekt) is off — the codebase has many legitimate protocol/crypto/UI
  constants that would drown the report.

## Not yet wired (candidates)

- **OWASP Dependency-Check** or Dependabot security alerts — CVE scanning for a
  credential-handling networked app.
- **gitleaks** / GitHub secret scanning — the app handles Nextcloud app passwords and
  `app/google-services.json` is gitignored; enforce it doesn't get committed.
- **R8** for release builds (currently `optimization.enable = false`) — shrinking + extra
  analysis; the Crashlytics mapping upload is already configured for when it's enabled.
