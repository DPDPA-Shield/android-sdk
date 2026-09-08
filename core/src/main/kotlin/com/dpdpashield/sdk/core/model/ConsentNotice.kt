package com.dpdpashield.sdk.core.model

/**
 * Mirrors the `data` payload of `GET /api/v1/consent/public-notice`
 * (apps/api/src/modules/consent/consent.routes.ts). Field names and nullability
 * are copied verbatim from the route handler - do not rename anything here
 * without checking the backend route first, and vice versa.
 */
data class ConsentNotice(
    val id: String,
    val title: String,
    val description: String,
    val privacyPolicyUrl: String?,
    /** DPDPA Rule 3 disclosure fields - see CLAUDE.md "Rule 3 disclosures". */
    val contactEmail: String?,
    val complaintText: String?,
    val withdrawalUrl: String?,
    val purposes: List<ConsentPurpose>,
    /** Keyed by uppercase language code, e.g. "HI", "TA". English is never a key here. */
    val translations: Map<String, NoticeTranslation>,
    /** "EN" first, then whatever languages actually have a translation row. */
    val availableLanguages: List<String>,
    /**
     * White-label brand name. Server has already resolved whiteLabelEnabled +
     * whiteLabelConfig - the SDK must never see or reason about plan/entitlement
     * data, only this resolved string. null -> render "Powered by DPDPA Shield".
     */
    val brandName: String?,
    /**
     * White-label logo URL (stable, served via api.dpdpashield.in). Only non-null
     * when the tenant has white-label enabled and has uploaded a logo. The SDK
     * renders it at the top of the consent screen when present.
     */
    val logoUrl: String? = null,
    /**
     * White-label primary hex colour, e.g. "#3B4BDB". Only non-null for white-label
     * tenants. The SDK uses this for buttons and interactive elements. Validate with
     * a regex or Color.parseColor before applying - never trust the raw DB value into
     * a style/theme without parsing.
     */
    val primaryColor: String? = null,
)

data class ConsentPurpose(
    val id: String,
    val name: String,
    val description: String?,
    val required: Boolean,
    val dataCategories: List<String>,
)

/**
 * One language's translation bundle for the notice-level strings plus a map of
 * per-purpose translations keyed by purpose id. A translation entry may be
 * partial (e.g. only `purposes` populated, no `title`) exactly as the backend
 * builds it - see consent.routes.ts's translationsMap construction, which
 * merges notice-level and purpose-level translation rows independently.
 */
data class NoticeTranslation(
    val title: String? = null,
    val introText: String? = null,
    val rightsText: String? = null,
    val purposes: Map<String, PurposeTranslation> = emptyMap(),
)

data class PurposeTranslation(
    val name: String?,
    val description: String?,
)
