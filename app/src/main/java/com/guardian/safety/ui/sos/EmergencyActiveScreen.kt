package com.guardian.safety.ui.sos

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guardian.safety.service.EmergencyActionResult
import com.guardian.safety.service.EmergencyCallManager
import com.guardian.safety.service.EmergencyNumbers
import com.guardian.safety.service.EmergencySmsManager
import com.guardian.safety.service.HardwareAlertManager
import com.guardian.safety.service.SmsStatus
import com.guardian.safety.service.SosStage
import com.guardian.safety.ui.GuardianViewModel
import com.guardian.safety.ui.LocationUiState
import com.guardian.safety.ui.theme.*
import com.guardian.safety.util.HapticUtils
import kotlinx.coroutines.delay

/**
 * Full-screen emergency surface.
 *
 * Every status row reflects what actually happened: a dispatched call, an SMS the
 * network accepted, an alert the server confirmed — or the honest "still trying"
 * state. Nothing is reported as successful merely because the app intended it.
 */
@Composable
fun EmergencyActiveScreen(
    viewModel: GuardianViewModel,
    onCancelSos: () -> Unit,
) {
    val context = LocalContext.current
    var elapsedSeconds by remember { mutableStateOf(0) }
    var isFlashlightOn by remember { mutableStateOf(false) }
    var isSirenActive by remember { mutableStateOf(false) }
    var cancelError by remember { mutableStateOf<String?>(null) }

    val locationState by viewModel.locationState.collectAsState()
    val sosState by viewModel.sosActive.collectAsState()
    val result by viewModel.lastSosResult.collectAsState()
    val deliveries by viewModel.smsDeliveries.collectAsState()

    val fix = locationState as? LocationUiState.Available
    val hardwareAlertManager = remember { HardwareAlertManager(context) }
    val emergencyNumber = remember(context) { EmergencyNumbers.primary(context) }

    DisposableEffect(Unit) {
        onDispose { hardwareAlertManager.release() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            elapsedSeconds++
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PrimaryRed.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(PrimaryRed),
                    )
                    Column {
                        Text(
                            text = "EMERGENCY ACTIVE • " +
                                String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60),
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryRed,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (fix != null) {
                                "Location: ${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}" +
                                    (fix.accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
                            } else {
                                viewModel.locationFailureMessage()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = true)
                        when (val outcome = EmergencyCallManager.callNumber(context, emergencyNumber)) {
                            is EmergencyActionResult.Failed -> cancelError = outcome.message
                            else -> cancelError = null
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CALL $emergencyNumber", fontWeight = FontWeight.Bold, color = Color.White)
                }

                IconButton(
                    onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = false)
                        isFlashlightOn = hardwareAlertManager.toggleFlashlight()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isFlashlightOn) WarningYellow else DarkCard),
                ) {
                    Icon(
                        Icons.Default.FlashOn,
                        contentDescription = "Flashlight strobe",
                        tint = if (isFlashlightOn) Color.Black else TextPrimary,
                    )
                }

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
                        .background(if (isSirenActive) PrimaryRed else DarkCard),
                ) {
                    Icon(
                        imageVector = if (isSirenActive) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Siren sound",
                        tint = if (isSirenActive) Color.White else TextPrimary,
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = false)
                        val outcome = EmergencySmsManager.shareEmergencyLocationSheet(
                            context = context,
                            userName = "Guardian user",
                            latitude = fix?.latitude,
                            longitude = fix?.longitude,
                            accuracyM = fix?.accuracyM,
                            batteryLevel = null,
                        )
                        if (outcome is EmergencyActionResult.Failed) cancelError = outcome.message
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkCard),
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share location", tint = TextPrimary)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    EmergencyStatusRow(
                        icon = Icons.Default.GpsFixed,
                        title = "Location",
                        status = if (fix != null) {
                            "${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}" +
                                (fix.accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
                        } else {
                            viewModel.locationFailureMessage()
                        },
                        active = fix != null,
                    )
                }
                item {
                    val call = result?.callOutcome
                    EmergencyStatusRow(
                        icon = Icons.Default.Call,
                        title = "Emergency call",
                        status = when (call) {
                            is EmergencyActionResult.Dispatched -> call.detail
                            is EmergencyActionResult.UserActionRequired -> call.detail
                            is EmergencyActionResult.Failed -> call.message
                            null -> "No call was attempted."
                        },
                        active = call is EmergencyActionResult.Dispatched,
                    )
                }
                item {
                    val sent = deliveries.values.count { it.status == SmsStatus.SENT || it.status == SmsStatus.DELIVERED }
                    val queued = deliveries.values.count { it.status == SmsStatus.QUEUED }
                    val failed = deliveries.values.count {
                        it.status == SmsStatus.FAILED || it.status == SmsStatus.COMPOSE_OPENED
                    }
                    EmergencyStatusRow(
                        icon = Icons.Default.Sms,
                        title = "Emergency messages",
                        status = when {
                            deliveries.isEmpty() -> "No emergency contact could be messaged."
                            failed > 0 -> "$failed message(s) need your attention, $queued sending, $sent confirmed."
                            queued > 0 -> "$queued message(s) handed to the network."
                            else -> "$sent message(s) confirmed by the network."
                        },
                        active = sent > 0 || queued > 0,
                    )
                }
                item {
                    EmergencyStatusRow(
                        icon = Icons.Default.CloudUpload,
                        title = "Guardian service",
                        status = when (sosState?.syncStatus) {
                            "SYNCED" -> "Emergency confirmed by the Guardian service (${sosState?.remoteId ?: ""})."
                            "SYNCING" -> "Sending to the Guardian service…"
                            "FAILED" -> sosState?.lastError ?: "The Guardian service could not be reached."
                            else -> "Stored on this device and queued for sync."
                        },
                        active = sosState?.syncStatus == "SYNCED",
                    )
                }
                item {
                    EmergencyStatusRow(
                        icon = Icons.Default.Group,
                        title = "Family and responders",
                        status = when {
                            sosState?.remoteId == null -> "Your guardians will be alerted once the service confirms."
                            sosState?.acknowledgedAt != null -> "A guardian has acknowledged your alert."
                            else -> "Alert is live for your guardian network."
                        },
                        active = sosState?.remoteId != null,
                    )
                }
                item {
                    EmergencyStatusRow(
                        icon = Icons.Default.LocalPolice,
                        title = "Emergency services ($emergencyNumber)",
                        status = "Dialling is one tap away and works without data.",
                        active = true,
                    )
                }
                result?.problems?.takeIf { it.isNotEmpty() }?.let { problems ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = WarningYellow.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "What still needs attention",
                                    color = WarningYellow,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                problems.forEach { problem ->
                                    Text(
                                        text = "• $problem",
                                        color = TextPrimary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (sosState?.syncStatus != "SYNCED") {
                    item {
                        OutlinedButton(
                            onClick = { viewModel.retryPendingSos() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = TextPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry sending now", color = TextPrimary)
                        }
                    }
                }
            }

            if (cancelError != null) {
                Text(
                    text = cancelError ?: "",
                    color = WarningYellow,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Button(
                onClick = {
                    HapticUtils.triggerHaptic(context, isHeavy = false)
                    hardwareAlertManager.release()
                    viewModel.cancelSos()
                    onCancelSos()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "I AM SAFE — CANCEL SOS",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
fun EmergencyStatusRow(icon: ImageVector, title: String, status: String, active: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(icon, contentDescription = null, tint = if (active) SuccessGreen else TextSecondary)
                Column(modifier = Modifier.padding(end = 16.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(status, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Icon(
                imageVector = if (active) Icons.Default.CheckCircle else Icons.Default.HourglassEmpty,
                contentDescription = null,
                tint = if (active) SuccessGreen else TextSecondary,
            )
        }
    }
}

/** Small helper used by the status list to describe the sync stage. */
internal fun SosStage.describeStage(): String = when (this) {
    SosStage.TRIGGERED -> "Emergency recorded"
    SosStage.CALL_STARTED -> "Call started"
    SosStage.SMS_STARTED -> "Messages sent"
    SosStage.CLOUD_SYNCED -> "Confirmed by Guardian"
    SosStage.FAILED -> "Failed"
    SosStage.CANCELLED -> "Cancelled"
}
