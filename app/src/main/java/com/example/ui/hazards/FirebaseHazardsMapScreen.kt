package com.example.ui.hazards

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.FirebaseHazard
import com.example.ui.GuardianViewModel
import com.example.ui.theme.*
import com.example.util.HapticUtils
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirebaseHazardsMapScreen(viewModel: GuardianViewModel) {
    val hazards by viewModel.firebaseHazards.collectAsState()
    val context = LocalContext.current

    var selectedCategory by remember { mutableStateOf("All") }
    var selectedHazard by remember { mutableStateOf<FirebaseHazard?>(null) }
    var showReportDialog by remember { mutableStateOf(false) }

    val categories = listOf("All", "Crime", "Hazard", "Weather", "Medical")

    val filteredHazards = hazards.filter { h ->
        selectedCategory == "All" || h.category.equals(selectedCategory, ignoreCase = true)
    }

    // Radar pulsing animation
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val radarSweep by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Immersive Radar Map Canvas & Pins Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0C12)),
            contentAlignment = Alignment.Center
        ) {
            // Radar Circles & Sweep
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension * 0.45f

                // Concentric circles
                drawCircle(color = AccentPurple.copy(alpha = 0.1f), radius = maxRadius * 0.25f, center = center, style = Stroke(1.dp.toPx()))
                drawCircle(color = AccentPurple.copy(alpha = 0.15f), radius = maxRadius * 0.5f, center = center, style = Stroke(1.dp.toPx()))
                drawCircle(color = AccentPurple.copy(alpha = 0.2f), radius = maxRadius * 0.75f, center = center, style = Stroke(1.dp.toPx()))
                drawCircle(color = AccentPurple.copy(alpha = 0.25f), radius = maxRadius, center = center, style = Stroke(1.5.dp.toPx()))

                // Crosshairs
                drawLine(color = AccentPurple.copy(alpha = 0.15f), start = androidx.compose.ui.geometry.Offset(center.x, center.y - maxRadius), end = androidx.compose.ui.geometry.Offset(center.x, center.y + maxRadius), strokeWidth = 1f)
                drawLine(color = AccentPurple.copy(alpha = 0.15f), start = androidx.compose.ui.geometry.Offset(center.x - maxRadius, center.y), end = androidx.compose.ui.geometry.Offset(center.x + maxRadius, center.y), strokeWidth = 1f)
            }

            // User Center Marker (Pulsing)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(PrimaryRed.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(PrimaryRed)
                )
            }

            // Plotted Hazard Pins across map relative to center
            filteredHazards.forEachIndexed { index, hazard ->
                // Calculate pseudo-offset based on lat/lng difference from base (37.7749, -122.4194)
                val dLat = (hazard.latitude - 37.7749) * 4000f
                val dLng = (hazard.longitude - (-122.4194)) * 4000f

                val pinColor = when (hazard.severity.lowercase()) {
                    "critical" -> PrimaryRed
                    "warning" -> Color(0xFFFFA726)
                    else -> SuccessGreen
                }

                Box(
                    modifier = Modifier
                        .offset(x = dLng.dp, y = (-dLat).dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(DarkSurface)
                        .border(2.dp, pinColor, CircleShape)
                        .clickable {
                            HapticUtils.triggerHaptic(context, isHeavy = false)
                            selectedHazard = hazard
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (hazard.category.lowercase()) {
                            "crime" -> Icons.Default.Security
                            "weather" -> Icons.Default.Cloud
                            "medical" -> Icons.Default.LocalHospital
                            else -> Icons.Default.Warning
                        },
                        contentDescription = hazard.title,
                        tint = pinColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Top Header & Filter Chips Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(SuccessGreen)
                        )
                        Text(
                            text = "FIREBASE LIVE HAZARDS MAP",
                            style = MaterialTheme.typography.labelSmall,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Vicinity Threat Radar",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = {
                        HapticUtils.triggerHaptic(context, isHeavy = false)
                        showReportDialog = true
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(PrimaryRed)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Report Hazard", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Category Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    val isSelected = selectedCategory == category
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                HapticUtils.triggerHaptic(context, isHeavy = false)
                                selectedCategory = category
                            },
                        color = if (isSelected) PrimaryRed else DarkCard,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, if (isSelected) PrimaryRed else DividerColor)
                    ) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) Color.White else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Bottom Hazard Summary Bar / Selected Hazard Bottom Sheet
        if (selectedHazard != null) {
            val hazard = selectedHazard!!
            val severityColor = when (hazard.severity.lowercase()) {
                "critical" -> PrimaryRed
                "warning" -> Color(0xFFFFA726)
                else -> SuccessGreen
            }
            val timeFormatted = remember(hazard.timestamp) {
                val diffMin = (System.currentTimeMillis() - hazard.timestamp) / 60000
                if (diffMin < 1) "Just now" else "$diffMin mins ago"
            }

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, DividerColor),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(severityColor.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = severityColor)
                            }
                            Column {
                                Text(
                                    text = hazard.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${hazard.category} • Reported $timeFormatted by ${hazard.reportedBy}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        IconButton(onClick = { selectedHazard = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Text(
                        text = hazard.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.ThumbUp, contentDescription = null, tint = AccentPurple, modifier = Modifier.size(16.dp))
                            Text(
                                text = "${hazard.upvotes} Community Confirms",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    HapticUtils.triggerHaptic(context, isHeavy = false)
                                    viewModel.upvoteFirebaseHazard(hazard.id)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPurple)
                            ) {
                                Icon(Icons.Default.ThumbUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Verify")
                            }

                            Button(
                                onClick = {
                                    HapticUtils.triggerHaptic(context, isHeavy = true)
                                    selectedHazard = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Route Around")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReportDialog) {
        ReportFirebaseHazardDialog(
            onDismiss = { showReportDialog = false },
            onSubmit = { title, category, severity, description ->
                viewModel.reportFirebaseHazard(
                    title = title,
                    category = category,
                    severity = severity,
                    description = description,
                    latitude = 37.7749 + (Math.random() - 0.5) * 0.02,
                    longitude = -122.4194 + (Math.random() - 0.5) * 0.02
                )
                showReportDialog = false
                HapticUtils.triggerHaptic(context, isHeavy = true)
            }
        )
    }
}

@Composable
fun ReportFirebaseHazardDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Hazard") }
    var severity by remember { mutableStateOf("Warning") }

    val categories = listOf("Crime", "Hazard", "Weather", "Medical")
    val severities = listOf("Critical", "Warning", "Info")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = PrimaryRed)
                Text("Report Safety Hazard", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Hazard Title") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryRed,
                        unfocusedBorderColor = DividerColor,
                        focusedLabelColor = PrimaryRed,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description & Location details") },
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryRed,
                        unfocusedBorderColor = DividerColor,
                        focusedLabelColor = PrimaryRed,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Text("Category", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(categories) { cat ->
                        val isSelected = category == cat
                        FilterChip(
                            selected = isSelected,
                            onClick = { category = cat },
                            label = { Text(cat) }
                        )
                    }
                }

                Text("Severity Level", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(severities) { sev ->
                        val isSelected = severity == sev
                        FilterChip(
                            selected = isSelected,
                            onClick = { severity = sev },
                            label = { Text(sev) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onSubmit(title, category, severity, description.ifBlank { title })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
            ) {
                Text("Broadcast to Firebase", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
