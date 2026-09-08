package com.dpdpashield.sdk.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dpdpashield.sdk.core.i18n.LocalizedNotice
import com.dpdpashield.sdk.core.i18n.LocalizedPurpose

/**
 * Native Compose consent screen.
 *
 * Renders the tenant's published notice — purposes, toggles, rights text — with
 * full white-label branding support: logo, primary colour, support email, and an
 * optional "Powered by DPDPA Shield" attribution badge.
 *
 * Pass a [ShieldConsentTheme] built from the loaded [com.dpdpashield.sdk.core.model.ConsentNotice]:
 * ```kotlin
 * val theme = ShieldConsentTheme(
 *     primaryColor = hexToComposeColor(notice.primaryColor),
 *     logo         = { AsyncImage(model = notice.logoUrl, ...) },
 *     showPoweredBy = notice.brandName == null,
 * )
 * ConsentScreen(
 *     notice       = shield.localize(),
 *     contactEmail = notice.contactEmail,
 *     brandName    = notice.brandName,
 *     theme        = theme,
 *     onAcceptAll  = { given -> shield.recordDecision(identifier, given, "EN") },
 *     onRejectAll  = { given -> shield.recordDecision(identifier, given, "EN") },
 *     onSave       = { given -> shield.recordDecision(identifier, given, "EN") },
 * )
 * ```
 */
@Composable
fun ConsentScreen(
    notice: LocalizedNotice,
    onAcceptAll: (Map<String, Boolean>) -> Unit,
    onRejectAll: (Map<String, Boolean>) -> Unit,
    onSave: (Map<String, Boolean>) -> Unit,
    modifier: Modifier = Modifier,
    initialDecision: Map<String, Boolean> = emptyMap(),
    theme: ShieldConsentTheme = ShieldConsentTheme(),
    /** From ConsentNotice.contactEmail — shown as a "Contact us" link at the bottom. */
    contactEmail: String? = null,
    /** From ConsentNotice.brandName — null means DPDPA Shield is the visible brand. */
    brandName: String? = null,
) {
    val primary = theme.primaryColor ?: MaterialTheme.colorScheme.primary

    val toggleablePurposes = remember(notice) { notice.purposes.filter { !it.required } }
    val requiredPurposes   = remember(notice) { notice.purposes.filter { it.required } }

    val decisions = remember(notice) {
        mutableStateMapOf<String, Boolean>().apply {
            toggleablePurposes.forEach { put(it.id, initialDecision[it.id] ?: false) }
        }
    }

    Surface(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Logo ─────────────────────────────────────────────────────────
            // White-label tenants → their logo via theme.logo lambda.
            // Everyone else    → DPDPA Shield logo from CDN.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (theme.logo != null) {
                    theme.logo.invoke()
                } else {
                    AsyncImage(
                        model              = DPDPA_SHIELD_LOGO_URL,
                        contentDescription = "DPDPA Shield",
                        modifier           = Modifier.height(40.dp),
                    )
                }
            }

            // ── Notice title + intro ─────────────────────────────────────────
            Text(
                text = notice.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            notice.introText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Purposes ─────────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(requiredPurposes) { purpose ->
                    RequiredPurposeRow(purpose)
                }
                items(toggleablePurposes) { purpose ->
                    TogglePurposeRow(
                        purpose = purpose,
                        checked = decisions[purpose.id] == true,
                        onCheckedChange = { decisions[purpose.id] = it },
                        checkedThumbColor = primary,
                    )
                }
            }

            // ── Rights text ─────────────────────────────────────────────────
            notice.rightsText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            // ── Action buttons ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onRejectAll(toggleablePurposes.associate { it.id to false }) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = primary),
                ) { Text("Reject All") }

                Button(
                    onClick = { onAcceptAll(toggleablePurposes.associate { it.id to true }) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = primary),
                ) { Text("Accept All") }
            }

            Button(
                onClick = { onSave(decisions.toMap()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primary),
            ) { Text("Save My Choices") }

            // ── Footer: contact email + powered-by ───────────────────────────
            if (contactEmail != null || theme.showPoweredBy) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (contactEmail != null) {
                        Text(
                            text = "Questions? $contactEmail",
                            style = MaterialTheme.typography.bodySmall,
                            color = primary,
                        )
                    }
                    if (theme.showPoweredBy) {
                        Text(
                            text = if (brandName != null) "Powered by $brandName" else "Powered by DPDPA Shield",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun TogglePurposeRow(
    purpose: LocalizedPurpose,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    checkedThumbColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = purpose.name, style = MaterialTheme.typography.titleSmall)
            purpose.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = checkedThumbColor,
                checkedTrackColor = checkedThumbColor.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun RequiredPurposeRow(purpose: LocalizedPurpose) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = purpose.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "  · required",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        purpose.description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
