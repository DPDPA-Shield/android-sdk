# DPDPA Shield — Android SDK

Native Android library for collecting and managing user consent under India's **Digital Personal Data Protection Act 2023 (DPDPA)**. It connects directly to your DPDPA Shield account — the same consent ledger your web properties already write into — so every consent captured on Android appears in your dashboard, exports, and re-consent campaigns automatically.

[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-brightgreen)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/language-Kotlin-blue)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-BSD--3--Clause-blue)](LICENSE)

---

## Why native, not a WebView

Most consent SDKs render a web page inside a WebView. This SDK is a **native Jetpack Compose UI** — no WebView overhead, proper rendering of Hindi, Bengali, Tamil, and every other Indic script on low-end devices, and an offline-first gate that flips before any network call. When the user declines a purpose, the gate blocks third-party SDK initialisation immediately — even without a connection.

---

## Requirements

- Android 7.0+ (minSdk 24)
- Kotlin 2.0+ or Java 17+
- A DPDPA Shield account with a published consent notice → [dpdpashield.in](https://dpdpashield.in)

---

## Installation

The SDK is published via [JitPack](https://jitpack.io/#DPDPA-Shield/android-sdk).

**1. Add JitPack to your repositories** (`settings.gradle.kts`):
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

**2. Add the dependency** (`app/build.gradle.kts`):
```kotlin
dependencies {
    implementation("com.github.DPDPA-Shield:android-sdk:1.0.0-beta1")
}
```

---

## Quick Start

### 1. Initialise once in your Application class

```kotlin
class MyApp : Application() {
    lateinit var shield: ShieldConsentManager

    override fun onCreate() {
        super.onCreate()
        shield = ShieldConsentManager.init(
            applicationContext,
            ShieldConsentManager.Config(
                apiKey = "dpdpa_live_YOUR_KEY",   // Settings → API Keys
                cmpId  = 1234,                    // your IAB CMP ID if registered
            )
        )
        // Flushes the offline queue automatically when connectivity returns
        shield.observeConnectivity()
    }
}
```

### 2. Show the consent screen

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shield = (application as MyApp).shield

        setContent {
            var rawNotice by remember { mutableStateOf<ConsentNotice?>(null) }
            var notice by remember { mutableStateOf<LocalizedNotice?>(null) }

            LaunchedEffect(Unit) {
                if (shield.restoredDecision() == null) {
                    when (val result = shield.loadNotice()) {
                        is ShieldApiResult.Success -> {
                            rawNotice = result.value
                            notice = shield.localize()
                        }
                        is ShieldApiResult.Failure -> { /* show retry */ }
                    }
                }
            }

            notice?.let { localized ->
                val theme = ShieldConsentTheme(
                    primaryColor  = hexToComposeColor(rawNotice?.primaryColor),
                    showPoweredBy = rawNotice?.brandName == null,
                    logo = rawNotice?.logoUrl?.let { url ->
                        { AsyncImage(model = url, contentDescription = null, modifier = Modifier.height(40.dp)) }
                    },
                )
                ConsentScreen(
                    notice       = localized,
                    theme        = theme,
                    contactEmail = rawNotice?.contactEmail,
                    brandName    = rawNotice?.brandName,
                    onAcceptAll  = { given -> shield.recordDecision("user@example.com", given, "en") },
                    onRejectAll  = { given -> shield.recordDecision("user@example.com", given, "en") },
                    onSave       = { given -> shield.recordDecision("user@example.com", given, "en") },
                )
            }
        }
    }
}
```

### 3. Gate third-party SDKs on consent

```kotlin
// Only runs the block if the user has consented to this purpose
shield.gate.runIfConsented(analyticsPurposeId) {
    FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(true)
}

// Or react to consent changes over time
shield.gate.observeConsented(analyticsPurposeId) { consented ->
    if (consented) initAnalytics() else disableAnalytics()
}
```

---

## Key Features

| Feature | Details |
|---|---|
| **Native Compose UI** | No WebView. Proper Indic script rendering. Works on low-end devices. |
| **Offline-first** | Gate flips before any network call. Consent is queued with exponential backoff and synced when connectivity returns. |
| **IAB TCF v2** | Writes `IABTCF_TCString` to SharedPreferences so ad and analytics SDKs that speak the in-app TCF format read consent state automatically. |
| **App identity** | Sends your app's package name and signing certificate fingerprint as `X-App-Identity` — the mobile equivalent of domain allowlisting. |
| **WebView sync** | Suppresses duplicate consent banners when your own website is embedded in a WebView — pre-seeds localStorage before the Web SDK runs. |
| **22 languages** | Hindi, Bengali, Tamil, Telugu, Marathi, Gujarati, and 16 more — per-field fallback to English, never all-or-nothing. |
| **Parental consent** | Native 3-step flow for users under 18: age gate → guardian email OTP → confirmation. Backed by DPDPA Section 9. |

---

## Parental Consent (DPDPA Section 9)

```kotlin
// Drop-in 3-step screen: age gate → guardian OTP → confirmation
ParentalConsentScreen(
    manager   = shield,
    sessionId = UUID.randomUUID().toString(),
    orgName   = rawNotice?.brandName ?: "Your App",
    onVerified = { result -> /* proceed */ },
    onCancel   = { /* user is an adult or cancelled */ },
)
```

---

## WebView Bridge

If your app embeds your own website in a WebView and that page runs the DPDPA Shield Web SDK:

```kotlin
val bridge = ShieldWebViewBridge(shield, "dpdpa_live_YOUR_KEY")
bridge.install(webView)

webView.webViewClient = object : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        bridge.onPageStarted(view) // injects native consent state before page scripts run
    }
}
```

---

## IAB TCF v2 Purpose Mapping

```kotlin
ShieldConsentManager.Config(
    apiKey = "dpdpa_live_YOUR_KEY",
    cmpId  = 1234,
    tcfPurposeMapping = TcfPurposeMapping(mapOf(
        "your-analytics-purpose-uuid" to 8,   // IAB Purpose 8: Market research
        "your-marketing-purpose-uuid" to 4,   // IAB Purpose 4: Ad selection
    )),
)
```

---

## Support

- Docs: [dpdpashield.in/docs/android-sdk](https://dpdpashield.in/docs/android-sdk)
- Email: [hello@dpdpashield.in](mailto:hello@dpdpashield.in)
- Issues: [github.com/DPDPA-Shield/android-sdk/issues](https://github.com/DPDPA-Shield/android-sdk/issues)

---

## License

BSD 3-Clause — see [LICENSE](LICENSE).
