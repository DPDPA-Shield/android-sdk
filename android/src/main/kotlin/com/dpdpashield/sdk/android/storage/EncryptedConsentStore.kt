package com.dpdpashield.sdk.android.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dpdpashield.sdk.core.model.ConsentDecision
import com.dpdpashield.sdk.core.net.SdkRecordRequest
import com.dpdpashield.sdk.core.queue.QueueStore
import com.dpdpashield.sdk.core.queue.QueuedConsentWrite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PREFS_FILE_NAME = "dpdpashield_consent_secure"
private const val KEY_LAST_DECISION = "last_decision"
private const val KEY_OFFLINE_QUEUE = "offline_queue"

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Encrypted at-rest storage for the two things this SDK persists between
 * process launches: the data principal's last known decision (so
 * [com.dpdpashield.sdk.core.gate.ShieldGate] can be seeded correctly on cold
 * start, before any network round trip) and the offline write queue.
 *
 * `EncryptedSharedPreferences` (AES256-GCM values, AES256-SIV keys, backed
 * by a Keystore-generated MasterKey) - deliberately NOT the plain
 * SharedPreferences the TCF writer uses. A consent decision plus its
 * hashed identifier is exactly the kind of "which purposes did this specific
 * person agree to" record that DPDPA-conscious storage should encrypt at
 * rest by default, even though the identifier itself is already a one-way
 * hash - defence in depth, not a claim that the hash alone was insufficient.
 */
class EncryptedConsentStore private constructor(
    private val prefs: SharedPreferences,
) : QueueStore {

    fun saveDecision(decision: ConsentDecision) {
        prefs.edit().putString(KEY_LAST_DECISION, json.encodeToString(DecisionDto.serializer(), decision.toDto())).apply()
    }

    fun loadDecision(): ConsentDecision? {
        val raw = prefs.getString(KEY_LAST_DECISION, null) ?: return null
        return runCatching { json.decodeFromString(DecisionDto.serializer(), raw).toCoreModel() }.getOrNull()
    }

    fun clearDecision() {
        prefs.edit().remove(KEY_LAST_DECISION).apply()
    }

    override fun loadAll(): List<QueuedConsentWrite> {
        val raw = prefs.getString(KEY_OFFLINE_QUEUE, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(QueueDto.serializer(), raw).items.map { it.toCoreModel() }
        }.getOrDefault(emptyList())
    }

    override fun saveAll(items: List<QueuedConsentWrite>) {
        val dto = QueueDto(items.map { it.toDto() })
        prefs.edit().putString(KEY_OFFLINE_QUEUE, json.encodeToString(QueueDto.serializer(), dto)).apply()
    }

    companion object {
        fun create(context: Context): EncryptedConsentStore {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedConsentStore(prefs)
        }
    }
}

// ── Persistence DTOs - see OkHttpShieldApiClient.kt's doc comment on why the
// wire/storage shape lives in `android`, not `core`: this keeps `core` free
// of a hard dependency on kotlinx.serialization, so a host app that already
// ships a different JSON library isn't forced to carry two. ─────────────────

@Serializable
private data class DecisionDto(
    val noticeId: String,
    val given: Map<String, Boolean>,
    val languageShown: String,
    val decidedAtEpochMs: Long,
    val synced: Boolean,
)

private fun ConsentDecision.toDto() = DecisionDto(noticeId, given, languageShown, decidedAtEpochMs, synced)
private fun DecisionDto.toCoreModel() = ConsentDecision(noticeId, given, languageShown, decidedAtEpochMs, synced)

@Serializable
private data class QueueDto(val items: List<QueuedWriteDto>)

@Serializable
private data class QueuedWriteDto(
    val id: String,
    val payload: SdkRecordRequestDto,
    val appIdentity: String? = null,
    val createdAtEpochMs: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long,
)

@Serializable
private data class SdkRecordRequestDto(
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
)

private fun QueuedConsentWrite.toDto() = QueuedWriteDto(
    id = id,
    payload = payload.toDto(),
    appIdentity = appIdentity,
    createdAtEpochMs = createdAtEpochMs,
    attemptCount = attemptCount,
    nextAttemptAtEpochMs = nextAttemptAtEpochMs,
)

private fun QueuedWriteDto.toCoreModel() = QueuedConsentWrite(
    id = id,
    payload = payload.toCoreModel(),
    appIdentity = appIdentity,
    createdAtEpochMs = createdAtEpochMs,
    attemptCount = attemptCount,
    nextAttemptAtEpochMs = nextAttemptAtEpochMs,
)

private fun SdkRecordRequest.toDto() = SdkRecordRequestDto(apiKey, status, purposes, userAgent, url, language, positionShown, noticeId, identifierHash, identifier)
private fun SdkRecordRequestDto.toCoreModel() = SdkRecordRequest(apiKey, status, purposes, userAgent, url, language, positionShown, noticeId, identifierHash, identifier)
