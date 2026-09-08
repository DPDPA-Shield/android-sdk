plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    // Kotlin 2.0+ moved Compose compilation out of the (now-removed) implicit
    // AGP/Kotlin integration into its own plugin - `composeOptions {
    // kotlinCompilerExtensionVersion }` alone is no longer sufficient and
    // fails configuration with "the Compose Compiler Gradle plugin is
    // required when compose is enabled" (confirmed against a real build).
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    `maven-publish`
}

// Coordinates exist ONLY so a host app can test-integrate this module before
// it is ever published anywhere real (see mobile/android-sdk/README.md,
// "Installing locally, before any Maven publish exists"). `0.1.0-SNAPSHOT`
// is a placeholder pre-release version, not a promise of API stability.
group = "com.dpdpashield.sdk"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "com.dpdpashield.sdk.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 24 // Android 7.0 - matches this SDK's use of EncryptedSharedPreferences
                     // (androidx.security.crypto) and java.time-free date handling; raise
                     // only with a documented reason, since Indian-market Android share
                     // skews toward older OS versions more than most markets.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    // Exposes the "debug" variant as a publishable software component - AGP
    // does not do this by default for a library module (unlike a plain `java`
    // or `kotlin("jvm")` module), so `components["debug"]` below would not
    // exist without this block.
    publishing {
        singleVariant("debug")
    }
    // No composeOptions.kotlinCompilerExtensionVersion here: the
    // org.jetbrains.kotlin.plugin.compose plugin (above) picks a compiler
    // version matched to the Kotlin Gradle plugin version automatically -
    // setting both is redundant now that Compose compilation is a first-class
    // Kotlin compiler plugin rather than an AGP-side option.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
    implementation("com.squareup.okhttp3:okhttp")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// AGP registers the "debug" component (from `android.publishing.singleVariant`
// above) lazily, after the project is evaluated - a publications block outside
// afterEvaluate would fail with "Could not find debug" the moment
// `:android:publishToMavenLocal` actually runs.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("debug") {
                from(components["debug"])
            }
        }
    }
}
