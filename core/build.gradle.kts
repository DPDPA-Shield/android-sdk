// Pure Kotlin/JVM module - deliberately NOT `com.android.library`. No
// android.* import may appear anywhere under src/main/kotlin; that's what
// makes this module compilable and unit-testable with a bare kotlinc/JDK,
// with no Android SDK, no emulator, no Robolectric. See VerifyCore.kt for
// the real, executed proof (24/24 gates - run it with the command in
// ../README.md).
plugins {
    kotlin("jvm") version "2.0.20"
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

// Coordinates exist ONLY so a host app can test-integrate this module before
// it is ever published anywhere real (see mobile/android-sdk/README.md,
// "Installing locally, before any Maven publish exists"). `0.1.0-SNAPSHOT`
// is a placeholder pre-release version, not a promise of API stability.
group = "com.dpdpashield.sdk"
version = "0.1.0-SNAPSHOT"

// `kotlin("jvm")` applies the Java plugin underneath, which is what makes
// `components["java"]` resolvable below - no extra plugin needed for that.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
}

// The verify/ source set mirrors src/verify/kotlin/VerifyCore.kt, which was
// run in this repo's sandbox via a standalone kotlinc invocation rather than
// through this Gradle module (no Gradle was available there). Once opened in
// Android Studio, `./gradlew :core:test` should be preferred going forward -
// VerifyCoreKt's assertions are candidates to port into real kotlin.test
// `@Test` functions rather than kept as a hand-rolled gate runner long-term.
sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
    }
}
