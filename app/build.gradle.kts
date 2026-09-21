import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import java.util.Properties

// Deployment config: real values in the gitignored secrets.properties, with
// secrets.properties.example as the committed fallback so a fresh clone builds.
val realSecrets = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    val example = rootProject.file("secrets.properties.example")
    (if (realSecrets.exists()) realSecrets else example).inputStream().use { load(it) }
}

// A release built from the example fallback would ship pointing at nextcloud.example.com.
// CI opts in explicitly (-PallowExampleSecrets=true) because it only checks that R8 works.
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseRequested && !realSecrets.exists() && !providers.gradleProperty("allowExampleSecrets").isPresent) {
    throw GradleException("secrets.properties is missing: refusing to build a release against the example server.")
}

/** Escapes a value for embedding in a generated Java string literal. */
fun String.asJavaLiteral() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Release signing: gitignored, not committed, no fallback — a release build
// without it fails with a clear "signing config not set up" error rather than
// silently producing an unsigned APK. See docs/security.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    autoCorrect = false
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

// detekt 1.23.x runs in-process on the Gradle daemon's JVM and only supports
// JVM target/runtime up to 22. Pin the analysis target here; additionally the
// Gradle daemon must run on a JDK <= 21 for `./gradlew detekt` (JDK 17 recommended).
// The Android Studio JBR (JDK 25) is too new — CI and local runs should point
// org.gradle.java.home / JAVA_HOME at a JDK 17 for the detekt task.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach { jvmTarget = "11" }
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach { jvmTarget = "11" }

android {
    namespace = "com.eeinspired.mantel"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.eeinspired.mantel"
        minSdk = 33
        targetSdk = 37
        versionCode = 10
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", secrets.getProperty("MANTEL_BASE_URL").asJavaLiteral())
        buildConfigField(
            "String",
            "ALLOWED_HOST_SUFFIX",
            secrets.getProperty("MANTEL_ALLOWED_HOST_SUFFIX").asJavaLiteral(),
        )
    }

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
        debug {
            // Skip mapping-file upload for local builds — it just slows them down.
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Upload the deobfuscation mapping so release stack traces are readable.
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        // sarifReport/htmlReport removed: AGP 9.4 deprecated both booleans — lint
        // reports (SARIF + HTML) are now always generated regardless.
        // The six top-level screen composables are navigation entry points, never
        // composed with a caller-supplied Modifier — compose-lints documents this
        // as a valid exception to ComposeModifierMissing.
        disable += "ComposeModifierMissing"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.config)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json) // the android.jar org.json is a stub in unit tests
    testImplementation(libs.kxml2) // a real XmlPullParser for PROPFIND tests
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.okhttp.logging)

    detektPlugins(libs.detekt.formatting)
    lintChecks(libs.compose.lint.checks)

    constraints {
        // We don't use fragments directly — Firebase (play-services-basement) pulls
        // in 1.1.0/1.0.0 transitively, which Play Console flags as outdated.
        implementation(libs.androidx.fragment)
    }
}