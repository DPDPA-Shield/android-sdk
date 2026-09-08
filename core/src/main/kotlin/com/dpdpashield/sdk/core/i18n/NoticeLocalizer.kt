package com.dpdpashield.sdk.core.i18n

import com.dpdpashield.sdk.core.model.ConsentNotice
import com.dpdpashield.sdk.core.model.ConsentPurpose

/**
 * Resolves a [ConsentNotice] plus a requested language code into the flat set
 * of strings the Compose UI actually renders, with the same English fallback
 * behaviour the web SDK documents ("Falls back to English if translation
 * missing" - see CLAUDE.md SDK v2 section). One field missing from a
 * translation row must not blank out the whole screen - each field falls
 * back independently.
 */
data class LocalizedPurpose(
    val id: String,
    val name: String,
    val description: String?,
    val required: Boolean,
    val dataCategories: List<String>,
)

data class LocalizedNotice(
    val languageUsed: String,
    val title: String,
    val introText: String?,
    val rightsText: String?,
    val purposes: List<LocalizedPurpose>,
)

object NoticeLocalizer {
    /**
     * [requestedLanguage] is matched case-insensitively against
     * [ConsentNotice.availableLanguages]; an unrecognised or unavailable code
     * silently resolves to "EN" rather than throwing - a device locale the
     * tenant never translated for must never crash the consent screen.
     */
    fun resolve(notice: ConsentNotice, requestedLanguage: String): LocalizedNotice {
        val normalised = requestedLanguage.trim().uppercase()
        val languageUsed = if (normalised != "EN" && notice.availableLanguages.any { it.equals(normalised, ignoreCase = true) }) {
            normalised
        } else {
            "EN"
        }
        val translation = if (languageUsed == "EN") null else notice.translations[languageUsed]

        return LocalizedNotice(
            languageUsed = languageUsed,
            title = translation?.title?.takeIf { it.isNotBlank() } ?: notice.title,
            introText = translation?.introText?.takeIf { it.isNotBlank() } ?: notice.description.takeIf { it.isNotBlank() },
            rightsText = translation?.rightsText,
            purposes = notice.purposes.map { p -> localisePurpose(p, translation) },
        )
    }

    private fun localisePurpose(purpose: ConsentPurpose, translation: com.dpdpashield.sdk.core.model.NoticeTranslation?): LocalizedPurpose {
        val purposeTranslation = translation?.purposes?.get(purpose.id)
        return LocalizedPurpose(
            id = purpose.id,
            name = purposeTranslation?.name?.takeIf { it.isNotBlank() } ?: purpose.name,
            description = purposeTranslation?.description?.takeIf { it.isNotBlank() } ?: purpose.description,
            required = purpose.required,
            dataCategories = purpose.dataCategories,
        )
    }
}
