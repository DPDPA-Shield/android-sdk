package com.dpdpashield.sdk.core.net

/**
 * Mirrors `SdkRecordSchema` in apps/api/src/modules/consent/consent.schema.ts
 * exactly - field names, optionality, and the identifierHash/identifier split.
 * Every pre-existing field here is what the web SDK already sends to
 * `POST /api/v1/consent/sdk-record`; the Android SDK adds nothing to the body -
 * mobile attribution (channel=MOBILE_APP) is derived server-side from the
 * X-App-Identity header, never sent as a body field (see CLAUDE.md
 * "Android SDK expansion - backend step 1": a client-settable channel would
 * let a browser caller self-declare MOBILE_APP to dodge origin enforcement).
 */
data class SdkRecordRequest(
    val apiKey: String,
    val status: String? = null,
    val purposes: Map<String, Boolean>? = null,
    val userAgent: String? = null,
    val url: String? = null,
    val language: String? = null,
    val positionShown: String? = null,
    val noticeId: String? = null,
    /** 64-char lowercase hex SHA-256, produced on-device by
     *  [com.dpdpashield.sdk.core.hash.DataPrincipalHasher] - the SDK NEVER
     *  sends [identifier] when it can hash locally instead. This is the
     *  "hash proof on-device" differentiator from the plan doc, applied to
     *  every consent write, not just the offline path. */
    val identifierHash: String? = null,
    /** Raw identifier fallback - only used by a caller that explicitly opts
     *  out of on-device hashing (e.g. server-to-server reconciliation). */
    val identifier: String? = null,
    /** The host app's OWN internal ID for this data principal (e.g. their
     *  users-table primary key) - never derived by the SDK, purely whatever
     *  the caller already knows about their own logged-in user. Independent
     *  of [identifierHash]/[identifier] - set none, one, or both alongside
     *  this. Mirrors `RecordConsentSchema.externalId` /
     *  `SdkRecordSchema.externalId` server-side (both capped at 200 chars). */
    val externalId: String? = null,
)

/** The `{ error: { code?, message } }` envelope every route in this API uses. */
data class ApiErrorEnvelope(
    val code: String?,
    val message: String,
)

sealed class ShieldApiResult<out T> {
    data class Success<T>(val value: T) : ShieldApiResult<T>()
    data class Failure(val httpStatus: Int?, val error: ApiErrorEnvelope?, val cause: Throwable?) :
        ShieldApiResult<Nothing>()
}

/**
 * The network boundary the core module depends on. Deliberately an interface
 * with no OkHttp/Retrofit import anywhere in `core` - this is what keeps the
 * module pure-JVM and compilable/testable without the Android SDK. The
 * `android` module provides the real implementation
 * (OkHttpShieldApiClient) using OkHttp + kotlinx.serialization; a JVM unit
 * test or this module's own verify program can supply a fake instead.
 */
interface ShieldApiClient {
    suspend fun fetchNotice(apiKey: String, appIdentity: String?, noticeId: String? = null): ShieldApiResult<com.dpdpashield.sdk.core.model.ConsentNotice>
    suspend fun submitConsent(request: SdkRecordRequest, appIdentity: String?): ShieldApiResult<Unit>
}
