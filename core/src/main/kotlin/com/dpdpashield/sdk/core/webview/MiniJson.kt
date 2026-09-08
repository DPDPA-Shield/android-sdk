package com.dpdpashield.sdk.core.webview

/**
 * A hand-written, deliberately narrow JSON codec - not a general-purpose
 * parser. `:core` has zero third-party dependency beyond
 * kotlinx-coroutines-core (see mobile/android-sdk/README.md), so pulling in
 * kotlinx-serialization here just to encode/decode a flat object of
 * primitives would be the wrong trade for the one thing this module needs
 * it for: building the two localStorage payloads the Web SDK already reads
 * (`dpdpa_consent_{apiKey}` / `dpdpa_purposes_{apiKey}` - see CLAUDE.md,
 * "localStorage keys written by the SDK"), and decoding a purposes-map
 * reported back from a WebView. Same "hand-write the narrow thing rather
 * than add a dependency" call as [com.dpdpashield.sdk.core.tcf.Base64Url].
 */
object MiniJson {
    /**
     * Encodes a flat object of String/Boolean/Number values, e.g.
     * `encodeObject("id" to "n1", "status" to "ACCEPTED", "ts" to 123L)` ->
     * `{"id":"n1","status":"ACCEPTED","ts":123}`. Any other value type is a
     * programmer error, not a runtime input to tolerate - it throws.
     */
    fun encodeObject(vararg pairs: Pair<String, Any>): String {
        val sb = StringBuilder("{")
        pairs.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            sb.append('"').append(escapeJsonString(key)).append("\":")
            when (value) {
                is Boolean -> sb.append(if (value) "true" else "false")
                is Number -> sb.append(value.toString())
                is String -> sb.append('"').append(escapeJsonString(value)).append('"')
                else -> throw IllegalArgumentException(
                    "MiniJson.encodeObject: unsupported value type ${value::class.simpleName} for key \"$key\" - " +
                        "only String, Boolean and Number are supported",
                )
            }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun escapeJsonString(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    /**
     * Decodes ONLY a flat JSON object of string keys to boolean values, e.g.
     * `{"purpose-a":true,"purpose-b":false}`. This exists solely to parse
     * the purposes-map payload a WebView-embedded page reports back through
     * [com.dpdpashield.sdk.core.webview.WebViewConsentBridge.parseWebReportedPurposes].
     *
     * Any input that isn't exactly this shape - malformed JSON, a nested
     * object, a non-boolean value, trailing garbage, missing braces -
     * returns `null` rather than a partial or best-effort result. A
     * malformed report from web content (which this app does not control
     * the way it controls its own native code) must never be silently
     * misread as a real consent decision.
     */
    fun decodeFlatBooleanMap(json: String): Map<String, Boolean>? {
        val trimmed = json.trim()
        if (trimmed.length < 2 || trimmed.first() != '{' || trimmed.last() != '}') return null
        val body = trimmed.substring(1, trimmed.length - 1)
        if (body.isBlank()) return emptyMap()

        val result = mutableMapOf<String, Boolean>()
        var i = 0
        val n = body.length
        while (i < n) {
            while (i < n && (body[i].isWhitespace() || body[i] == ',')) i++
            if (i >= n) break

            if (body[i] != '"') return null
            i++
            val key = StringBuilder()
            var closedKey = false
            while (i < n) {
                val c = body[i]
                if (c == '"') {
                    closedKey = true
                    i++
                    break
                }
                if (c == '\\') {
                    if (i + 1 >= n) return null
                    when (body[i + 1]) {
                        '"' -> key.append('"')
                        '\\' -> key.append('\\')
                        'n' -> key.append('\n')
                        'r' -> key.append('\r')
                        't' -> key.append('\t')
                        else -> return null
                    }
                    i += 2
                } else {
                    key.append(c)
                    i++
                }
            }
            if (!closedKey) return null

            while (i < n && body[i].isWhitespace()) i++
            if (i >= n || body[i] != ':') return null
            i++
            while (i < n && body[i].isWhitespace()) i++

            val value = when {
                body.startsWith("true", i) -> {
                    i += 4
                    true
                }
                body.startsWith("false", i) -> {
                    i += 5
                    false
                }
                else -> return null
            }
            result[key.toString()] = value

            while (i < n && body[i].isWhitespace()) i++
        }
        return result
    }
}
