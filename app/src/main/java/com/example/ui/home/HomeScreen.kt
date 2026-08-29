package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.GuardianViewModel
import com.example.ui.protection.ProtectionDrawerPanel
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    viewModel: GuardianViewModel,
    onTriggerSos: () -> Unit,
    onNavigateToLive: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sosPressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isEmergencyTriggered by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[android.Manifest.permission.SEND_SMS] ?: false
        val callGranted = permissions[android.Manifest.permission.CALL_PHONE] ?: false
        if (smsGranted || callGranted) {
            com.example.service.EmergencyCallManager.dialOrCallEmergency(context, "911")
        }
    }

    // Hold to trigger SOS coroutine
    LaunchedEffect(sosPressed) {
        if (sosPressed) {
            progress = 0f
            val startTime = System.currentTimeMillis()
            val duration = 2000L // 2 seconds hold
            while (sosPressed && progress < 1f) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    HapticUtils.triggerHaptic(context, isHeavy = true)
                    isEmergencyTriggered = true
                    permissionLauncher.launch(arrayOf(
                        android.Manifest.permission.SEND_SMS,
                        android.Manifest.permission.CALL_PHONE,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ))
                    onTriggerSos()
                    break
                }
                delay(16)
            }
        } else {
            progress = 0f
        }
    }

    var isProtectionDrawerOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // SECTION 1: Top Status Bar & Greeting
            TopStatusBarSection(onOpenProtectionDrawer = { isProtectionDrawerOpen = true })

            // SECTION 2: Hero SOS Area (~45% screen)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
                contentAlignment = Alignment.Center
            ) {
                HeroSosControl(
                    isPressed = sosPressed,
                    progress = progress,
                    isEmergency = isEmergencyTriggered,
                    onPressStart = {
                        HapticUtils.triggerHaptic(context, isHeavy = false)
                        sosPressed = true
                    },
                    onPressEnd = {
                        sosPressed = false
                    }
                )
            }

            // SECTION 3: Quick Emergency Actions (4 Floating Chips)
            QuickActionsSection(
                onSilentSos = {
                    HapticUtils.triggerHaptic(context, isHeavy = true)
                    permissionLauncher.launch(arrayOf(android.Manifest.permission.SEND_SMS))
                    onTriggerSos()
                },
                onMedical = {
                    HapticUtils.triggerHaptic(context, isHeavy = false)
                    permissionLauncher.launch(arrayOf(android.Manifest.permission.CALL_PHONE))
                    com.example.service.EmergencyCallManager.dialOrCallEmergency(context, "112")
                },
                onPolice = {
                    HapticUtils.triggerHaptic(context, isHeavy = true)
                    permissionLauncher.launch(arrayOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.CALL_PHONE))
                    com.example.service.EmergencyCallManager.dialOrCallEmergency(context, "911")
                    onTriggerSos()
                },
                onFakeCall = {
                    HapticUtils.triggerHaptic(context, isHeavy = false)
                    // Trigger simulated safety call
                    com.example.service.EmergencyCallManager.dialOrCallEmergency(context, "5550199")
                }
            )

            // SECTION 4: Guardian Status (Horizontal Avatars)
            GuardianStatusSection()

            // SECTION 5: Live Activity (Tiny Timeline)
            LiveActivitySection(onNavigateToLive = onNavigateToLive)
        }

        // Right-side Slide-in Protection Drawer Panel
        ProtectionDrawerPanel(
            isOpen = isProtectionDrawerOpen,
            onClose = { isProtectionDrawerOpen = false },
            viewModel = viewModel
        )
    }
}

