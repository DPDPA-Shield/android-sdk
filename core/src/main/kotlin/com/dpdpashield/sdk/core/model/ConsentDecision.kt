package com.dpdpashield.sdk.core.model

/**
 * The data principal's per-purpose decision for one notice, held in memory
 * (and persisted by the android module's EncryptedConsentStore) between app
 * launches. This is the SDK's own local record of "what did the user choose" -
 * separate from, and always ahead of, whatever the backend has confirmed,
 * because [ShieldGate] must gate third-party SDK init the instant the user
 * taps Save, offline or not (see OfflineConsentQueue).
 */
data class ConsentDecision(
    val noticeId: String,
    /** purposeId -> given. Absent key means "never decided" - callers must
     *  not conflate "explicitly rejected" with "not yet asked". */
    val given: Map<String, Boolean>,
    val languageShown: String,
    val decidedAtEpochMs: Long,
    /** True once this decision has been durably written to the backend
     *  ConsentRecord (i.e. the queued write for it succeeded). A decision
     *  that is only locally recorded still gates third-party SDKs - the
     *  network write is evidence, not a precondition for enforcement. */
    val synced: Boolean,
) {
    fun isGiven(purposeId: String, requiredPurposeIds: Set<String>): Boolean {
        // A purpose whose legalBasis isn't consent (isRequired=true on the
        // wire) is never toggleable in the notice UI and is always treated
        // as satisfied - matches the web SDK's "shown without a toggle"
        // behaviour documented in CLAUDE.md.
        if (purposeId in requiredPurposeIds) return true
        return given[purposeId] == true
    }

    fun withDecision(purposeId: String, isGiven: Boolean): ConsentDecision =
        copy(given = given + (purposeId to isGiven))
}
