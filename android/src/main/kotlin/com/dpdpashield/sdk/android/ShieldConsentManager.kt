package com.dpdpashield.sdk.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.dpdpashield.sdk.android.net.AppIdentityProvider
import com.dpdpashield.sdk.android.net.OkHttpShieldApiClient
import com.dpdpashield.sdk.android.parental.ParentalConsentClient
import com.dpdpashield.sdk.android.storage.EncryptedConsentStore
import com.dpdpashield.sdk.android.storage.TcfSharedPreferencesWriter
import com.dpdpashield.sdk.core.ShieldConsentConfig
import com.dpdpashield.sdk.core.ShieldConsentEngine
import com.dpdpashield.sdk.core.gate.ShieldGate
import com.dpdpashield.sdk.core.i18n.LocalizedNotice
import com.dpdpashield.sdk.core.i18n.NoticeLocalizer
import com.dpdpashield.sdk.core.model.AgeGateResult
import com.dpdpashield.sdk.core.model.ConsentDecision
import com.dpdpashield.sdk.core.model.ConsentNotice
import com.dpdpashield.sdk.core.model.ParentalConsentInitiated
import com.dpdpashield.sdk.core.model.ParentalConsentVerified
import com.dpdpashield.sdk.core.net.ShieldApiResult
import com.dpdpashield.sdk.core.tcf.TcfPurposeMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * The one class a host app instantiates. Everything else in this module is
 * a supporting implementation detail behind this facade -
 * [com.dpdpashield.sdk.core.ShieldConsentEngine] is where the actual logic
 * lives (and is what's unit-tested - see mobile/android-sdk/README.md); this
 * class exists only to wire that engine to real Android I/O:
 * EncryptedSharedPreferences, OkHttp, ConnectivityManager, and the app's own
 * signing certificate.
 *
 * Usage (see mobile/android-sdk/README.md for the full walkthrough):
 * ```
 * val shield = ShieldConsentManager.init(
 *     applicationContext,
 *     ShieldConsentManager.Config(apiKey = "dpdpa_live_xxx", cmpId = 1234),
 * )
 * shield.gate.runIfConsented("<analytics-purpose-id>") { Analytics.init(this) }
 * ```
 */
class ShieldConsentManager private constructor(
    private val context: Context,
    private val config: Config,
    private val engine: ShieldConsentEngine,
) {
    /** The enforcement API - see [com.dpdpashield.sdk.core.gate.ShieldGate]'s
     *  doc comment. This is what a host app actually gates third-party SDK
     *  initialisation on. */
    val gate: ShieldGate get() = engine.gate

    /**
     * DPDPA Section 9 parental consent client. Only instantiated if you call
     * any of the age-gate / parental-consent methods - zero overhead otherwise.
     */
    val parentalConsent: ParentalConsentClient by lazy {
        ParentalConsentClient(apiKey = config.apiKey)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    data class Config(
        val apiKey: String,
        val cmpId: Int,
        val cmpVersion: Int = 1,
        val tcfPurposeMapping: TcfPurposeMapping? = null,
    )

    /** Loads the tenant's published notice. Call once at startup, before
     *  deciding whether to show the consent screen (compare against
     *  [restoredDecision] to know whether the user has already answered). */
    suspend fun loadNotice(): ShieldApiResult<ConsentNotice> = engine.loadNotice()

    fun localize(languageCode: String = Locale.getDefault().language): LocalizedNotice? = engine.localize(languageCode)

    /** The decision restored from encrypted storage at [init] time, if any -
     *  a non-null result means the consent screen should NOT be shown again
     *  unless the notice version has materially changed (host app's call). */
    fun restoredDecision(): ConsentDecision? = gate.currentDecision()

    /**
     * Records the user's choice: flips [gate] synchronously (offline-first),
     * persists it to [EncryptedConsentStore], queues the backend write, and
     * refreshes the IAB TCF SharedPreferences keys. [identifier] is hashed
     * on-device before anything is queued or sent - see
     * [com.dpdpashield.sdk.core.hash.DataPrincipalHasher].
     *
     * [externalId] is the host app's own internal ID for this user (e.g.
     * their own users-table primary key) - sent as-is, never hashed, so it
     * can be used later to correlate this record back to the app's own
     * database (Consent Records search, exports,
     * `GET /consent/by-external-id/:externalId`). Optional, independent of
     * [identifier].
     */
    fun recordDecision(identifier: String, given: Map<String, Boolean>, languageShown: String, externalId: String? = null) {
        val now = System.currentTimeMillis()
        engine.recordDecision(identifier, given, languageShown, now, writeId = UUID.randomUUID().toString(), externalId = externalId)

        val store = EncryptedConsentStore.create(context)
        gate.currentDecision()?.let { store.saveDecision(it) }

        TcfSharedPreferencesWriter.write(
            context = context,
            cmpId = config.cmpId,
            cmpVersion = config.cmpVersion,
            policyVersion = 4,
            tcString = engine.buildTcfString(now),
        )

        flushQueueAsync()
    }

    /**
     * Merges a purposes decision reported by
     * [com.dpdpashield.sdk.android.webview.ShieldWebViewBridge] - i.e. the
     * WEB widget, running inside a WebView this app embeds, recorded a
     * decision this app did not itself capture - into the in-process
     * [gate] ONLY. This does NOT persist to [EncryptedConsentStore] or
     * queue a backend write: the Web SDK that recorded this decision
     * already wrote its own `ConsentRecord` via `POST /consent/sdk-record`
     * from inside the WebView. Writing it again here would be a second,
     * redundant write of the exact same decision through a different
     * channel.
     *
     * Falls back to the notice already loaded by [engine] when there's no
     * prior gate decision to carry a notice id forward from (e.g. the
     * WebView is the very first surface the user ever saw); if neither is
     * available there is nothing to attach the decision to and this is a
     * silent no-op rather than a crash - the web widget's own record is
     * still the source of truth regardless.
     */
    fun syncGateFromWebView(given: Map<String, Boolean>) {
        val noticeId = gate.currentDecision()?.noticeId ?: engine.currentNotice()?.id ?: return
        val merged = ConsentDecision(
            noticeId = noticeId,
            given = given,
            languageShown = gate.currentDecision()?.languageShown ?: Locale.getDefault().language,
            decidedAtEpochMs = System.currentTimeMillis(),
            // The web widget already synced this to the backend itself via
            // its own POST /consent/sdk-record call - mark it synced here
            // too so this manager never enqueues a redundant write for it.
            synced = true,
        )
        gate.updateDecision(merged)
    }

    // ── DPDPA Section 9: parental consent (convenience wrappers) ─────────────

    /** Step 1 — verify the user's age before showing consent UI.
     *  [dob] must be in ISO-8601 date format (YYYY-MM-DD).
     *  [sessionId] is a stable, unique identifier for this age-gate session
     *  (e.g. a random UUID generated by the host app). */
    suspend fun verifyAge(dob: String, sessionId: String): ShieldApiResult<AgeGateResult> =
        parentalConsent.verifyAge(dob, sessionId)

    /** Step 2 — send the OTP to [guardianEmail].
     *  Show [com.dpdpashield.sdk.android.ui.ParentalConsentScreen] after this
     *  succeeds to collect the guardian's OTP. */
    suspend fun initiateParentalConsent(
        sessionId: String,
        guardianEmail: String,
    ): ShieldApiResult<ParentalConsentInitiated> =
        parentalConsent.initiateParentalConsent(sessionId, guardianEmail)

    /** Step 3 — submit the OTP the guardian received by email.
     *  On success the ChildAccount is created with processing restrictions. */
    suspend fun verifyParentalConsent(
        consentId: String,
        otp: String,
    ): ShieldApiResult<ParentalConsentVerified> =
        parentalConsent.verifyParentalConsent(consentId, otp)

    /** Fire-and-forget queue drain - safe to call opportunistically (app
     *  foreground, connectivity restored, a periodic WorkManager job). Errors
     *  are swallowed here because [ShieldConsentEngine.flushQueue] already
     *  re-queues on failure with backoff; there is nothing more for a caller
     *  of this convenience method to do with an exception. */
    fun flushQueueAsync() {
        scope.launch {
            runCatching { engine.flushQueue(System.currentTimeMillis()) }
        }
    }

    /** Registers a `ConnectivityManager.NetworkCallback` that flushes the
     *  offline queue the moment connectivity returns. Call once, typically
     *  from `Application.onCreate()`. There is no matching `stop()` because
     *  this callback is meant to live for the process lifetime, same as this
     *  manager itself (see README.md - a per-Activity instance would flush
     *  duplicate work every time an Activity is recreated). */
    fun observeConnectivity() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager?.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    flushQueueAsync()
                }
            },
        )
    }

    companion object {
        fun init(context: Context, config: Config): ShieldConsentManager {
            val appContext = context.applicationContext
            val store = EncryptedConsentStore.create(appContext)
            val appIdentity = AppIdentityProvider.current(appContext)

            val gate = ShieldGate()
            store.loadDecision()?.let { gate.updateDecision(it) }

            val engine = ShieldConsentEngine(
                config = ShieldConsentConfig(
                    apiKey = config.apiKey,
                    appIdentity = appIdentity,
                    cmpId = config.cmpId,
                    cmpVersion = config.cmpVersion,
                    tcfPurposeMapping = config.tcfPurposeMapping,
                ),
                apiClient = OkHttpShieldApiClient(),
                queueStore = store,
                gate = gate,
            )
            return ShieldConsentManager(appContext, config, engine)
        }
    }
}
