package com.example.ui.protection

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.GuardianViewModel
import com.example.ui.theme.*

@Composable
fun ProtectionDrawerPanel(
    isOpen: Boolean,
    onClose: () -> Unit,
    viewModel: GuardianViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val voiceEnabled by viewModel.isVoiceActivationEnabled.collectAsState()
    val backgroundAiEnabled by viewModel.isBackgroundAiEnabled.collectAsState()
    val fallGuardEnabled by viewModel.isFallGuardEnabled.collectAsState()
    val crashSosEnabled by viewModel.isCrashSosEnabled.collectAsState()
    val shakeGestureEnabled by viewModel.isShakeGestureEnabled.collectAsState()
    val autoSosSilenceEnabled by viewModel.isAutoSosSilenceEnabled.collectAsState()
    val liveGpsEnabled by viewModel.isLiveGpsEnabled.collectAsState()
    val guardianProximityEnabled by viewModel.isGuardianProximityEnabled.collectAsState()
    val cloudRecordEnabled by viewModel.isCloudRecordEnabled.collectAsState()
    val audioBlackboxEnabled by viewModel.isAudioBlackboxEnabled.collectAsState()
    val biometricLockEnabled by viewModel.isBiometricLockEnabled.collectAsState()
    val incognitoModeEnabled by viewModel.isIncognitoModeEnabled.collectAsState()

    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(animationSpec = tween(300)) + slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it }),
        exit = fadeOut(animationSpec = tween(300)) + slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onClose() })
                }
        ) {
            // Right-side Panel (approx 80% width)
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
                    .align(Alignment.CenterEnd)
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {}) // consume taps inside panel
                    },
                color = DarkSurface,
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                border = BorderStroke(1.dp, DividerColor),
                tonalElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryRed.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = PrimaryRed, modifier = Modifier.size(22.dp))
                            }
                            Column {
                                Text(
                                    text = "Protection Control",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Edge Quick Settings & Sentinels",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DividerColor, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Collapsible Sections List
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Section 1: Automated Sentinel Protection
                        item {
                            CollapsibleToggleSection(
                                title = "Automated Protection",
                                icon = Icons.Default.Security,
                                activeCount = listOf(voiceEnabled, backgroundAiEnabled).count { it },
                                totalCount = 2
                            ) {
                                ToggleItem(
                                    title = "Voice Sentinel",
                                    subtitle = "Scream & keyword detector",
                                    icon = Icons.Default.Mic,
                                    checked = voiceEnabled,
                                    onCheckedChange = { viewModel.toggleVoiceActivation(it, context) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ToggleItem(
                                    title = "Background Threat Monitor",
                                    subtitle = "Spatial safety & corridor scanning",
                                    icon = Icons.Default.Security,
                                    checked = backgroundAiEnabled,
                                    onCheckedChange = { viewModel.toggleBackgroundAi(it) }
                                )
                            }
                        }

                        // Section 2: Motion Detection
                        item {
                            CollapsibleToggleSection(
                                title = "Motion Detection",
                                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                                activeCount = listOf(fallGuardEnabled, crashSosEnabled).count { it },
                                totalCount = 2
                            ) {
                                ToggleItem(
                                    title = "Fall Guard",
                                    subtitle = "Impact deceleration monitoring",
                                    icon = Icons.AutoMirrored.Filled.DirectionsRun,
                                    checked = fallGuardEnabled,
                                    onCheckedChange = { viewModel.toggleFallGuard(it) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ToggleItem(
                                    title = "Crash SOS",
                                    subtitle = "High-g vehicle collision sensing",
                                    icon = Icons.Default.CarCrash,
                                    checked = crashSosEnabled,
                                    onCheckedChange = { viewModel.toggleCrashSos(it) }
                                )
                            }
                        }

                        // Section 3: Emergency Triggers
                        item {
                            CollapsibleToggleSection(
                                title = "Emergency Triggers",
                                icon = Icons.Default.Vibration,
                                activeCount = listOf(shakeGestureEnabled, autoSosSilenceEnabled).count { it },
                                totalCount = 2
                            ) {
                                ToggleItem(
                                    title = "Shake Gesture",
                                    subtitle = "Vigorous 4x device shake",
                                    icon = Icons.Default.Vibration,
                                    checked = shakeGestureEnabled,
                                    onCheckedChange = { viewModel.toggleShakeGesture(it) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ToggleItem(
                                    title = "Auto-SOS on Silence",
                                    subtitle = "Check-in timeout broadcaster",
                                    icon = Icons.Default.Timer,
                                    checked = autoSosSilenceEnabled,
                                    onCheckedChange = { viewModel.toggleAutoSosSilence(it) }
                                )
                            }
                        }

                        // Section 4: Monitoring
                        item {
                            CollapsibleToggleSection(
                                title = "Monitoring",
                                icon = Icons.Default.Radar,
                                activeCount = listOf(liveGpsEnabled, guardianProximityEnabled).count { it },
                                totalCount = 2
                            ) {
                                ToggleItem(
                                    title = "Live GPS Telemetry",
                                    subtitle = "Real-time location stream",
                                    icon = Icons.Default.GpsFixed,
                                    checked = liveGpsEnabled,
                                    onCheckedChange = { viewModel.toggleLiveGps(it) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ToggleItem(
                                    title = "Guardian Proximity",
                                    subtitle = "Alert nearby trusted contacts",
                                    icon = Icons.Default.Group,
                                    checked = guardianProximityEnabled,
                                    onCheckedChange = { viewModel.toggleGuardianProximity(it) }
                                )
                            }
                        }

                        // Section 5: Recording
                        item {
                            CollapsibleToggleSection(
                                title = "Recording",
                                icon = Icons.Default.CloudUpload,
                                activeCount = listOf(cloudRecordEnabled, audioBlackboxEnabled).count { it },
                                totalCount = 2
                            ) {
                                ToggleItem(
                                    title = "Cloud Evidence Vault",
                                    subtitle = "Instant encrypted stream backup",
                                    icon = Icons.Default.CloudUpload,
                                    checked = cloudRecordEnabled,
                                    onCheckedChange = { viewModel.toggleCloudRecord(it) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ToggleItem(
                                    title = "Audio Blackbox",
                                    subtitle = "Continuous 5-min rolling buffer",
                                    icon = Icons.Default.MicExternalOn,
                                    checked = audioBlackboxEnabled,
                                    onCheckedChange = { viewModel.toggleAudioBlackbox(it) }
                                )
                            }
                        }

                        // Section 6: Privacy
                        item {
                            CollapsibleToggleSection(
                                title = "Privacy",
                                icon = Icons.Default.Lock,
                                activeCount = listOf(biometricLockEnabled, incognitoModeEnabled).count { it },
                                totalCount = 2
                            ) {
                                ToggleItem(
                                    title = "Biometric Lock",
                                    subtitle = "Require FaceID/Fingerprint for SOS cancel",
                                    icon = Icons.Default.Fingerprint,
                                    checked = biometricLockEnabled,
                                    onCheckedChange = { viewModel.toggleBiometricLock(it) }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ToggleItem(
                                    title = "Incognito Mode",
                                    subtitle = "Conceal notification banners",
                                    icon = Icons.Default.VisibilityOff,
                                    checked = incognitoModeEnabled,
                                    onCheckedChange = { viewModel.toggleIncognitoMode(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CollapsibleToggleSection(
    title: String,
    icon: ImageVector,
    activeCount: Int,
    totalCount: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow")

    Surface(
        color = DarkCard,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DividerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(20.dp))
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(text = "$activeCount/$totalCount active", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    HorizontalDivider(color = DividerColor.copy(alpha = 0.5f), thickness = 0.5f.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    content()
                }
            }
        }
    }
}

@Composable
fun ToggleItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentPurple.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = PrimaryRed,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = DarkBackground
            )
        )
    }
}
