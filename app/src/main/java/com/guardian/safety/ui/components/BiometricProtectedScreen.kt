package com.guardian.safety.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.guardian.safety.ui.theme.*
import com.guardian.safety.util.BiometricAuthManager
import com.guardian.safety.util.BiometricOutcome
import com.guardian.safety.util.HapticUtils

/**
 * Gate for protected configuration screens.
 *
 * The screen starts **closed** and only opens after the platform confirms the user.
 * There is no "unlock instantly" shortcut and no auto-success path: a device without
 * an enrolled biometric or screen lock shows an explanation instead of the content.
 */
@Composable
fun BiometricProtectedScreen(
    title: String,
    subtitle: String = "Confirm it is you to open secured configuration",
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var isAuthenticated by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var unavailableReason by remember { mutableStateOf<String?>(null) }
    var promptRequested by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        when (val availability = BiometricAuthManager.canAuthenticate(context)) {
            is BiometricOutcome.NotEnrolled -> unavailableReason = availability.message
            else -> {
                promptRequested = true
                BiometricAuthManager.authenticate(context, title = "Secure $title", subtitle = subtitle) { outcome ->
                    when (outcome) {
                        BiometricOutcome.Authenticated -> {
                            isAuthenticated = true
                            authError = null
                        }
                        is BiometricOutcome.NotEnrolled -> unavailableReason = outcome.message
                        is BiometricOutcome.Rejected -> authError = outcome.message
                    }
                }
            }
        }
    }

    if (isAuthenticated) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                shape = CircleShape,
                color = DarkSurface.copy(alpha = 0.85f),
                shadowElevation = 4.dp,
                onClick = {
                    HapticUtils.triggerHaptic(context, isHeavy = false)
                    isAuthenticated = false
                    authError = null
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock this screen",
                        tint = AccentPurple,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("Lock", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(AccentPurple.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(40.dp),
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = unavailableReason ?: authError ?: subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unavailableReason != null || authError != null) PrimaryRed else TextSecondary,
                    textAlign = TextAlign.Center,
                )

                if (unavailableReason == null) {
                    Button(
                        onClick = {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            promptRequested = true
                            BiometricAuthManager.authenticate(
                                context = context,
                                title = "Secure $title",
                                subtitle = subtitle,
                            ) { outcome ->
                                when (outcome) {
                                    BiometricOutcome.Authenticated -> {
                                        isAuthenticated = true
                                        authError = null
                                    }
                                    is BiometricOutcome.NotEnrolled -> unavailableReason = outcome.message
                                    is BiometricOutcome.Rejected -> authError = outcome.message
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (promptRequested) "Try again" else "Unlock with device lock",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }

                if (onBack != null) {
                    TextButton(onClick = onBack) {
                        Text("Go back", color = TextSecondary)
                    }
                }
            }
        }
    }
}
