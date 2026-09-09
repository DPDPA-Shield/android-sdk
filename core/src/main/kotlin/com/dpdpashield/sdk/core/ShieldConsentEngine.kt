package com.dpdpashield.sdk.core

import com.dpdpashield.sdk.core.gate.ShieldGate
import com.dpdpashield.sdk.core.hash.DataPrincipalHasher
import com.dpdpashield.sdk.core.i18n.LocalizedNotice
import com.dpdpashield.sdk.core.i18n.NoticeLocalizer
import com.dpdpashield.sdk.core.model.ConsentDecision
import com.dpdpashield.sdk.core.model.ConsentNotice
import com.dpdpashield.sdk.core.net.ShieldApiClient
import com.dpdpashield.sdk.core.net.ShieldApiResult
import com.dpdpashield.sdk.core.net.SdkRecordRequest
import com.dpdpashield.sdk.core.queue.OfflineConsentQueue
import com.dpdpashield.sdk.core.queue.QueueStore
import com.dpdpashield.sdk.core.tcf.TcfCoreInput
import com.dpdpashield.sdk.core.tcf.TcfPurposeMapping
import com.dpdpashield.sdk.core.tcf.TcfStringEncoder

/** Constructor config - everything the android module supplies at init time. */
data class ShieldConsentConfig(
    val apiKey: String,
    val appIdentity: String? = null,
    val cmpId: Int,
    val cmpVersion: Int = 1,
    val tcfPurposeMapping: TcfPurposeMapping? = null,
    val queueBaseDelayMs: Long = 5_000L,
    val queueMaxDelayMs: Long = 30 * 60_000L,
)

/**
 * The single object a host app holds. Owns notice state, the current
 * decision, [ShieldGate], and the [OfflineConsentQueue] - everything the
 * android module's ShieldConsentManager needs is exposed here so that class
 * is a thin platform shim (Compose UI + EncryptedSharedPreferences +
 * lifecycle wiring) rather than a second copy of this logic.
 *
 * Deliberately has NO knowledge of Android - it depends only on the
 * [ShieldApiClient] and [QueueStore] interfaces the platform module
 * implements, which is what makes this whole class compilable and
 * unit-testable as plain JVM Kotlin (see mobile/android-sdk/README.md and
 * core/src/verify/kotlin/VerifyCore.kt).
 */
class ShieldConsentEngine(
    private val config: ShieldConsentConfig,
    private val apiClient: ShieldApiClient,
    queueStore: QueueStore,
    val gate: ShieldGate = ShieldGate(),
) {
    private var notice: ConsentNotice? = null
    private val queue = OfflineConsentQueue(
        store = queueStore,
        baseDelayMs = config.queueBaseDelayMs,
        maxDelayMs = config.queueMaxDelayMs,
    )

    val queueSize: Int get() = queue.size

    suspend fun loadNotice(noticeId: String? = null): ShieldApiResult<ConsentNotice> {
        val result = apiClient.fetchNotice(config.apiKey, config.appIdentity, noticeId)
        if (result is ShieldApiResult.Success) {
            notice = result.value
            gate.setRequiredPurposeIds(result.value.purposes.filter { it.required }.map { it.id }.toSet())
        }
        return result
    }

    fun currentNotice(): ConsentNotice? = notice

    fun localize(languageCode: String): LocalizedNotice? =
        notice?.let { NoticeLocalizer.resolve(it, languageCode) }

    /**
     * Applies a user's decision immediately (gate flips synchronously, before
     * any network call) and enqueues the backend write. This is the
     * offline-first guarantee from the plan doc: a tracker gated on a purpose
     * the user just declined stops running the instant this returns, whether
     * or not the device has connectivity.
     *
     * [identifier] is hashed on-device via [DataPrincipalHasher] and only the
     * hash ever leaves the device - see [SdkRecordRequest.identifierHash]'s
     * doc comment.
     *
     * [externalId] is the host app's own internal ID for this data principal
     * (e.g. their users-table primary key) - passed straight through, never
     * hashed or otherwise transformed, since unlike [identifier] it isn't PII
     * we need to protect on the wire. See [SdkRecordRequest.externalId].
     */
    fun recordDecision(
        identifier: String,
        given: Map<String, Boolean>,
        languageShown: String,
        nowEpochMs: Long,
        writeId: String,
        externalId: String? = null,
    ): QueuedWrite {
        val n = notice ?: error("recordDecision called before loadNotice succeeded")

        val decision = ConsentDecision(
            noticeId = n.id,
            given = given,
            languageShown = languageShown,
            decidedAtEpochMs = nowEpochMs,
            synced = false,
        )
        gate.updateDecision(decision)

        val request = SdkRecordRequest(
            apiKey = config.apiKey,
            purposes = given,
            language = languageShown,
            noticeId = n.id,
            identifierHash = DataPrincipalHasher.hash(identifier),
            externalId = externalId,
        )
        val queued = queue.enqueue(writeId, request, config.appIdentity, nowEpochMs)
        return QueuedWrite(queued.id)
    }

    /**
     * Drains every due write in the offline queue through [apiClient],
     * marking each sent or backing it off on failure. The android module
     * calls this from a connectivity-change receiver and/or a periodic
     * WorkManager job - this function itself has no opinion on when it
     * should run, only what happens when it does.
     */
    suspend fun flushQueue(nowEpochMs: Long): FlushSummary {
        var sent = 0
        var failed = 0
        var dropped = 0
        for (write in queue.dueForRetry(nowEpochMs)) {
            when (val result = apiClient.submitConsent(write.payload, write.appIdentity)) {
                is ShieldApiResult.Success -> {
                    queue.markSent(write.id)
                    sent++
                }
                is ShieldApiResult.Failure -> {
                    val stillQueued = queue.markFailed(write.id, nowEpochMs)
                    if (stillQueued == null) dropped++ else failed++
                }
            }
        }
        return FlushSummary(sent = sent, failed = failed, dropped = dropped, remaining = queue.size)
    }

    /**
     * Builds `IABTCF_TCString` from the current decision, or null when either
     * there's no decision yet or the host app never configured a
     * [ShieldConsentConfig.tcfPurposeMapping] - see [TcfPurposeMapping]'s doc
     * comment on why a missing mapping must never fall back to a guess.
     */
    fun buildTcfString(nowEpochMs: Long): String? {
        val mapping = config.tcfPurposeMapping ?: return null
        val decision = gate.currentDecision() ?: return null
        val givenPurposeIds = decision.given.filterValues { it }.keys
        val iabPurposes = mapping.toIabPurposeSet(givenPurposeIds)

        return TcfStringEncoder.encodeCoreString(
            TcfCoreInput(
                createdEpochMs = decision.decidedAtEpochMs,
                lastUpdatedEpochMs = nowEpochMs,
                cmpId = config.cmpId,
                cmpVersion = config.cmpVersion,
                consentScreen = 1,
                consentLanguage = decision.languageShown.take(2).ifBlank { "EN" },
                vendorListVersion = 0,
                tcfPolicyVersion = 4,
                isServiceSpecific = true,
                purposeConsent = iabPurposes,
            ),
        )
    }
}

data class QueuedWrite(val id: String)

data class FlushSummary(val sent: Int, val failed: Int, val dropped: Int, val remaining: Int)
