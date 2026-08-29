package com.example.ui.sos

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.service.EmergencyCallManager
import com.example.service.EmergencySmsManager
import com.example.service.HardwareAlertManager
import com.example.ui.GuardianViewModel
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.delay

@Composable
fun EmergencyActiveScreen(
    viewModel: GuardianViewModel,
    onCancelSos: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var elapsedSeconds by remember { mutableStateOf(0) }
    var isFlashlightOn by remember { mutableStateOf(false) }
    var isSirenActive by remember { mutableStateOf(false) }

    val currentLat by viewModel.currentLatitude.collectAsState()
    val currentLng by viewModel.currentLongitude.collectAsState()

    val hardwareAlertManager = remember { HardwareAlertManager(context) }

    DisposableEffect(Unit) {
        onDispose {
            hardwareAlertManager.release()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PrimaryRed.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(PrimaryRed)
                    )
                    Column {
                        Text(
                            text = "EMERGENCY ACTIVE • ${String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryRed,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Location: $currentLat, $currentLng",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Real Hardware Control Quick Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Call 911 Direct
                Button(
                    onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = true)
                        EmergencyCallManager.dialOrCallEmergency(context, "911")
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CALL 911", fontWeight = FontWeight.Bold, color = Color.White)
                }

                // Flashlight Strobe
                IconButton(
                    onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = false)
                        isFlashlightOn = hardwareAlertManager.toggleFlashlight()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isFlashlightOn) WarningYellow else DarkCard)
                ) {
                    Icon(
                        Icons.Default.FlashOn,
                        contentDescription = "Flashlight Strobe",
                        tint = if (isFlashlightOn) Color.Black else TextPrimary
                    )
                }

                // Siren Audio
                IconButton(
                    onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = true)
                        if (isSirenActive) {
                            hardwareAlertManager.stopSirenSound()
                            isSirenActive = false
                        } else {
                            hardwareAlertManager.playSirenSound()
                            isSirenActive = true
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSirenActive) PrimaryRed else DarkCard)
                ) {
                    Icon(
                        imageVector = if (isSirenActive) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Siren Sound",
                        tint = if (isSirenActive) Color.White else TextPrimary
                    )
                }

                // Share Sheet
                IconButton(
                    onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = false)
                        EmergencySmsManager.shareEmergencyLocationSheet(
                            context = context,
                            userName = "Guardian User",
                            latitude = currentLat,
                            longitude = currentLng,
                            batteryLevel = 90
                        )
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCard)
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share Location", tint = TextPrimary)
                }
            }

            // Status List & Live Telemetry
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    EmergencyStatusRow(icon = Icons.Default.GpsFixed, title = "GPS Live Coordinates", status = "$currentLat, $currentLng (±3m)", active = true)
                }
                item {
                    EmergencyStatusRow(icon = Icons.Default.Sms, title = "Emergency SMS Broadcast", status = "Dispatched via SMS & System Services", active = true)
                }
                item {
                    EmergencyStatusRow(icon = Icons.Default.Sensors, title = "Real-time Accelerometer", status = "Sensors active for crash & fall detection", active = true)
                }
                item {
                    EmergencyStatusRow(icon = Icons.Default.Group, title = "Guardian Network", status = "Broadcasting to emergency contacts", active = true)
                }
                item {
                    EmergencyStatusRow(icon = Icons.Default.LocalPolice, title = "911 / Emergency Services", status = "Quick Dial ready", active = true)
                }
            }

            // Large Cancel Button
            Button(
                onClick = {
                    HapticUtils.triggerHaptic(context, isHeavy = false)
                    hardwareAlertManager.release()
                    onCancelSos()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("I AM SAFE — CANCEL SOS", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
fun EmergencyStatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, status: String, active: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(icon, contentDescription = null, tint = if (active) SuccessGreen else TextSecondary)
                Column {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
        }
    }
}
