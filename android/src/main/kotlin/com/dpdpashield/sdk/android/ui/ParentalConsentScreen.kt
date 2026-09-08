package com.dpdpashield.sdk.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import coil.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dpdpashield.sdk.android.ShieldConsentManager
import com.dpdpashield.sdk.core.model.ParentalConsentVerified
import com.dpdpashield.sdk.core.net.ShieldApiResult
import kotlinx.coroutines.launch

/**
 * Native Compose screen for the DPDPA Section 9 parental consent flow.
 *
 * Three internal steps rendered in a single composable — the host app does not
 * need to manage navigation between them:
 *
 *   Step 1 — AgeGate: child enters their date of birth (or the app pre-fills it
 *             if it already knows the user's DOB).
 *   Step 2 — GuardianEmail: collect the guardian's email address to send the OTP.
 *   Step 3 — OtpEntry: guardian enters the 6-digit OTP from their email.
 *
 * Usage:
 * ```kotlin
 * ParentalConsentScreen(
 *     manager    = (application as MyApp).shield,
 *     sessionId  = uuid,                    // stable ID for this age-gate session
 *     orgName    = "Acme App",              // shown in the UI
 *     onVerified = { result -> proceed() },
 *     onCancel   = { finish() },
 * )
 * ```
 *
 * The screen is deliberately NOT wired to any navigation library — drop it
 * inside whatever dialog, bottom sheet, or full-screen composable your app uses.
 *
 * For a pre-login / anonymous user [sessionId] should be a UUID generated once
 * per install (e.g. stored in EncryptedSharedPreferences) so the backend can
 * correlate the age-gate with the eventual parental consent record.
 */
/**
 * Recommended way to pass [orgName]: read it from the loaded notice.
 * ```kotlin
 * val orgName = shield.localize()?.brandName
 *     ?: shield.loadNotice().let { (it as? ShieldApiResult.Success)?.value?.brandName }
 *     ?: "the organisation"
 * ```
 */
