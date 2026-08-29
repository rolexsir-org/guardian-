package com.example.ui.protection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.GuardianViewModel
import com.example.ui.theme.*

@Composable
fun ProtectionScreen(viewModel: GuardianViewModel) {
    var isDrawerOpen by remember { mutableStateOf(false) }

    val voiceEnabled by viewModel.isVoiceActivationEnabled.collectAsState()
    val backgroundAiEnabled by viewModel.isBackgroundAiEnabled.collectAsState()
    val fallGuardEnabled by viewModel.isFallGuardEnabled.collectAsState()
    val crashSosEnabled by viewModel.isCrashSosEnabled.collectAsState()
    val shakeGestureEnabled by viewModel.isShakeGestureEnabled.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "PROTECTION SENTINELS",
                            style = MaterialTheme.typography.headlineLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Automated Sentinels & Biometric Triggers",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    IconButton(
                        onClick = { isDrawerOpen = true },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(DarkSurface)
                            .border(1.dp, DividerColor, CircleShape)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = "Open Protection Panel", tint = PrimaryRed)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Quick Launch Banner for Slide-in Panel
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { isDrawerOpen = true },
                    color = DarkCard,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, DividerColor)
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(PrimaryRed.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = PrimaryRed)
                            }
                            Column {
                                Text("Protection Control Panel", style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text("Tap to open right-side slide-over quick settings", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }
                }
            }

            // Status Bento Grid (Read-only status overview)
            item {
                Text("Active Sentinels Overview", style = MaterialTheme.typography.titleSmall, color = TextSecondary, fontWeight = FontWeight.Bold)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusOverviewCard(
                        modifier = Modifier.weight(1f),
                        title = "Voice Sentinel",
                        status = if (voiceEnabled) "Active" else "Paused",
                        isactive = voiceEnabled,
                        icon = Icons.Default.Mic
                    )
                    StatusOverviewCard(
                        modifier = Modifier.weight(1f),
                        title = "Fall Guard",
                        status = if (fallGuardEnabled) "Armed" else "Off",
                        isactive = fallGuardEnabled,
                        icon = Icons.AutoMirrored.Filled.DirectionsRun
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusOverviewCard(
                        modifier = Modifier.weight(1f),
                        title = "Crash SOS",
                        status = if (crashSosEnabled) "Armed" else "Off",
                        isactive = crashSosEnabled,
                        icon = Icons.Default.CarCrash
                    )
                    StatusOverviewCard(
                        modifier = Modifier.weight(1f),
                        title = "Shake Gesture",
                        status = if (shakeGestureEnabled) "Ready" else "Off",
                        isactive = shakeGestureEnabled,
                        icon = Icons.Default.Vibration
                    )
                }
            }
        }

        ProtectionDrawerPanel(
            isOpen = isDrawerOpen,
            onClose = { isDrawerOpen = false },
            viewModel = viewModel
        )
    }
}

@Composable
fun StatusOverviewCard(
    modifier: Modifier = Modifier,
    title: String,
    status: String,
    isactive: Boolean,
    icon: ImageVector
) {
    Card(
        modifier = modifier
            .height(130.dp)
            .clip(RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DividerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isactive) SuccessGreen.copy(alpha = 0.2f) else AccentPurple.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = if (isactive) SuccessGreen else AccentPurple, modifier = Modifier.size(18.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isactive) SuccessGreen.copy(alpha = 0.15f) else DarkBackground)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(text = status, style = MaterialTheme.typography.labelSmall, color = if (isactive) SuccessGreen else TextSecondary, fontWeight = FontWeight.Bold)
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(if (isactive) "Operational" else "Disabled", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}


@Composable
fun BentoCardFull(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SuccessGreen.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = SuccessGreen)
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = PrimaryRed)
            )
        }
    }
}
