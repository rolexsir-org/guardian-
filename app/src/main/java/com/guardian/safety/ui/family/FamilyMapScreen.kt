package com.guardian.safety.ui.family

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
import com.guardian.safety.data.SafeZoneEntity
import com.guardian.safety.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyMapScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit
) {
    val familyMembers by viewModel.familyMembers.collectAsState()
    val safeZones by viewModel.safeZones.collectAsState()
    val locationState by viewModel.locationState.collectAsState()
    val myFix = locationState as? com.guardian.safety.ui.LocationUiState.Available

    var showAddZoneDialog by remember { mutableStateOf(false) }
    var zoneName by remember { mutableStateOf("") }
    var zoneRadius by remember { mutableStateOf("200") }
    var zoneType by remember { mutableStateOf("HOME") }

    val primaryColor = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family map & safe zones") },
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
                    IconButton(onClick = {
                        if (myFix == null) {
                            viewModel.reportActionProblem(
                                "A safe zone needs your real location. ${viewModel.locationFailureMessage()}",
                            )
                        } else {
                            showAddZoneDialog = true
                        }
                    }, modifier = Modifier.testTag("add_safe_zone_button")) {
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
                            Text(
                                text = if (myFix != null) "Safe-zone radar" else "Radar needs your location",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (myFix != null) {
                                    "Tracking ${familyMembers.count { it.latitude != null && it.longitude != null }} " +
                                        "sharing members across ${safeZones.size} safe zones"
                                } else {
                                    viewModel.locationFailureMessage()
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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

            if (familyMembers.isEmpty()) {
                item {
                    Text(
                        text = "No family members yet. Invite them from the Family hub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                            Text(
                                text = member.latitude?.let { lat ->
                                    "Location: ${"%.5f".format(lat)}, ${"%.5f".format(member.longitude ?: 0.0)}"
                                } ?: "This member has not shared a location.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                when {
                                    member.online -> "Online"
                                    member.lastSeenAt != null -> "Offline"
                                    else -> "Unknown"
                                },
                            )
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
                            if (zoneName.isNotBlank() && myFix != null) {
                                // Safe zones are anchored to the device's real position.
                                viewModel.addSafeZone(
                                    name = zoneName,
                                    latitude = myFix.latitude,
                                    longitude = myFix.longitude,
                                    radius = zoneRadius.toFloatOrNull() ?: 150f,
                                    type = zoneType.uppercase(),
                                )
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
