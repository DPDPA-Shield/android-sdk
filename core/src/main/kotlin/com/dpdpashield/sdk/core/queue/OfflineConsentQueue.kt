package com.dpdpashield.sdk.core.queue

import com.dpdpashield.sdk.core.net.SdkRecordRequest

/**
 * A consent write that hasn't been confirmed by the backend yet.
 *
 * `id` is a client-generated identifier used only for local bookkeeping
 * (dedup, removal) - it is never sent to the backend and has nothing to do
 * with the ConsentRecord id the server assigns.
 */
data class QueuedConsentWrite(
    val id: String,
    val payload: SdkRecordRequest,
    val appIdentity: String?,
    val createdAtEpochMs: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long = createdAtEpochMs,
)

/**
 * Durable storage for the queue, implemented by the android module on top of
 * EncryptedSharedPreferences (or any persistence a host app wants to supply -
 * this interface is what keeps [OfflineConsentQueue] itself free of any
 * Android import). Consent writes are exactly the kind of data that must
 * survive a process death between "user tapped Save while offline" and
 * "connectivity restored", so this cannot be an in-memory-only queue in the
 * android module - but the retry/backoff *logic* below has nothing to do
 * with how it's stored, which is why it lives here and is unit-testable
 * without any Android dependency.
 */
interface QueueStore {
    fun loadAll(): List<QueuedConsentWrite>
    fun saveAll(items: List<QueuedConsentWrite>)
}

/**
 * In-memory-backed offline queue with exponential backoff, deferring all
 * durability to an injected [QueueStore]. Every mutating call re-persists the
 * full list immediately - the queue is expected to hold at most a handful of
 * pending writes per device (one per notice re-consent while offline), never
 * an unbounded backlog, so O(n) re-serialisation on every call is the right
 * trade for "never lose a write to a crash between mutate and persist".
 */
class OfflineConsentQueue(
    private val store: QueueStore,
    private val baseDelayMs: Long = 5_000L,
    private val maxDelayMs: Long = 30 * 60_000L,
    private val maxAttempts: Int = 20,
) {
    private var items: MutableList<QueuedConsentWrite> = store.loadAll().toMutableList()

    val size: Int get() = items.size

    fun enqueue(id: String, payload: SdkRecordRequest, appIdentity: String?, nowEpochMs: Long): QueuedConsentWrite {
        val write = QueuedConsentWrite(
            id = id,
            payload = payload,
            appIdentity = appIdentity,
            createdAtEpochMs = nowEpochMs,
            nextAttemptAtEpochMs = nowEpochMs,
        )
        items.add(write)
        persist()
        return write
    }

    /** Writes whose next-attempt time has arrived, oldest first - so a burst
     *  of offline decisions is replayed in the order the user made them. */
    fun dueForRetry(nowEpochMs: Long): List<QueuedConsentWrite> =
        items.filter { it.nextAttemptAtEpochMs <= nowEpochMs }.sortedBy { it.createdAtEpochMs }

    fun markSent(id: String) {
        items.removeAll { it.id == id }
        persist()
    }

    /**
     * Exponential backoff, capped, with a hard ceiling on attempt count.
     * Returns `null` when the write has exhausted [maxAttempts] and has been
     * dropped from the queue - the caller is responsible for surfacing that
     * as a developer-visible failure (this SDK never silently discards a
     * consent write without telling the host app, since a dropped write
     * means a real ConsentRecord was never created for a real decision).
     */
    fun markFailed(id: String, nowEpochMs: Long): QueuedConsentWrite? {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val current = items[idx]
        val nextAttemptCount = current.attemptCount + 1
        if (nextAttemptCount >= maxAttempts) {
            items.removeAt(idx)
            persist()
            return null
        }
        val delay = backoffDelayMs(nextAttemptCount)
        val updated = current.copy(attemptCount = nextAttemptCount, nextAttemptAtEpochMs = nowEpochMs + delay)
        items[idx] = updated
        persist()
        return updated
    }

    fun snapshot(): List<QueuedConsentWrite> = items.toList()

    private fun backoffDelayMs(attemptCount: Int): Long {
        // attemptCount is always >= 1 here (see markFailed). 2^20 would
        // overflow a naive shift for a large attemptCount, so clamp the
        // exponent before shifting rather than clamping the result -
        // clamping only the result still computes an enormous intermediate
        // Long that could itself overflow into a negative delay.
        val exponent = attemptCount.coerceAtMost(30)
        val raw = baseDelayMs * (1L shl exponent)
        return raw.coerceAtMost(maxDelayMs)
    }

    private fun persist() {
        store.saveAll(items.toList())
    }
}
