package com.example.ui.family

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.SafeZoneEntity
import com.example.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyMapScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit
) {
    val familyMembers by viewModel.familyMembers.collectAsState()
    val safeZones by viewModel.safeZones.collectAsState()

    var showAddZoneDialog by remember { mutableStateOf(false) }
    var zoneName by remember { mutableStateOf("") }
    var zoneRadius by remember { mutableStateOf("200") }
    var zoneType by remember { mutableStateOf("HOME") }

    val primaryColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Family Map & Safe Zones") },
                navigationIcon = {
                    IconButton(onClick = { onBack() }, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { showAddZoneDialog = true }, modifier = Modifier.testTag("add_safe_zone_button")) {
                        Icon(Icons.Default.AddLocation, contentDescription = "Add Safe Zone")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().height(260.dp).testTag("family_radar_map_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val centerX = size.width / 2
                            val centerY = size.height / 2
                            drawCircle(color = Color.LightGray.copy(alpha = 0.4f), radius = 100f, center = Offset(centerX, centerY))
                            drawCircle(color = primaryColor.copy(alpha = 0.2f), radius = 60f, center = Offset(centerX, centerY))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Real-Time Geofence Radar Active", style = MaterialTheme.typography.titleMedium)
                            Text("Tracking ${familyMembers.size} family members across ${safeZones.size} safe zones", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Text(
                    "Active Safe Zones (${safeZones.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(safeZones) { zone ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().testTag("safe_zone_card_${zone.zoneId}")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (zone.zoneType) {
                                    "HOME" -> Icons.Default.Home
                                    "SCHOOL" -> Icons.Default.School
                                    "WORK" -> Icons.Default.Work
                                    else -> Icons.Default.Place
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(zone.name, style = MaterialTheme.typography.titleMedium)
                                Text("Radius: ${zone.radiusMeters.toInt()}m • Lat: ${zone.latitude}, Lng: ${zone.longitude}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { viewModel.deleteSafeZone(zone) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item {
                Text(
                    "Member Real-Time Locations",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(familyMembers) { member ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(member.name, style = MaterialTheme.typography.titleMedium)
                            Text("Location: ${member.latitude}, ${member.longitude}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(member.connectionStatus)
                        }
                    }
                }
            }
        }

        if (showAddZoneDialog) {
            AlertDialog(
                onDismissRequest = { showAddZoneDialog = false },
                title = { Text("Create Safe Zone") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = zoneName,
                            onValueChange = { zoneName = it },
                            label = { Text("Zone Name (e.g. Grandma's House)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_zone_name")
                        )
                        OutlinedTextField(
                            value = zoneRadius,
                            onValueChange = { zoneRadius = it },
                            label = { Text("Radius in Meters (e.g. 200)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_zone_radius")
                        )
                        OutlinedTextField(
                            value = zoneType,
                            onValueChange = { zoneType = it },
                            label = { Text("Type (HOME, SCHOOL, WORK, CUSTOM)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_zone_type")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (zoneName.isNotBlank()) {
                                viewModel.addSafeZone(zoneName, 37.7749, -122.4194, zoneRadius.toFloatOrNull() ?: 150f, zoneType)
                                showAddZoneDialog = false
                                zoneName = ""
                            }
                        },
                        modifier = Modifier.testTag("confirm_add_zone")
                    ) {
                        Text("Add Zone")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddZoneDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
