package com.dpdpashield.sdk.core.webview

import com.dpdpashield.sdk.core.model.ConsentDecision

/**
 * Pure logic for the WebView bridge (ANDROID-SDK-EXPANSION-PLAN.md, step 3
 * "WebView bridge") - the same "inject native consent state before the
 * embedded page's own script runs" pattern Didomi/OneTrust use, so a
 * tenant's own site running the EXISTING Web SDK (`packages/sdk`) inside a
 * host app's WebView sees a decision that already exists and never shows
 * its own duplicate banner.
 *
 * All string-building here is pure and platform-independent so it can be
 * compiled and verified for real without a WebView (an Android framework
 * class, unavailable to `:core`) - see VerifyCore.kt gates 8-8g. The actual
 * `WebView.evaluateJavascript(...)` call lives in
 * `com.dpdpashield.sdk.android.webview.ShieldWebViewBridge` (`:android`,
 * written but not compiled - see mobile/android-sdk/README.md).
 *
 * This does NOT create a second consent record. The two localStorage keys
 * it writes (`dpdpa_consent_{apiKey}` / `dpdpa_purposes_{apiKey}`) are read
 * by the EXISTING Web SDK's own init logic (CLAUDE.md, "localStorage keys
 * written by the SDK") - this only pre-seeds them so that logic sees a
 * decision that already exists and skips its own prompt.
 */
object WebViewConsentBridge {
    /** Returned when there is no native decision yet - a genuine no-op, not
     *  an empty string, so callers never have to special-case "nothing to
     *  inject" themselves. */
    private const val NOOP_SCRIPT = "(function(){})();"

    /**
     * Classifies a purposes map into the same tri-state outcome the backend
     * ConsentRecord/exports already use (CLAUDE.md: "the consent-outcome
     * enum" is ACCEPTED | REJECTED | PARTIAL - distinct from the separate
     * withdrawal-state enum). An empty map (a notice with no toggleable
     * purposes at all - everything required) is ACCEPTED, since there was
     * nothing to decline.
     */
    fun classifyStatus(given: Map<String, Boolean>): String = when {
        given.values.all { it } -> "ACCEPTED"
        given.values.none { it } -> "REJECTED"
        else -> "PARTIAL"
    }

    /**
     * Builds the JS to `evaluateJavascript` into a WebView, seeding
     * `dpdpa_consent_{apiKey}` and `dpdpa_purposes_{apiKey}` in
     * `localStorage` from [decision]. Returns [NOOP_SCRIPT] when [decision]
     * is null (no native decision exists yet - let the embedded page's own
     * Web SDK run its normal flow). Safe to call on every page navigation:
     * re-setting the same keys is idempotent.
     *
     * Deliberately does NOT call `JSON.stringify()` inside the injected
     * script - the JSON payloads are serialised here, in Kotlin, via
     * [MiniJson], and passed as pre-built string literals. That avoids
     * needing to construct a JS object-literal expression at all (smaller
     * surface for anything to go wrong at execution time) and keeps the
     * only thing that has to be escaped correctly a single-quoted-string
     * boundary, handled by [escapeForSingleQuotedJs].
     */
    fun buildLocalStorageInjectionScript(apiKey: String, decision: ConsentDecision?): String {
        if (decision == null) return NOOP_SCRIPT

        val consentJson = MiniJson.encodeObject(
            "id" to decision.noticeId,
            "status" to classifyStatus(decision.given),
            "ts" to decision.decidedAtEpochMs,
            "lang" to decision.languageShown,
        )
        val purposesJson = MiniJson.encodeObject(
            *decision.given.map { (purposeId, isGiven) -> purposeId to isGiven }.toTypedArray(),
        )

        val safeApiKey = escapeForSingleQuotedJs(apiKey)
        val safeConsentJson = escapeForSingleQuotedJs(consentJson)
        val safePurposesJson = escapeForSingleQuotedJs(purposesJson)

        return buildString {
            append("(function(){try{")
            append("localStorage.setItem('dpdpa_consent_").append(safeApiKey).append("','").append(safeConsentJson).append("');")
            append("localStorage.setItem('dpdpa_purposes_").append(safeApiKey).append("','").append(safePurposesJson).append("');")
            append("}catch(e){}})();")
        }
    }

    /**
     * Parses a purposes-map payload reported back from a WebView (the WEB
     * widget recorded a decision while running inside this app's WebView -
     * see `ShieldWebViewBridge.reportConsent`). Returns null on anything
     * malformed, per [MiniJson.decodeFlatBooleanMap]'s own contract - a
     * thin, stable-name wrapper so `:android` depends on this object's
     * public API rather than reaching into [MiniJson] directly.
     */
    fun parseWebReportedPurposes(purposesJson: String): Map<String, Boolean>? =
        MiniJson.decodeFlatBooleanMap(purposesJson)

    /**
     * Escapes arbitrary text for safe embedding inside a single-quoted JS
     * string literal that will be executed via `evaluateJavascript`.
     * Escapes backslash and the single quote (the delimiter itself), plus
     * CR/LF and the codepoints U+2028/U+2029 ("line separator" / "paragraph
     * separator" - a real, documented footgun: some JS tokenizers treat a
     * raw U+2028/2029 inside what looks like a single-line statement as a
     * line terminator, which can silently truncate the injected script).
     * Any other control character below U+0020 is escaped too, defensively.
     *
     * Compares against `.code` (an Int) for the non-ASCII codepoints rather
     * than Kotlin `' '` char literals - purely to keep this file's own
     * source text unambiguous (a `\u` char literal and a raw non-printable
     * character look identical in most editors/terminals; comparing the
     * numeric codepoint removes any doubt about what's actually being
     * matched).
     *
     * `internal`, not `private`: VerifyCore.kt exercises this directly
     * (gates 8c/8g) to prove the escaping is correct without needing a real
     * JS engine - see that file's own comment on why there's no JUnit/Gradle
     * test source set yet. `internal` visibility is what a future
     * `core/src/test/kotlin` suite (Gradle test sources are friend-compiled
     * against main by default) would also need, so this is the same choice
     * that setup will want, not a hack for the current ad-hoc compile.
     */
    internal fun escapeForSingleQuotedJs(s: String): String = buildString {
        for (c in s) {
            val code = c.code
            when {
                c == '\\' -> append("\\\\")
                c == '\'' -> append("\\'")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                code == 0x2028 -> append("\\u2028")
                code == 0x2029 -> append("\\u2029")
                code < 0x20 -> append("\\u%04x".format(code))
                else -> append(c)
            }
        }
    }

    /**
     * The exact inverse of [escapeForSingleQuotedJs] - exists ONLY so
     * VerifyCore.kt can pull a string literal's content back out of a
     * generated script and prove the round trip is correct (gate 8c). No
     * production code path ever needs to un-escape a script it just wrote;
     * this is a test seam, not a feature.
     */
    internal fun unescapeSingleQuotedJs(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> {
                        sb.append('\\')
                        i += 2
                    }
                    '\'' -> {
                        sb.append('\'')
                        i += 2
                    }
                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }
                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }
                    'u' -> {
                        val hex = s.substring(i + 2, i + 6)
                        sb.append(hex.toInt(16).toChar())
                        i += 6
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
