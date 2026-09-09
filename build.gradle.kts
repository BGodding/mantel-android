// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.ben.manes.versions)
    alias(libs.plugins.detekt) apply false
}

// `./gradlew dependencyUpdates` — report newer dependency versions, stable releases only.
tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask>().configureEach {
    rejectVersionIf {
        val v = candidate.version.lowercase()
        listOf("alpha", "beta", "-rc", "rc-", ".rc", "-cr", "-m", "preview", "dev", "snapshot", "eap")
            .any { it in v }
    }
}
