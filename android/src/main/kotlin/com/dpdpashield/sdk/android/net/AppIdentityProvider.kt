package com.dpdpashield.sdk.android.net

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Computes the `packageName:sha256CertFingerprint` composite string sent as
 * the `X-App-Identity` header - see CLAUDE.md "Android SDK expansion -
 * backend step 1" for the full server-side contract, including the
 * colon-separated-hex form Android Studio/Play Console display, which the
 * backend's `checkAppIdentity()` also accepts alongside bare hex. This class
 * always emits bare lowercase hex (simpler, and it's what
 * `SdkAppIdentity.certificateFingerprint` is stored as).
 *
 * Reads the APK's own signing certificate via PackageManager - this is the
 * same certificate a Play Store listing's "App integrity" page shows, and
 * matches what a developer registers in the DPDPA Shield dashboard under
 * Settings -> API Keys -> App Identities (POST /api-keys/:keyId/app-identities).
 * There is deliberately no way to override this at runtime from app code:
 * the whole point of checking the *signing* certificate rather than a
 * self-reported string is that only whoever holds the real signing key can
 * produce a request the backend allowlist will accept.
 */
object AppIdentityProvider {
    /** Returns null if signing info genuinely cannot be read (should not
     *  happen for an installed, running app) - callers must treat null the
     *  same as "no X-App-Identity header available" rather than crash, since
     *  [com.dpdpashield.sdk.core.gate.ShieldGate]'s enforcement never depends
     *  on this succeeding (an empty allowlist server-side is simply
     *  unenforced - see checkAppIdentity()'s doc comment). */
    fun current(context: Context): String? {
        val packageName = context.packageName
        val fingerprint = signingCertificateSha256(context) ?: return null
        return "$packageName:$fingerprint"
    }

    @Suppress("DEPRECATION") // GET_SIGNATURES path retained for API < 28 (see below)
    private fun signingCertificateSha256(context: Context): String? {
        val pm = context.packageManager
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            // apkContentsSigners covers the common single-signer case; a
            // multi-signer (key-rotated) app should register EVERY historical
            // certificate as its own SdkAppIdentity row - see admin.routes.ts
            // POST /api-keys/:keyId/app-identities, which supports exactly
            // that (one row per (packageName, certificateFingerprint) pair).
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            info.signatures
        }
        val primary = signatures?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(primary.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
