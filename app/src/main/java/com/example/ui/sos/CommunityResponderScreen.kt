package com.example.ui.sos

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.GuardianViewModel
import com.example.util.HapticUtils

data class CommunityEmergencyItem(
    val id: String,
    val userName: String,
    val emergencyType: String,
    val distanceKm: Float,
    val description: String,
    val timestamp: Long,
    val isVerified: Boolean = true,
    var status: String = "ACTIVE"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityResponderScreen(viewModel: GuardianViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var isOptedIn by remember { mutableStateOf(true) }
    var selectedRadiusKm by remember { mutableStateOf(10f) }

    val activeEmergencies = remember {
        mutableStateListOf(
            CommunityEmergencyItem(
                id = "em_1",
                userName = "Sarah J.",
                emergencyType = "Personal Safety Threat",
                distanceKm = 0.8f,
                description = "Suspicious individual following near 4th and Main St. Immediate assistance requested.",
                timestamp = System.currentTimeMillis() - 120000
            ),
            CommunityEmergencyItem(
                id = "em_2",
                userName = "David K.",
                emergencyType = "Medical Emergency",
                distanceKm = 2.4f,
                description = "Sudden difficulty breathing and chest pain at Central Station platform 2.",
                timestamp = System.currentTimeMillis() - 300000
            ),
            CommunityEmergencyItem(
                id = "em_3",
                userName = "Elena R.",
                emergencyType = "Road Accident",
                distanceKm = 4.1f,
                description = "Minor vehicle collision at Highway 101 offramp. Traffic blocked, medical check requested.",
                timestamp = System.currentTimeMillis() - 600000
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Community Emergency & Responders") },
                navigationIcon = {
                    IconButton(onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = false)
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialFactory.containerColorOrDefault(MaterialTheme.colorScheme.primaryContainer),
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingSize.padding16
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Responder Status", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            }
                            Switch(
                                checked = isOptedIn,
                                onCheckedChange = {
                                    HapticUtils.triggerHaptic(context, isHeavy = false)
                                    isOptedIn = it
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isOptedIn) "You are opted-in as a Verified Community Responder within a ${selectedRadiusKm.toInt()} km radius. You will receive urgent alerts when nearby neighbors trigger an SOS."
                            else "You are currently opted-out of receiving nearby community emergency requests.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Active Nearby Emergencies (${activeEmergencies.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!isOptedIn) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Enable Responder Mode above to view active community emergency requests.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                items(activeEmergencies) { emergency ->
                    EmergencyResponderCard(
                        emergency = emergency,
                        onAccept = {
                            HapticUtils.triggerHaptic(context, isHeavy = true)
                            emergency.status = "ACCEPTED"
                        },
                        onDecline = {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            activeEmergencies.remove(emergency)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun EmergencyResponderCard(
    emergency: CommunityEmergencyItem,
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
                        text = "${emergency.distanceKm} km away",
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
                            val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:911"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Call 911", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:37.7749,-122.4194?q=Emergency+Location"))
                            context.startActivity(intent)
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

object PaddingSize {
    val padding16 = PaddingValues(16.dp)
}
object MaterialFactory {
    fun containerColorOrDefault(color: Color): Color = color
}
