package com.dpdpashield.sdk.android.net

import com.dpdpashield.sdk.core.model.ConsentNotice
import com.dpdpashield.sdk.core.model.ConsentPurpose
import com.dpdpashield.sdk.core.model.NoticeTranslation
import com.dpdpashield.sdk.core.model.PurposeTranslation
import com.dpdpashield.sdk.core.net.ApiErrorEnvelope
import com.dpdpashield.sdk.core.net.SdkRecordRequest
import com.dpdpashield.sdk.core.net.ShieldApiClient
import com.dpdpashield.sdk.core.net.ShieldApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * `X-App-Identity: <packageName>:<sha256CertFingerprint>` - the exact header
 * name and format `checkAppIdentity()` in apps/api/src/lib/sdkSecurity.ts
 * parses (see CLAUDE.md "Android SDK expansion - backend step 1"). The
 * fingerprint is the app's own signing certificate SHA-256, computed once at
 * SDK init from the PackageManager signing info - see
 * AppIdentityProvider.kt in the same package for how the android module
 * derives it; this class only ever receives the already-formatted string.
 */
private const val APP_IDENTITY_HEADER = "X-App-Identity"
private const val BASE_URL = "https://api.dpdpashield.in/api/v1/consent"

// ── Wire DTOs (kotlinx.serialization) - deliberately separate from core's
// plain data classes. `core` has zero dependency on any serialization
// library by design (see core/build.gradle.kts and README.md "Why two
// modules"), so the JSON shape lives here, in the one module allowed to
// depend on the Android/JVM ecosystem, and is mapped onto core's domain
// model before anything else in the SDK ever sees it. ──────────────────────

@Serializable
private data class PublicNoticeEnvelope(val data: PublicNoticeDto? = null, val error: ErrorDto? = null)

@Serializable
private data class ErrorDto(val code: String? = null, val message: String = "")

@Serializable
private data class PublicNoticeDto(
    val id: String,
    val title: String,
    val description: String,
    val privacyPolicyUrl: String? = null,
    val contactEmail: String? = null,
    val complaintText: String? = null,
    val withdrawalUrl: String? = null,
    val purposes: List<PurposeDto> = emptyList(),
    val translations: Map<String, TranslationDto> = emptyMap(),
    val availableLanguages: List<String> = emptyList(),
    val brandName: String? = null,
    val logoUrl: String? = null,
    val primaryColor: String? = null,
)

@Serializable
private data class PurposeDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
    val dataCategories: List<String> = emptyList(),
)

@Serializable
private data class TranslationDto(
    val title: String? = null,
    val introText: String? = null,
    val rightsText: String? = null,
    val purposes: Map<String, PurposeTranslationDto> = emptyMap(),
)

@Serializable
private data class PurposeTranslationDto(val name: String? = null, val description: String? = null)

/** Mirrors SdkRecordSchema exactly - see core/net/SdkRecordRequest.kt's doc
 *  comment. `@SerialName` isn't needed anywhere here because every field
 *  name already matches the wire name verbatim - kept 1:1 on purpose so a
 *  diff against consent.schema.ts is a name-for-name comparison. */
@Serializable
private data class SdkRecordBody(
    val apiKey: String,
    val status: String? = null,
    val purposes: Map<String, Boolean>? = null,
    val userAgent: String? = null,
    val url: String? = null,
    val language: String? = null,
    val positionShown: String? = null,
    val noticeId: String? = null,
    val identifierHash: String? = null,
    val identifier: String? = null,
    val externalId: String? = null,
)

@Serializable
private data class SdkRecordResponseEnvelope(val data: kotlinx.serialization.json.JsonElement? = null, val error: ErrorDto? = null)

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

class OkHttpShieldApiClient(
    private val client: OkHttpClient = OkHttpClient(),
) : ShieldApiClient {

    override suspend fun fetchNotice(apiKey: String, appIdentity: String?, noticeId: String?): ShieldApiResult<ConsentNotice> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(BASE_URL).append("/public-notice?apiKey=").append(apiKey)
                if (noticeId != null) append("&noticeId=").append(noticeId)
            }
            val requestBuilder = Request.Builder().url(url).get()
            if (appIdentity != null) requestBuilder.header(APP_IDENTITY_HEADER, appIdentity)

            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    val envelope = json.decodeFromString(PublicNoticeEnvelope.serializer(), bodyText)
                    if (!response.isSuccessful || envelope.data == null) {
                        return@withContext ShieldApiResult.Failure(
                            httpStatus = response.code,
                            error = envelope.error?.let { ApiErrorEnvelope(it.code, it.message) },
                            cause = null,
                        )
                    }
                    ShieldApiResult.Success(envelope.data.toCoreModel())
                }
            } catch (e: IOException) {
                ShieldApiResult.Failure(httpStatus = null, error = null, cause = e)
            } catch (e: kotlinx.serialization.SerializationException) {
                // A malformed/unexpected response body is treated the same as
                // a network failure by the caller (ShieldConsentEngine has no
                // separate "bad response" branch) - both mean "try again
                // later", never "crash the host app's consent flow".
                ShieldApiResult.Failure(httpStatus = null, error = null, cause = e)
            }
        }

    override suspend fun submitConsent(request: SdkRecordRequest, appIdentity: String?): ShieldApiResult<Unit> =
        withContext(Dispatchers.IO) {
            val body = SdkRecordBody(
                apiKey = request.apiKey,
                status = request.status,
                purposes = request.purposes,
                userAgent = request.userAgent,
                url = request.url,
                language = request.language,
                positionShown = request.positionShown,
                noticeId = request.noticeId,
                identifierHash = request.identifierHash,
                identifier = request.identifier,
                externalId = request.externalId,
            )
            val requestBuilder = Request.Builder()
                .url("$BASE_URL/sdk-record")
                .post(json.encodeToString(SdkRecordBody.serializer(), body).toRequestBody("application/json".toMediaType()))
            if (appIdentity != null) requestBuilder.header(APP_IDENTITY_HEADER, appIdentity)

            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        val bodyText = response.body?.string().orEmpty()
                        val envelope = runCatching { json.decodeFromString(SdkRecordResponseEnvelope.serializer(), bodyText) }.getOrNull()
                        return@withContext ShieldApiResult.Failure(
                            httpStatus = response.code,
                            error = envelope?.error?.let { ApiErrorEnvelope(it.code, it.message) },
                            cause = null,
                        )
                    }
                    ShieldApiResult.Success(Unit)
                }
            } catch (e: IOException) {
                ShieldApiResult.Failure(httpStatus = null, error = null, cause = e)
            }
        }
}

private fun PublicNoticeDto.toCoreModel(): ConsentNotice = ConsentNotice(
    id = id,
    title = title,
    description = description,
    privacyPolicyUrl = privacyPolicyUrl,
    contactEmail = contactEmail,
    complaintText = complaintText,
    withdrawalUrl = withdrawalUrl,
    purposes = purposes.map {
        ConsentPurpose(id = it.id, name = it.name, description = it.description, required = it.required, dataCategories = it.dataCategories)
    },
    translations = translations.mapValues { (_, t) ->
        NoticeTranslation(
            title = t.title,
            introText = t.introText,
            rightsText = t.rightsText,
            purposes = t.purposes.mapValues { (_, pt) -> PurposeTranslation(name = pt.name, description = pt.description) },
        )
    },
    availableLanguages = availableLanguages,
    brandName = brandName,
    logoUrl = logoUrl,
    primaryColor = primaryColor,
)
