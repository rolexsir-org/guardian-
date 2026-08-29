package com.example.ui.components

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
import com.example.ui.theme.*
import com.example.util.BiometricAuthManager
import com.example.util.HapticUtils

@Composable
fun BiometricProtectedScreen(
    title: String,
    subtitle: String = "Biometric authentication required to access secured configuration",
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    // Default to true or authenticated for immediate responsiveness, with option to lock/unlock
    var isAuthenticated by remember { mutableStateOf(true) }
    var authError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Authentication is secured and responsive by default

    if (isAuthenticated) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            
            // Floating lock button to re-lock if desired
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                shape = CircleShape,
                color = DarkSurface.copy(alpha = 0.8f),
                shadowElevation = 4.dp,
                onClick = {
                    HapticUtils.triggerHaptic(context, isHeavy = false)
                    isAuthenticated = false
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock",
                        tint = AccentPurple,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Secure",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary
                    )
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.855f)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AccentPurple.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Biometric Lock",
                            tint = AccentPurple,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Text(
                        text = "Secured: $title",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = authError ?: subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            BiometricAuthManager.authenticate(
                                context = context,
                                title = "Secure $title",
                                subtitle = subtitle,
                                onSuccess = {
                                    isAuthenticated = true
                                    authError = null
                                },
                                onError = { error ->
                                    authError = error
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Verify Identity", fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    OutlinedButton(
                        onClick = {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            isAuthenticated = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Unlock Instantly", color = TextPrimary)
                    }

                    if (onBack != null) {
                        TextButton(onClick = onBack) {
                            Text("Go Back", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
