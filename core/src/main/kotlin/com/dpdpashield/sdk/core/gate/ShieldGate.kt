package com.dpdpashield.sdk.core.gate

import com.dpdpashield.sdk.core.model.ConsentDecision
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The enforcement point every competitor SDK's docs describe as a suggestion
 * ("call your SDK init only after consent") rather than something the CMP
 * itself enforces. `ShieldGate` is the one place a developer actually asks
 * "may I run this?" instead of being trusted to remember to check.
 *
 * Fail-closed by construction: a purpose with no recorded decision reads as
 * NOT consented (see [ConsentDecision.isGiven]) - a tracker gated on a
 * purpose the user was never even asked about does not run. This is the
 * opposite default of an ad SDK that assumes consent until told otherwise.
 *
 * `requiredPurposeIds` is threaded through from the loaded [ConsentNotice]'s
 * purposes (`required == true`) so that a legal-basis purpose which is never
 * shown as a toggle in the UI (see NoticeLocalizer / the web SDK's identical
 * "shown without a toggle" behaviour) doesn't read as permanently gated off
 * just because it has no per-purpose entry in `given`.
 */
class ShieldGate(private var requiredPurposeIds: Set<String> = emptySet()) {
    @Volatile
    private var decision: ConsentDecision? = null

    private val listeners = CopyOnWriteArrayList<(ConsentDecision) -> Unit>()

    /** Called once when the notice loads, before any decision exists. */
    fun setRequiredPurposeIds(ids: Set<String>) {
        requiredPurposeIds = ids
    }

    fun updateDecision(newDecision: ConsentDecision) {
        decision = newDecision
        listeners.forEach { it(newDecision) }
    }

    fun currentDecision(): ConsentDecision? = decision

    fun isConsented(purposeId: String): Boolean {
        val d = decision ?: return purposeId in requiredPurposeIds
        return d.isGiven(purposeId, requiredPurposeIds)
    }

    /**
     * Runs [block] only if [purposeId] is currently consented. Returns
     * whether it ran, so callers that need to branch (e.g. "show a
     * consent-required placeholder instead") don't have to duplicate the
     * [isConsented] check.
     */
    inline fun runIfConsented(purposeId: String, block: () -> Unit): Boolean {
        if (!isConsented(purposeId)) return false
        block()
        return true
    }

    /**
     * Re-evaluates [block] every time the decision changes, immediately for
     * the current state and again on every future [updateDecision] call -
     * for a tracker init that should retroactively fire the moment a user
     * grants a purpose they'd previously declined, without the host app
     * having to re-poll `isConsented` itself.
     */
    fun observeConsented(purposeId: String, block: (Boolean) -> Unit) {
        block(isConsented(purposeId))
        listeners.add { block(isConsented(purposeId)) }
    }
}
