package com.dpdpashield.sdk.android.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.dpdpashield.sdk.android.ShieldConsentManager
import com.dpdpashield.sdk.core.webview.WebViewConsentBridge

/**
 * Injects the native consent decision into a WebView's `localStorage`
 * before the tenant's own embedded page (running the existing Web SDK,
 * `packages/sdk`) has a chance to show its own banner - the same
 * "inject-before-init" pattern Didomi/OneTrust use for their WebView
 * bridges (ANDROID-SDK-EXPANSION-PLAN.md, step 3 "WebView bridge"). This
 * does NOT create a second consent record: the localStorage keys it writes
 * (`dpdpa_consent_{apiKey}` / `dpdpa_purposes_{apiKey}`) are read by the
 * EXISTING Web SDK's own init logic (CLAUDE.md, "localStorage keys written
 * by the SDK") - this bridge only pre-seeds them so that logic sees a
 * decision that already exists and skips its own prompt.
 *
 * All string-building, JSON encode/decode and JS-string escaping is pure
 * logic living in [com.dpdpashield.sdk.core.webview.WebViewConsentBridge]
 * (`:core`), verified for real in VerifyCore.kt gates 8-8g. This class's
 * only job is calling [WebView.evaluateJavascript] and
 * [WebView.addJavascriptInterface] - like the rest of `:android`, it has
 * never been compiled or run (see mobile/android-sdk/README.md,
 * "Verification status" - this file inherits that boundary).
 *
 * This class does NOT own the WebView's lifecycle or navigation - it is
 * deliberately NOT a `WebViewClient` subclass, so it never has to guess at
 * or conflict with a host app's own `WebViewClient`/`WebChromeClient`
 * logic. A host calls [onPageStarted] from inside their own
 * `WebViewClient.onPageStarted` override, and [install] once, right after
 * creating the `WebView` and before the first `loadUrl`.
 */
class ShieldWebViewBridge(
    private val manager: ShieldConsentManager,
    private val apiKey: String,
) {
    /**
     * Registers a `window.ShieldNative` JS interface object on [webView]
     * for the lifetime of the WebView. Call once, before the first
     * `loadUrl`/`loadData`.
     *
     * The embedded page can call:
     * - `window.ShieldNative.getDecisionAvailable()` -> boolean. True when a
     *   native decision already exists, so the page can skip fetching or
     *   rendering its own banner without even reading `localStorage`.
     * - `window.ShieldNative.reportConsent(status, purposesJson)` -> if the
     *   WEB widget records a decision while running inside this WebView
     *   (e.g. this WebView is the primary UI for that screen), this syncs
     *   [manager]'s in-process `ShieldGate` so any NATIVE code gated via
     *   `ShieldGate.runIfConsented` reacts immediately. It does NOT write a
     *   second `ConsentRecord` to the backend - the Web SDK running in the
     *   page already did that via its own `POST /consent/sdk-record` call;
     *   this only keeps the in-process native gate from disagreeing with
     *   what the user just chose on-screen. `status` is accepted but
     *   currently unused (reserved for a future analytics hook) - the
     *   purposes map is the only thing the gate actually needs.
     *
     * `@JavascriptInterface` methods are invoked by the WebView on a
     * background thread, not the UI thread - safe here because
     * [com.dpdpashield.sdk.core.gate.ShieldGate] is thread-safe by
     * construction (`@Volatile` decision + `CopyOnWriteArrayList`
     * listeners), so no additional synchronisation is needed in this class.
     */
    fun install(webView: WebView) {
        webView.addJavascriptInterface(JsInterface(), "ShieldNative")
    }

    /**
     * Call from the host's own `WebViewClient.onPageStarted` override, for
     * every navigation into the tenant's own consent-collecting page. Safe
     * to call on every navigation, including ones that aren't the consent
     * page: the injected script is a genuine no-op when there's no native
     * decision yet, and idempotent (re-setting the same keys) when there is
     * one.
     */
    fun onPageStarted(webView: WebView) {
        val decision = manager.restoredDecision()
        val script = WebViewConsentBridge.buildLocalStorageInjectionScript(apiKey, decision)
        webView.evaluateJavascript(script, null)
    }

    private inner class JsInterface {
        @JavascriptInterface
        fun getDecisionAvailable(): Boolean = manager.restoredDecision() != null

        @JavascriptInterface
        fun reportConsent(status: String, purposesJson: String) {
            val purposes = WebViewConsentBridge.parseWebReportedPurposes(purposesJson) ?: return
            manager.syncGateFromWebView(purposes)
        }
    }
}
