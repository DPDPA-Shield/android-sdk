// See mobile/android-sdk/README.md before opening this in Android Studio -
// `:core` compiles and runs standalone (verified in this repo's sandbox with
// a bare kotlinc, no Gradle needed - see README). `:android` and
// `:reactnative` both build for real via this Gradle project against a real
// Android SDK (`./gradlew :android:assembleDebug :reactnative:assembleDebug`,
// plus `:android:lint` - 0 errors) - see README.md's "Verification status"
// section for the precise line between "compiles and lints clean" and
// "exercised on a device/emulator" (the latter has not been done for either
// module).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle auto-provision the JDK 17 toolchain `:core` pins
// (`kotlin { jvmToolchain(17) }`) when the machine running the build only
// has a newer JDK on JAVA_HOME (this sandbox has JDK 21 via Android Studio's
// bundled JBR) - confirmed necessary against a real build: without it,
// Gradle 9's toolchain resolution refuses to auto-detect across major
// versions and fails with "Toolchain download repositories have not been
// configured."
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dpdpashield-android-sdk"

include(":core")
include(":android")
include(":reactnative")
