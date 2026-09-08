package com.dpdpashield.sdk.android.storage

import android.content.Context
import androidx.core.content.edit

/**
 * Writes the IAB TCF v2 "In-App consent details" plain SharedPreferences
 * keys so any third-party SDK already speaking that standard (Facebook,
 * Braze, most ad SDKs, several analytics SDKs) reads DPDPA Shield-managed
 * consent without any integration work beyond initialising after this SDK -
 * see ANDROID-SDK-EXPANSION-PLAN.md's differentiation table.
 *
 * MUST be the app's *default* SharedPreferences file, not a scoped one - the
 * IAB spec requires these keys to live where `PreferenceManager
 * .getDefaultSharedPreferences(context)` (or, on modern AndroidX,
 * `context.getSharedPreferences(context.packageName + "_preferences", ...)`)
 * finds them, because that's the only location every reader agrees to check
 * without being told our custom file name. This is also why these values are
 * NOT written to [EncryptedConsentStore] - encrypting them would make them
 * invisible to every third-party SDK this feature exists to inform.
 *
 * `IABTCF_gdprApplies = "0"` always - DPDPA 2023 is not GDPR, and claiming
 * gdprApplies=1 would be lying to every SDK that branches on it. Purpose
 * consent bits are still written with real values because many SDKs read
 * `IABTCF_TCString`'s purpose bitfield as a general opt-out signal
 * regardless of `gdprApplies` - see [com.dpdpashield.sdk.core.ShieldConsentEngine.buildTcfString]'s
 * doc comment for why a fabricated purpose mapping is refused instead of
 * guessed, and this class simply doesn't write `IABTCF_TCString` at all when
 * [tcString] is null.
 */
object TcfSharedPreferencesWriter {
    private const val DEFAULT_PREFS_SUFFIX = "_preferences"
    private const val KEY_TC_STRING = "IABTCF_TCString"
    private const val KEY_GDPR_APPLIES = "IABTCF_gdprApplies"
    private const val KEY_CMP_SDK_ID = "IABTCF_CmpSdkID"
    private const val KEY_CMP_SDK_VERSION = "IABTCF_CmpSdkVersion"
    private const val KEY_POLICY_VERSION = "IABTCF_PolicyVersion"
    private const val KEY_USE_NON_STANDARD_STACKS = "IABTCF_UseNonStandardStacks"

    fun write(context: Context, cmpId: Int, cmpVersion: Int, policyVersion: Int, tcString: String?) {
        val prefs = context.getSharedPreferences(context.packageName + DEFAULT_PREFS_SUFFIX, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_GDPR_APPLIES, "0")
            putInt(KEY_CMP_SDK_ID, cmpId)
            putInt(KEY_CMP_SDK_VERSION, cmpVersion)
            putInt(KEY_POLICY_VERSION, policyVersion)
            putInt(KEY_USE_NON_STANDARD_STACKS, 0)
            if (tcString != null) putString(KEY_TC_STRING, tcString) else remove(KEY_TC_STRING)
        }
    }

    /** Called on full withdrawal - clears the TC string so a reader doesn't
     *  keep acting on a stale consent after the user has withdrawn. */
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(context.packageName + DEFAULT_PREFS_SUFFIX, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_TC_STRING)
            putString(KEY_GDPR_APPLIES, "0")
        }
    }
}
