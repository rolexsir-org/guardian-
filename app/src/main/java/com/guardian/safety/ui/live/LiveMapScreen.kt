package com.guardian.safety.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.guardian.safety.ui.GuardianViewModel
import com.guardian.safety.ui.theme.*

@Composable
fun LiveMapScreen(viewModel: GuardianViewModel) {
    val locationState by viewModel.locationState.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()
    val presence by viewModel.presence.collectAsState()
    val isSharing by viewModel.isLiveGpsEnabled.collectAsState()
    val fix = locationState as? com.guardian.safety.ui.LocationUiState.Available
    val sharingMembers = familyMembers.count { it.latitude != null && it.longitude != null }
    val onlineMembers = familyMembers.count { member ->
        presence.any { it.userId == member.userId && it.status == "ONLINE" } || member.online
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Immersive Map Grid
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0C0E14)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Radar,
                contentDescription = null,
                tint = AccentPurple.copy(alpha = 0.15f),
                modifier = Modifier.size(350.dp)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(PrimaryRed.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(PrimaryRed)
                )
            }
        }

        // Floating Header Chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = DarkCard,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isSharing) SuccessGreen else DangerRed)
                    )
                    Text(
                        text = when {
                            !isSharing -> "SHARING PAUSED"
                            fix != null -> "SHARING LOCATION"
                            else -> "WAITING FOR GPS"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Draggable Floating Glass Bottom Sheet
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(TextSecondary.copy(alpha = 0.4f))
                        .align(Alignment.CenterHorizontally)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Live location",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (fix != null) {
                                "${"%.5f".format(fix.latitude)}, ${"%.5f".format(fix.longitude)}" +
                                    (fix.accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
                            } else {
                                viewModel.locationFailureMessage()
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                    Button(
                        onClick = { viewModel.toggleLiveGps(!isSharing) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSharing) DangerRed.copy(alpha = 0.2f) else SuccessGreen.copy(alpha = 0.2f),
                            contentColor = if (isSharing) DangerRed else SuccessGreen
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(if (isSharing) "Stop" else "Resume", fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = DividerColor)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    MetricItem(
                        icon = Icons.Default.GpsFixed,
                        label = "Sharing",
                        value = if (sharingMembers > 0) "$sharingMembers" else "0",
                    )
                    MetricItem(
                        icon = Icons.Default.Group,
                        label = "Online",
                        value = onlineMembers.toString(),
                    )
                    MetricItem(
                        icon = Icons.Default.MyLocation,
                        label = "My fix",
                        value = fix?.accuracyM?.let { "±${it.toInt()} m" } ?: "—",
                    )
                    MetricItem(
                        icon = Icons.Default.Update,
                        label = "Updated",
                        value = fix?.let { com.guardian.safety.ui.relativeTime(it.at) } ?: "—",
                    )
                }
            }
        }
    }
}

@Composable
fun MetricItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}