@Composable
fun ParentalConsentScreen(
    manager: ShieldConsentManager,
    sessionId: String,
    orgName: String,
    onVerified: (ParentalConsentVerified) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialDob: String = "",
    theme: ShieldConsentTheme = ShieldConsentTheme(),
) {
    val primary = theme.primaryColor ?: MaterialTheme.colorScheme.primary
    var step by remember { mutableStateOf(if (initialDob.isNotBlank()) Step.GUARDIAN_EMAIL else Step.AGE_GATE) }
    var dob by remember { mutableStateOf(initialDob) }
    var guardianEmail by remember { mutableStateOf("") }
    var consentId by remember { mutableStateOf("") }
    var maskedEmail by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Surface(modifier = modifier) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Logo ─────────────────────────────────────────────────────────
            Box(
                modifier = Modifier.fillMaxWidth(),
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

            // ── Header ──────────────────────────────────────────────────────
            Text(
                text = when (step) {
                    Step.AGE_GATE      -> "Verify your age"
                    Step.GUARDIAN_EMAIL -> "Parental consent required"
                    Step.OTP_ENTRY     -> "Enter the OTP"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when (step) {
                    Step.AGE_GATE ->
                        "$orgName uses your date of birth to determine whether parental consent is required under India's DPDPA 2023 (Section 9)."
                    Step.GUARDIAN_EMAIL ->
                        "You appear to be under 18. $orgName needs a parent or guardian to consent on your behalf. Please enter their email address."
                    Step.OTP_ENTRY ->
                        "A one-time password has been sent to $maskedEmail. Ask your guardian to check their inbox and share the 6-digit code."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // ── Step content ────────────────────────────────────────────────
            when (step) {
                Step.AGE_GATE -> AgeGateStep(
                    dob = dob,
                    onDobChange = { dob = it; errorMsg = null },
                )
                Step.GUARDIAN_EMAIL -> GuardianEmailStep(
                    guardianEmail = guardianEmail,
                    onEmailChange = { guardianEmail = it; errorMsg = null },
                )
                Step.OTP_ENTRY -> OtpEntryStep(
                    otp = otp,
                    maskedEmail = maskedEmail,
                    onOtpChange = { otp = it; errorMsg = null },
                )
            }

            // ── Error ────────────────────────────────────────────────────────
            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))

            // ── Action buttons ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    if (step == Step.AGE_GATE) onCancel()
                    else step = step.previous()
                }) {
                    Text(if (step == Step.AGE_GATE) "Cancel" else "Back")
                }

                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = primary),
                    onClick = {
                        scope.launch {
                            loading = true
                            errorMsg = null
                            when (step) {
                                Step.AGE_GATE -> {
                                    if (dob.isBlank()) {
                                        errorMsg = "Please enter your date of birth."
                                        loading = false
                                        return@launch
                                    }
                                    when (val result = manager.verifyAge(dob, sessionId)) {
                                        is ShieldApiResult.Success -> {
                                            if (!result.value.isMinor) {
                                                // Adult — no parental consent needed. The
                                                // caller should close this screen and
                                                // proceed with the normal consent flow.
                                                onCancel()
                                            } else {
                                                step = Step.GUARDIAN_EMAIL
                                            }
                                        }
                                        is ShieldApiResult.Failure -> {
                                            errorMsg = result.error?.message ?: "Age verification failed. Please try again."
                                        }
                                    }
                                }
                                Step.GUARDIAN_EMAIL -> {
                                    if (guardianEmail.isBlank() || !guardianEmail.contains('@')) {
                                        errorMsg = "Please enter a valid guardian email address."
                                        loading = false
                                        return@launch
                                    }
                                    when (val result = manager.initiateParentalConsent(sessionId, guardianEmail)) {
                                        is ShieldApiResult.Success -> {
                                            consentId = result.value.consentId
                                            maskedEmail = result.value.maskedGuardianEmail
                                            step = Step.OTP_ENTRY
                                        }
                                        is ShieldApiResult.Failure -> {
                                            errorMsg = result.error?.message ?: "Could not send OTP. Please check the email address."
                                        }
                                    }
                                }
                                Step.OTP_ENTRY -> {
                                    val trimmedOtp = otp.trim()
                                    if (trimmedOtp.length != 6 || trimmedOtp.any { !it.isDigit() }) {
                                        errorMsg = "Please enter the 6-digit code from the email."
                                        loading = false
                                        return@launch
                                    }
                                    when (val result = manager.verifyParentalConsent(consentId, trimmedOtp)) {
                                        is ShieldApiResult.Success -> onVerified(result.value)
                                        is ShieldApiResult.Failure -> {
                                            errorMsg = result.error?.message ?: "Invalid or expired OTP. Please try again."
                                        }
                                    }
                                }
                            }
                            loading = false
                        }
                    },
                    enabled = !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when (step) {
                            Step.AGE_GATE       -> "Continue"
                            Step.GUARDIAN_EMAIL -> "Send OTP"
                            Step.OTP_ENTRY      -> "Confirm consent"
                        }
                    )
                }
            }
        }
    }
}

// ── Step sub-composables ──────────────────────────────────────────────────────

@Composable
private fun AgeGateStep(dob: String, onDobChange: (String) -> Unit) {
    OutlinedTextField(
        value = dob,
        onValueChange = onDobChange,
        label = { Text("Date of birth (YYYY-MM-DD)") },
        placeholder = { Text("e.g. 2010-06-15") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GuardianEmailStep(guardianEmail: String, onEmailChange: (String) -> Unit) {
    OutlinedTextField(
        value = guardianEmail,
        onValueChange = onEmailChange,
        label = { Text("Guardian's email address") },
        placeholder = { Text("parent@example.com") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "An OTP will be sent to this address. The guardian must enter it in the next step to give consent.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OtpEntryStep(otp: String, maskedEmail: String, onOtpChange: (String) -> Unit) {
    Text(
        text = "OTP sent to $maskedEmail",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = otp,
        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) onOtpChange(it) },
        label = { Text("6-digit OTP") },
        placeholder = { Text("______") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(4.dp))
    Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "OTP expires in 24 hours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Step enum ─────────────────────────────────────────────────────────────────

private enum class Step {
    AGE_GATE, GUARDIAN_EMAIL, OTP_ENTRY;

    fun previous(): Step = when (this) {
        AGE_GATE      -> AGE_GATE
        GUARDIAN_EMAIL -> AGE_GATE
        OTP_ENTRY     -> GUARDIAN_EMAIL
    }
}
