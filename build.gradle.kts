// Root build file - no plugins applied here directly, each module applies
// its own. Versions pinned centrally so `:core` (pure Kotlin/JVM) and
// `:android` (Android library) agree on the Kotlin language version.
buildscript {
    extra.apply {
        set("kotlinVersion", "2.0.20")
        set("agpVersion", "8.5.2")
        set("composeCompilerVersion", "1.5.14") // paired with Kotlin 2.0.20 per JetBrains' compatibility map
        set("coroutinesVersion", "1.8.1")
        set("composeBomVersion", "2024.06.00")
        set("securityCryptoVersion", "1.1.0-alpha06")
        set("okhttpVersion", "4.12.0")
        set("kotlinxSerializationVersion", "1.7.1")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
