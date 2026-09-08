// Thin native-module wrapper over `:android` (which itself wraps `:core`) for
// a bare React Native app - no Expo config plugin, no New Architecture
// codegen, no `com.facebook.react` Gradle plugin applied here. This is
// deliberately the OLD-BRIDGE style (ReactContextBaseJavaModule + ReactPackage)
// rather than a TurboModule: a TurboModule needs codegen from a JS spec file
// running inside a real RN app's build (react-native-codegen, driven by the
// RN Gradle plugin's `autolinking` + `codegen` tasks) - there is no host RN
// app anywhere in this repo to drive that from, so old-bridge is the only
// style verifiable by a bare `:reactnative` Gradle module on its own. Every
// class here (ReactContextBaseJavaModule, Promise, ReadableMap, ReactPackage)
// is still fully supported under the New Architecture in bridge/interop mode -
// this is not a compatibility shim, it is a real, currently-idiomatic RN
// native module, just not the newest possible flavour of one.
plugins {
    id("com.android.library") version "8.5.2"
    // Kotlin 2.2.0 here, NOT the 2.0.20 :core/:android use - found by actually
    // running the build, not assumed. react-android 0.87.1's own .aar (and its
    // Fresco/imagepipeline transitives) ship Kotlin metadata version 2.2.0; a
    // 2.0.20 compiler can only read metadata up to 2.1.0 and fails with
    // "Incompatible classes were found in dependencies" the moment this
    // module's Kotlin source references any RN bridge type (WritableMap,
    // ReadableMap, Arguments, ...). A newer compiler reading OLDER metadata
    // from :core/:android (compiled at 2.0.20) is always safe - Kotlin
    // metadata is backward-compatible to read, never forward - so bumping
    // ONLY this module (which has no Compose dependency to keep paired with
    // a matching compose-compiler-plugin version, unlike :android) is the
    // surgical fix rather than risking the already-verified :core/:android
    // Compose stack by bumping the whole project.
    id("org.jetbrains.kotlin.android") version "2.2.0"
    `maven-publish`
}

// Coordinates exist ONLY so a host app can test-integrate this module before
// it is ever published anywhere real (see mobile/android-sdk/README.md,
// "Installing locally, before any Maven publish exists"). `0.1.0-SNAPSHOT`
// is a placeholder pre-release version, not a promise of API stability.
group = "com.dpdpashield.sdk"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "com.dpdpashield.sdk.reactnative"
    compileSdk = 34

    defaultConfig {
        minSdk = 24 // matches :android - see its own build.gradle.kts comment
    }

    // Exposes the "debug" variant as a publishable software component - see
    // :android's build.gradle.kts for why this block is required at all.
    publishing {
        singleVariant("debug")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":android"))

    // `compileOnly`, deliberately - matches the convention every real
    // published RN native-module package uses (react-native-webview,
    // @react-native-async-storage/async-storage, etc.): the HOST APP's own
    // React Native install provides this class at runtime (via its
    // `com.facebook.react:react-native` Gradle auto-linking), so bundling a
    // second copy into this module's own .aar would risk two different RN
    // core versions colliding in one app's classpath. `compileOnly` still
    // resolves the real artifact at COMPILE time (confirmed reachable on
    // Maven Central, see mobile/android-sdk/README.md), so this module's
    // Kotlin source is verified against RN's real bridge API, not typed
    // blind against documentation - it simply isn't bundled into the output.
    compileOnly("com.facebook.react:react-android:0.87.1")

    // :android's own kotlinx-coroutines-android dependency is `implementation`,
    // not `api` - not visible on this module's compile classpath transitively,
    // so it's declared again here (same as :android does for its own use).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// AGP registers the "debug" component lazily, after project evaluation - see
// :android's identical afterEvaluate block for why this can't be unwrapped.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("debug") {
                from(components["debug"])
            }
        }
    }
}
