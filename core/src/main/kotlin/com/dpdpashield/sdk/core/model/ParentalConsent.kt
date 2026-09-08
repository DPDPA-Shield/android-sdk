package com.dpdpashield.sdk.core.model

/**
 * Result of POST /children/age-gate/verify.
 *
 * When [isMinor] is true, the caller must collect guardian details and call
 * [ParentalConsentClient.initiateParentalConsent] before proceeding.
 */
data class AgeGateResult(
    val sessionId: String,
    val isMinor: Boolean,
    val age: Int,
    val requiresParentalConsent: Boolean,
)

/**
 * Result of POST /children/parental-consent/initiate.
 *
 * The OTP has been sent to [maskedGuardianEmail]. Present [ParentalConsentScreen]
 * so the guardian can enter it.
 */
data class ParentalConsentInitiated(
    val consentId: String,
    val maskedGuardianEmail: String,
    val expiresAt: String,
)

/**
 * Result of POST /children/parental-consent/verify.
 *
 * Consent is now recorded. [childAccountId] identifies the created ChildAccount
 * with default processing restrictions applied (AD_TARGETING, PROFILING,
 * DATA_SHARING, BEHAVIORAL_TRACKING blocked).
 */
data class ParentalConsentVerified(
    val success: Boolean,
    val childAccountId: String,
    val restrictions: List<String>,
)