@Composable
fun TopStatusBarSection(onOpenProtectionDrawer: () -> Unit) {
    val timeFormatted = remember {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SuccessGreen)
                )
                Text(
                    text = "PROTECTION ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = SuccessGreen,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Good evening, Alex",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Telemetry Pill
            Surface(
                color = DarkSurface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.border(1.dp, DividerColor, RoundedCornerShape(20.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = timeFormatted, style = MaterialTheme.typography.labelSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.size(3.dp).clip(CircleShape).background(TextSecondary))
                    Icon(Icons.Default.GpsFixed, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(12.dp))
                }
            }

            // Shield Icon (🛡) in top-right App Bar
            IconButton(
                onClick = onOpenProtectionDrawer,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(DarkSurface)
                    .border(1.dp, DividerColor, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Protection Settings",
                    tint = PrimaryRed,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun HeroSosControl(
    isPressed: Boolean,
    progress: Float,
    isEmergency: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hero_pulse")

    // Outer expanding wave 1
    val wave1Scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPressed || isEmergency) 800 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1Scale"
    )
    val wave1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPressed || isEmergency) 800 else 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1Alpha"
    )

    // Outer expanding wave 2 (offset)
    val wave2Scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPressed || isEmergency) 1000 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wave2Scale"
    )

    // Core button breathing pulse
    val corePulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPressed) 0.95f else if (isEmergency) 1.08f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPressed) 300 else if (isEmergency) 500 else 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "corePulse"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(260.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressStart()
                        tryAwaitRelease()
                        onPressEnd()
                    }
                )
            }
    ) {
        // Wave 1: Radiating outward aura ring
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(wave1Scale)
                .clip(CircleShape)
                .background(PrimaryRed.copy(alpha = wave1Alpha))
        )

        // Wave 2: Outer pulsing glow ring
        Box(
            modifier = Modifier
                .size(210.dp)
                .scale(wave2Scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isEmergency) listOf(PrimaryRed, Color(0xFF581C87))
                        else listOf(PrimaryRed.copy(alpha = 0.35f), Color.Transparent)
                    )
                )
        )

        // Outer Progress Ring when holding
        if (isPressed) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(240.dp),
                color = Color.White,
                trackColor = PrimaryRed.copy(alpha = 0.4f),
                strokeWidth = 8.dp,
            )
        }

        // Inner Core Button with pulsing animation
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(corePulseScale)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = if (isEmergency) listOf(Color(0xFFFF1744), Color(0xFFD50000))
                        else listOf(PrimaryRed, Color(0xFFB71C1C))
                    )
                )
                .border(3.dp, Color.White.copy(alpha = if (isPressed) 0.9f else 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = if (isEmergency) Icons.Default.Warning else Icons.Default.Shield,
                    contentDescription = "SOS Trigger Button",
                    tint = Color.White,
                    modifier = Modifier
                        .size(48.dp)
                        .scale(if (isEmergency) corePulseScale else 1f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isEmergency) "BROADCASTING" else if (isPressed) "HOLD..." else "HOLD FOR SOS",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }
        }
    }
}

@Composable
fun QuickActionsSection(
    onSilentSos: () -> Unit,
    onMedical: () -> Unit,
    onPolice: () -> Unit,
    onFakeCall: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickChip(title = "Silent SOS", icon = Icons.Default.VolumeOff, onClick = onSilentSos, modifier = Modifier.weight(1f))
        QuickChip(title = "Medical", icon = Icons.Default.MedicalServices, onClick = onMedical, modifier = Modifier.weight(1f))
        QuickChip(title = "Police 911", icon = Icons.Default.LocalPolice, onClick = onPolice, modifier = Modifier.weight(1f))
        QuickChip(title = "Fake Call", icon = Icons.Default.PhoneCallback, onClick = onFakeCall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun QuickChip(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = DarkCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, DividerColor)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun GuardianStatusSection() {
    val guardians = listOf(
        Pair("Dr. Elena", "0.4 mi • 2m"),
        Pair("Marcus", "1.2 mi • 5m"),
        Pair("Sarah", "3.0 mi • 12m")
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Guardians Ring", style = MaterialTheme.typography.titleSmall, color = TextSecondary, fontWeight = FontWeight.Bold)
        Text("3 Online", style = MaterialTheme.typography.labelSmall, color = SuccessGreen, fontWeight = FontWeight.Bold)
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(guardians) { guardian ->
            Surface(
                color = DarkCard,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.border(1.dp, DividerColor, RoundedCornerShape(18.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AccentPurple.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = guardian.first.take(2).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentPurple,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(guardian.first, style = MaterialTheme.typography.bodySmall, color = TextPrimary, fontWeight = FontWeight.Bold)
                        Text(guardian.second, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun LiveActivitySection(onNavigateToLive: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onNavigateToLive),
        color = DarkSurface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, DividerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SuccessGreen.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Radar, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text("Voice Sentinel & Live Journey Tracking", style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Continuous on-device threat scanning active", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}
