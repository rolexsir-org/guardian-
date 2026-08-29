package com.example.ui.sos

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.GuardianViewModel

@Composable
fun SosScreen(viewModel: GuardianViewModel, onNavigateToCommunityResponders: () -> Unit = {}) {
    val context = LocalContext.current
    val isSosActive by viewModel.isSosActive.collectAsState()
    val aiRecommendation by viewModel.aiRecommendation.collectAsState()
    val unusualMovement by viewModel.unusualMovementDetected.collectAsState()
    val isVoiceEnabled by viewModel.isVoiceActivationEnabled.collectAsState()

    // Multi-layer pulse animation for the emergency SOS button
    val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSosActive) 400 else 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val auraScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSosActive) 600 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auraScale"
    )
    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSosActive) 600 else 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auraAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (isSosActive) listOf(Color(0xFF8B0000), Color(0xFF330000))
                    else listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant)
                )
            )
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isSosActive) "EMERGENCY ACTIVE" else "Guardian SOS",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isSosActive) Color.White else MaterialTheme.colorScheme.error
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isSosActive) "Broadcasting SOS alert & sounding alarm..." else "Tap and hold or press SOS to alert emergency services & trusted contacts.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSosActive) Color(0xFFFFCCCC) else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Giant SOS Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(260.dp)
        ) {
            // Radiating outward aura wave ring
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .scale(auraScale)
                    .background(
                        (if (isSosActive) Color.White else MaterialTheme.colorScheme.error).copy(alpha = auraAlpha),
                        CircleShape
                    )
            )

            // Outer glowing ring
            Box(
                modifier = Modifier
                    .size(230.dp)
                    .scale(scale)
                    .background(
                        (if (isSosActive) Color.Red else MaterialTheme.colorScheme.error).copy(alpha = 0.25f),
                        CircleShape
                    )
            )

            Button(
                onClick = {
                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = true)
                    viewModel.triggerSos()
                },
                modifier = Modifier
                    .size(200.dp)
                    .scale(if (isSosActive) scale else 1f),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSosActive) Color.White else MaterialTheme.colorScheme.error
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "SOS Icon",
                        tint = if (isSosActive) MaterialTheme.colorScheme.error else Color.White,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isSosActive) "ACTIVE" else "SOS",
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                        color = if (isSosActive) MaterialTheme.colorScheme.error else Color.White
                    )
                }
            }
        }

        if (isSosActive) {
            Button(
                onClick = {
                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                    viewModel.cancelSos()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                modifier = Modifier.fillMaxWidth(0.8f).height(50.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Cancel Emergency Alert", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // Quick Emergency Dialers
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Tactical Safety Recommendation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(imageVector = Icons.Default.Security, contentDescription = "Safety", tint = MaterialTheme.colorScheme.primary)
                            Text(text = "Guardian Safety Recommendation", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        TextButton(onClick = {
                            com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                            viewModel.refreshAiRecommendation()
                        }) {
                            Text("Refresh")
                        }
                    }
                    Text(
                        text = aiRecommendation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Tactical Triggers: Voice SOS & Movement Anomaly
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = true)
                        viewModel.triggerVoiceSos()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Mic, contentDescription = "Voice SOS", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Voice SOS", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = true)
                        viewModel.simulateUnusualMovement()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.DirectionsRun, contentDescription = "Simulate Fall", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Simulate Fall", fontSize = 12.sp)
                }
            }

            // Continuous Voice Activation & Community Responders Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Continuous Voice SOS", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                Text("Detects phrases: 'Help me', 'SOS', 'Emergency'", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                        }
                        Switch(
                            checked = isVoiceEnabled,
                            onCheckedChange = {
                                com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                                viewModel.toggleVoiceActivation(it, context)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                            onNavigateToCommunityResponders()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Community Responders (10 km)", fontSize = 12.sp)
                    }
                }
            }

            Text(
                text = "Quick Emergency Dispatch",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EmergencyDialButton(
                    title = "Call 911",
                    subtitle = "Police / Fire / Medical",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                ) {
                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = true)
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))
                    context.startActivity(intent)
                }

                EmergencyDialButton(
                    title = "Local Security",
                    subtitle = "Campus / Patrol",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                ) {
                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:5550199"))
                    context.startActivity(intent)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun EmergencyDialButton(
    title: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(imageVector = Icons.Default.Call, contentDescription = title, tint = color)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, fontWeight = FontWeight.Bold, color = color, fontSize = 16.sp)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
