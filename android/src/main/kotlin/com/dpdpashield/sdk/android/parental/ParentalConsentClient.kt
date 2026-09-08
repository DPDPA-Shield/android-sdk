package com.dpdpashield.sdk.android.parental

import com.dpdpashield.sdk.core.model.AgeGateResult
import com.dpdpashield.sdk.core.model.ParentalConsentInitiated
import com.dpdpashield.sdk.core.model.ParentalConsentVerified
import com.dpdpashield.sdk.core.net.ApiErrorEnvelope
import com.dpdpashield.sdk.core.net.ShieldApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * HTTP client for DPDPA Section 9 parental consent endpoints.
 * Uses X-API-Key auth (not X-App-Identity — these are public SDK endpoints).
 *
 * Deliberately NOT part of ShieldApiClient / ShieldConsentEngine because the
 * parental consent flow is a separate, optional feature. A tenant's app that
 * never collects children's data never constructs this class.
 */
class ParentalConsentClient(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    baseUrl: String = "https://api.dpdpashield.in/api/v1/children",
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val JSON_MEDIA = "application/json".toMediaType()

    // ── Age gate ────────────────────────────────────────────────────────────

    suspend fun verifyAge(
        dob: String,
        sessionId: String,
    ): ShieldApiResult<AgeGateResult> = post("/age-gate/verify",
        """{"apiKey":"${apiKey.esc()}","dob":"${dob.esc()}","sessionId":"${sessionId.esc()}"}"""
    ) { data ->
        AgeGateResult(
            sessionId               = data.str("sessionId") ?: sessionId,
            isMinor                 = data.bool("isMinor") ?: false,
            age                     = data.int("age") ?: 0,
            requiresParentalConsent = data.bool("requiresParentalConsent") ?: false,
        )
    }

    // ── Initiate parental consent ───────────────────────────────────────────

    suspend fun initiateParentalConsent(
        sessionId: String,
        guardianEmail: String,
    ): ShieldApiResult<ParentalConsentInitiated> = post("/parental-consent/initiate",
        """{"apiKey":"${apiKey.esc()}","sessionId":"${sessionId.esc()}","guardianEmail":"${guardianEmail.esc()}"}"""
    ) { data ->
        ParentalConsentInitiated(
            consentId           = data.str("consentId") ?: "",
            maskedGuardianEmail = data.str("guardianEmail") ?: guardianEmail,
            expiresAt           = data.str("expiresAt") ?: "",
        )
    }

    // ── Verify OTP ─────────────────────────────────────────────────────────

    suspend fun verifyParentalConsent(
        consentId: String,
        otp: String,
    ): ShieldApiResult<ParentalConsentVerified> = post("/parental-consent/verify",
        """{"apiKey":"${apiKey.esc()}","consentId":"${consentId.esc()}","otp":"${otp.esc()}"}"""
    ) { data ->
        val restrictions = runCatching {
            data["restrictions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrNull() ?: emptyList()
        ParentalConsentVerified(
            success        = data.bool("success") ?: true,
            childAccountId = data.str("childAccountId") ?: "",
            restrictions   = restrictions,
        )
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private suspend fun <T> post(
        path: String,
        bodyJson: String,
        map: (JsonObject) -> T,
    ): ShieldApiResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base$path")
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .header("X-API-Key", apiKey)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val envelope = runCatching {
                    json.decodeFromString(RawEnvelope.serializer(), text)
                }.getOrElse {
                    return@use ShieldApiResult.Failure(response.code, null, it)
                }

                if (!response.isSuccessful) {
                    return@use ShieldApiResult.Failure(
                        httpStatus = response.code,
                        error      = envelope.error?.let { ApiErrorEnvelope(it.code, it.message) },
                        cause      = null,
                    )
                }

                val data = envelope.data?.jsonObject
                    ?: return@use ShieldApiResult.Failure(response.code, null, null)

                ShieldApiResult.Success(map(data))
            }
        } catch (e: IOException) {
            ShieldApiResult.Failure(httpStatus = null, error = null, cause = e)
        }
    }

    private fun String.esc() = replace("\\", "\\\\").replace("\"", "\\\"")
}

// ── JsonObject extension helpers ──────────────────────────────────────────────

private fun JsonObject.str(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.jsonPrimitive?.booleanOrNull

private fun JsonObject.int(key: String): Int? =
    get(key)?.jsonPrimitive?.intOrNull

// ── Wire DTOs ─────────────────────────────────────────────────────────────────

@Serializable
private data class RawEnvelope(
    @SerialName("data")  val data: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("error") val error: RawError? = null,
)

@Serializable
private data class RawError(
    @SerialName("code")    val code: String? = null,
    @SerialName("message") val message: String = "",
)
