package com.dpdpashield.sdk.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Stable public CDN URL for the DPDPA Shield logo (192×192 PNG). */
const val DPDPA_SHIELD_LOGO_URL = "https://dpdpashield.in/logo.png"

/**
 * Visual theming for [ConsentScreen] and [ParentalConsentScreen].
 *
 * The host app builds this from the [com.dpdpashield.sdk.core.model.ConsentNotice]
 * it loaded — the notice already carries the tenant's resolved white-label
 * values (logo URL, primary colour, brand name). Example:
 *
 * ```kotlin
 * val notice = shield.loadNotice().let { (it as ShieldApiResult.Success).value }
 * val theme = ShieldConsentTheme(
 *     primaryColor  = hexToComposeColor(notice.primaryColor),
 *     logo          = {
 *         AsyncImage(   // Coil — add io.coil-kt:coil-compose to your app's deps
 *             model          = notice.logoUrl,
 *             contentDescription = notice.brandName ?: "Logo",
 *             modifier       = Modifier.height(40.dp),
 *         )
 *     },
 * )
 * ConsentScreen(notice = localized, theme = theme, ...)
 * ```
 *
 * If [logo] is null the screen shows no logo (appropriate when [ConsentNotice.logoUrl]
 * is null, i.e. the tenant has not enabled white-labelling or has not uploaded a logo).
 * If [primaryColor] is null all interactive elements use the host app's
 * `MaterialTheme.colorScheme.primary`.
 */
data class ShieldConsentTheme(
    /**
     * Composable lambda that renders the tenant logo. Intentionally untyped —
     * the host app chooses how to load and render it (Coil, Glide, BitmapFactory,
     * a resource drawable, etc.). The SDK imposes no image-loading dependency.
     * Pass null when no logo is available.
     */
    val logo: (@Composable () -> Unit)? = null,

    /**
     * Primary brand colour for buttons and highlights. Parse from
     * [com.dpdpashield.sdk.core.model.ConsentNotice.primaryColor] using
     * [hexToComposeColor]. Null falls back to the host app's MaterialTheme primary.
     */
    val primaryColor: Color? = null,

    /**
     * Whether to render the "Powered by DPDPA Shield" attribution line at the
     * bottom of the screen. Always true unless the tenant has white-label enabled
     * (in which case [ConsentNotice.brandName] is non-null — conventionally pass
     * false when brandName is non-null).
     */
    val showPoweredBy: Boolean = true,
)

/**
 * Safely parses a CSS hex colour string (e.g. "#3B4BDB" or "3B4BDB") to a
 * Compose [Color]. Returns null for null input or any unparseable string —
 * callers fall back to the theme default rather than crashing.
 */
fun hexToComposeColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val normalised = if (hex.startsWith('#')) hex else "#$hex"
    return runCatching {
        Color(android.graphics.Color.parseColor(normalised))
    }.getOrNull()
}
