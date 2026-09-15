package com.guardian.safety.ui.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guardian.safety.service.EmergencyActionResult
import com.guardian.safety.service.describe
import com.guardian.safety.service.EmergencyCallManager
import com.guardian.safety.service.EmergencyNumbers
import com.guardian.safety.ui.GuardianViewModel
import com.guardian.safety.util.ExternalIntents
import com.guardian.safety.util.HapticUtils

/**
 * An emergency another Guardian user has raised and that this responder can see.
 * Built only from server events; there is no local sample list.
 */
data class CommunityEmergencyItem(
    val id: String,
    val userName: String,
    val emergencyType: String,
    val distanceKm: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val description: String,
    val timestamp: Long,
    val isVerified: Boolean = true,
    var status: String = "ACTIVE",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityResponderScreen(viewModel: GuardianViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var isOptedIn by remember { mutableStateOf(true) }
    var selectedRadiusKm by remember { mutableStateOf(10f) }

    val activeSos by viewModel.activeSosEvents.collectAsState()
    val locationState by viewModel.locationState.collectAsState()
    val fix = locationState as? com.guardian.safety.ui.LocationUiState.Available
    val emergencyNumber = remember(context) { EmergencyNumbers.primary(context) }
    val accepted = remember { mutableStateMapOf<String, String>() }

    // Real emergencies from the Guardian service, filtered to the chosen radius
    // when both the responder and the event have a genuine location.
    val emergencies = activeSos.map { event ->
        val distance = if (fix != null && event.latitude != null && event.longitude != null) {
            haversineKm(fix.latitude, fix.longitude, event.latitude, event.longitude).toFloat()
        } else {
            null
        }
        CommunityEmergencyItem(
            id = event.id,
            userName = event.userId?.take(8)?.let { "Guardian user $it" } ?: "Guardian user",
            emergencyType = event.triggerSource.replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() },
            distanceKm = distance,
            latitude = event.latitude,
            longitude = event.longitude,
            description = event.resolutionNote?.takeIf { it.isNotBlank() }
                ?: "Emergency raised at ${formatTimestamp(event.occurredAt)}.",
            timestamp = event.occurredAt,
            isVerified = event.acknowledgedAt != null,
            status = accepted[event.id] ?: "ACTIVE",
        )
    }.filter { item -> item.distanceKm == null || item.distanceKm <= selectedRadiusKm }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Community responders") },
                navigationIcon = {
                    IconButton(onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = false)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Responder status", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            }
                            Switch(
                                checked = isOptedIn,
                                onCheckedChange = {
                                    HapticUtils.triggerHaptic(context, isHeavy = false)
                                    isOptedIn = it
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isOptedIn) {
                                "You will see active Guardian emergencies within ${selectedRadiusKm.toInt()} km of your location."
                            } else {
                                "You are currently opted out of receiving nearby community emergency requests."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = selectedRadiusKm,
                            onValueChange = { selectedRadiusKm = it },
                            valueRange = 1f..25f,
                            steps = 23,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Active nearby emergencies (${emergencies.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!isOptedIn) {
                item {
                    EmptyState("Enable responder mode above to see active community emergencies.")
                }
            } else if (emergencies.isEmpty()) {
                item {
                    EmptyState(
                        "No active emergencies in your area right now. " +
                            (if (fix == null) "Turn on location to filter requests by distance." else ""),
                    )
                }
            } else {
                items(emergencies, key = { it.id }) { emergency ->
                    EmergencyResponderCard(
                        emergency = emergency,
                        emergencyNumber = emergencyNumber,
                        onProblem = viewModel::reportActionProblem,
                        onAccept = {
                            HapticUtils.triggerHaptic(context, isHeavy = true)
                            accepted[emergency.id] = "ACCEPTED"
                            viewModel.acknowledgeSos(emergency.id)
                        },
                        onDecline = {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            accepted.remove(emergency.id)
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return earthRadiusKm * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

private fun formatTimestamp(timestamp: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

@Composable
fun EmergencyResponderCard(
    emergency: CommunityEmergencyItem,
    emergencyNumber: String,
    onProblem: (String) -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val isAccepted = emergency.status == "ACCEPTED"

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAccepted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = emergency.emergencyType,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = emergency.distanceKm?.let { String.format(java.util.Locale.US, "%.1f km away", it) }
                            ?: "Location shared",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Requester: ${emergency.userName}",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = emergency.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isAccepted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            val outcome = EmergencyCallManager.callNumber(context, emergencyNumber)
                            if (outcome !is EmergencyActionResult.Dispatched) {
                                // Surfaced through the app banner instead of pretending
                                // the call happened.
                                onProblem(outcome.describe())
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Call $emergencyNumber", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            val lat = emergency.latitude
                            val lng = emergency.longitude
                            if (lat == null || lng == null) {
                                onProblem("This emergency has no location to navigate to.")
                            } else {
                                // A device with no maps app must not crash the
                                // responder screen: the failure is reported and a
                                // browser map is tried first.
                                val result = ExternalIntents.showOnMap(
                                    context = context,
                                    latitude = lat,
                                    longitude = lng,
                                    label = "Emergency location",
                                )
                                if (result is ExternalIntents.LaunchResult.Unavailable) {
                                    onProblem(result.message)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Navigate", fontSize = 12.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Accept & Respond", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Decline", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

