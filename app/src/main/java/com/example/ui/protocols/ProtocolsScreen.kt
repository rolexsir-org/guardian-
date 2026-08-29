package com.example.ui.protocols

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.EmergencyCallManager
import com.example.service.HardwareAlertManager
import com.example.util.HapticUtils

data class ProtocolGuide(
    val title: String,
    val category: String,
    val emergencyNumber: String = "911",
    val steps: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolsScreen() {
    val context = LocalContext.current
    val hardwareAlertManager = remember { HardwareAlertManager(context) }

    DisposableEffect(Unit) {
        onDispose {
            hardwareAlertManager.release()
        }
    }

    val guides = listOf(
        ProtocolGuide(
            title = "Medical Emergency & CPR",
            category = "Medical",
            emergencyNumber = "911",
            steps = listOf(
                "Check the scene for safety and assess responsiveness.",
                "Call 911 immediately and get an AED if nearby.",
                "Place the heel of one hand in the center of the chest, interlock the other hand on top.",
                "Push hard and fast (100–120 compressions per minute).",
                "Allow chest to recoil completely between compressions."
            )
        ),
        ProtocolGuide(
            title = "Earthquake Safety",
            category = "Natural Disaster",
            emergencyNumber = "911",
            steps = listOf(
                "DROP to your hands and knees before the earthquake knocks you down.",
                "COVER your head and neck under a sturdy table or desk.",
                "HOLD ON to your shelter until shaking stops.",
                "Stay away from glass, windows, exterior doors, and walls.",
                "If outdoors, move away from buildings, streetlights, and utility wires."
            )
        ),
        ProtocolGuide(
            title = "Fire Evacuation",
            category = "Fire",
            emergencyNumber = "911",
            steps = listOf(
                "Stay low to the ground where air is cleaner and cooler.",
                "Feel doors with the back of your hand before opening them. If hot, use an alternate escape route.",
                "If clothes catch fire: STOP, DROP, and ROLL.",
                "Evacuate immediately. Never use elevators during a fire.",
                "Gather at your designated assembly point and call emergency services."
            )
        ),
        ProtocolGuide(
            title = "Personal Threat & Confrontation",
            category = "Security",
            emergencyNumber = "911",
            steps = listOf(
                "Stay calm, keep your hands visible, and avoid aggressive posturing.",
                "Look for an immediate exit route to a well-lit public space or business.",
                "Make loud, clear demands or draw attention ('Call 911! Fire!').",
                "Trigger the Guardian SOS alert on your phone if in immediate danger.",
                "Comply with demands if your physical safety or life is directly threatened."
            )
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emergency Protocols & First Aid", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(guides) { guide ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = guide.category,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                IconButton(
                                    onClick = {
                                        HapticUtils.triggerHaptic(context, isHeavy = false)
                                        val fullText = "${guide.title}. " + guide.steps.joinToString(". ")
                                        hardwareAlertManager.speakSafetyAdvice(fullText)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.VolumeUp,
                                        contentDescription = "Read Aloud Hands Free",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        HapticUtils.triggerHaptic(context, isHeavy = true)
                                        EmergencyCallManager.dialOrCallEmergency(context, guide.emergencyNumber)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Call,
                                        contentDescription = "Call Emergency Number",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        Text(
                            text = guide.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        guide.steps.forEachIndexed { index, step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = step,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

