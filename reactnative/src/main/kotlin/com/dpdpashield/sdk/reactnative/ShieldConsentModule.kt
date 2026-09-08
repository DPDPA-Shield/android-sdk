package com.dpdpashield.sdk.reactnative

import com.dpdpashield.sdk.android.ShieldConsentManager
import com.dpdpashield.sdk.core.net.ShieldApiResult
import com.dpdpashield.sdk.core.tcf.TcfPurposeMapping
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Thin JS-facing wrapper over [ShieldConsentManager] - every method here does
 * exactly what its [ShieldConsentManager] counterpart does, with no extra
 * logic of its own; the actual consent engine, offline queue, TCF encoding
 * and gate enforcement all live in `:core`/`:android` and are unit-verified
 * there (see mobile/android-sdk/README.md). This class exists only to
 * translate RN's bridge types (ReadableMap/WritableMap/Promise) at the edge.
 *
 * JS side: `NativeModules.ShieldConsent.init({ apiKey, cmpId })`, etc. Event
 * emitter: `new NativeEventEmitter(NativeModules.ShieldConsent)` for the
 * `ShieldConsentChanged` event started by [startObservingConsent].
 *
 * `manager` is process-lifetime state, same as [ShieldConsentManager] itself
 * is documented to be (see its own `observeConnectivity` doc comment) - `init`
 * is expected to be called once, from the JS entry point, not per-screen.
 */
class ShieldConsentModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "ShieldConsent"

    // Dispatchers.Main so Promise resolution happens off a background thread
    // the JS bridge doesn't expect callbacks from; every suspend call this
    // module makes (loadNotice) is itself dispatched onto IO internally by
    // OkHttpShieldApiClient, so this does not block the main thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var manager: ShieldConsentManager? = null

    // Tracks which purposeIds already have a ShieldGate listener registered,
    // so a second startObservingConsent(sameId) call from JS (e.g. a screen
    // remounting) is a no-op rather than stacking a duplicate listener that
    // would emit the same event twice per change.
    private val watchedPurposeIds = mutableSetOf<String>()

    @ReactMethod
    fun init(config: ReadableMap, promise: Promise) {
        try {
            val apiKey = config.getString("apiKey")
                ?: return promise.reject("SHIELD_CONFIG_ERROR", "config.apiKey is required")
            if (!config.hasKey("cmpId")) {
                return promise.reject("SHIELD_CONFIG_ERROR", "config.cmpId is required")
            }
            val cmpId = config.getInt("cmpId")
            val cmpVersion = if (config.hasKey("cmpVersion")) config.getInt("cmpVersion") else 1
            val tcfMapping = config.getMap("tcfPurposeMapping")?.let(::readableMapToTcfMapping)

            manager = ShieldConsentManager.init(
                reactApplicationContext,
                ShieldConsentManager.Config(
                    apiKey = apiKey,
                    cmpId = cmpId,
                    cmpVersion = cmpVersion,
                    tcfPurposeMapping = tcfMapping,
                ),
            )
            promise.resolve(null)
        } catch (t: Throwable) {
            promise.reject("SHIELD_INIT_ERROR", t.message ?: "init() failed", t)
        }
    }

    @ReactMethod
    fun loadNotice(promise: Promise) {
        val m = requireManager(promise) ?: return
        scope.launch {
            when (val result = m.loadNotice()) {
                is ShieldApiResult.Success -> promise.resolve(Serialize.notice(result.value))
                is ShieldApiResult.Failure -> promise.reject(
                    "SHIELD_LOAD_NOTICE_FAILED",
                    result.error?.message ?: result.cause?.message ?: "Failed to load notice",
                    Serialize.error(result.httpStatus, result.error, result.cause),
                )
            }
        }
    }

    @ReactMethod
    fun localize(languageCode: String?, promise: Promise) {
        val m = requireManager(promise) ?: return
        val resolved = if (languageCode.isNullOrBlank()) m.localize() else m.localize(languageCode)
        promise.resolve(resolved?.let(Serialize::localizedNotice))
    }

    @ReactMethod
    fun restoredDecision(promise: Promise) {
        val m = requireManager(promise) ?: return
        promise.resolve(m.restoredDecision()?.let(Serialize::decision))
    }

    @ReactMethod
    fun recordDecision(identifier: String, given: ReadableMap, languageShown: String, promise: Promise) {
        val m = requireManager(promise) ?: return
        try {
            m.recordDecision(identifier, readableMapToBooleanMap(given), languageShown)
            promise.resolve(null)
        } catch (t: Throwable) {
            promise.reject("SHIELD_RECORD_DECISION_ERROR", t.message ?: "recordDecision() failed", t)
        }
    }

    /** See [ShieldConsentManager.syncGateFromWebView]'s own doc comment - this
     *  updates the in-process gate ONLY, it does not persist or enqueue a
     *  backend write, because the Web SDK running inside the WebView already
     *  made its own POST /consent/sdk-record call. */
    @ReactMethod
    fun syncGateFromWebView(given: ReadableMap, promise: Promise) {
        val m = requireManager(promise) ?: return
        m.syncGateFromWebView(readableMapToBooleanMap(given))
        promise.resolve(null)
    }

    @ReactMethod
    fun flushQueueAsync(promise: Promise) {
        val m = requireManager(promise) ?: return
        m.flushQueueAsync()
        promise.resolve(null)
    }

    @ReactMethod
    fun observeConnectivity(promise: Promise) {
        val m = requireManager(promise) ?: return
        m.observeConnectivity()
        promise.resolve(null)
    }

    /** The gate check itself - JS's equivalent of `ShieldGate.isConsented`.
     *  There is no JS equivalent of `runIfConsented`'s "run this block only
     *  if consented" (a Kotlin inline lambda isn't a bridgeable value); the
     *  JS-idiomatic mapping is `if (await ShieldConsent.isConsented(id)) { ... }`. */
    @ReactMethod
    fun isConsented(purposeId: String, promise: Promise) {
        val m = requireManager(promise) ?: return
        promise.resolve(m.gate.isConsented(purposeId))
    }

    /**
     * Registers a [com.dpdpashield.sdk.core.gate.ShieldGate.observeConsented]
     * listener for [purposeId] - fires a `ShieldConsentChanged` device event
     * `{ purposeId, given }` immediately for the current state and again on
     * every future change, exactly mirroring that method's own contract.
     * Idempotent per purposeId (see [watchedPurposeIds]'s doc comment).
     */
    @ReactMethod
    fun startObservingConsent(purposeId: String, promise: Promise) {
        val m = requireManager(promise) ?: return
        if (!watchedPurposeIds.add(purposeId)) {
            promise.resolve(null)
            return
        }
        m.gate.observeConsented(purposeId) { given ->
            val params = Arguments.createMap().apply {
                putString("purposeId", purposeId)
                putBoolean("given", given)
            }
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("ShieldConsentChanged", params)
        }
        promise.resolve(null)
    }

    // Required by RN's NativeEventEmitter/RCTEventEmitter contract - JS calls
    // these on every addListener()/removeAllListeners() even though this
    // module has no per-listener bookkeeping (it always emits regardless of
    // subscriber count, same as most native modules with a single event).
    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    private fun requireManager(promise: Promise): ShieldConsentManager? {
        val m = manager
        if (m == null) {
            promise.reject("SHIELD_NOT_INITIALISED", "Call ShieldConsent.init() before any other method")
        }
        return m
    }

    private fun readableMapToBooleanMap(map: ReadableMap): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        val iterator = map.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            result[key] = map.getBoolean(key)
        }
        return result
    }

    private fun readableMapToTcfMapping(map: ReadableMap): TcfPurposeMapping {
        val result = mutableMapOf<String, Set<Int>>()
        val iterator = map.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            val array: ReadableArray = map.getArray(key) ?: continue
            val ints = mutableSetOf<Int>()
            for (i in 0 until array.size()) ints.add(array.getInt(i))
            result[key] = ints
        }
        return TcfPurposeMapping(result)
    }
}
