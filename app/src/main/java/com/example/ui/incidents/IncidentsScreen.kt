package com.example.ui.incidents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.IncidentEntity
import com.example.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentsScreen(viewModel: GuardianViewModel) {
    val incidents by viewModel.incidents.collectAsState()
    val subscriptions by viewModel.incidentSubscriptions.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("All") }
    val context = LocalContext.current

    val filteredIncidents = incidents.filter { incident ->
        val isSubscribed = subscriptions[incident.category] ?: true
        val matchesTab = selectedFilter == "All" || incident.category == selectedFilter
        isSubscribed && matchesTab
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Community Safety Feed", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                        showSettingsDialog = true
                    }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Alert Notification Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                    showAddDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Report Incident")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Filter Chips
            val categories = listOf("All", "Crime", "Hazard", "Weather", "Medical")
            ScrollableTabRow(
                selectedTabIndex = categories.indexOf(selectedFilter),
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                categories.forEach { category ->
                    Tab(
                        selected = selectedFilter == category,
                        onClick = {
                            com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                            selectedFilter = category
                        },
                        text = { Text(category, fontWeight = FontWeight.SemiBold) }
                    )
                }
            }

            if (filteredIncidents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No safety incidents reported in this category.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingAlias(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredIncidents) { incident ->
                        IncidentCard(incident = incident, onUpvote = { viewModel.upvoteIncident(incident) })
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddIncidentDialog(
            onDismiss = { showAddDialog = false },
            onSubmit = { title, category, severity, description, location ->
                viewModel.addIncident(title, category, severity, description, location)
                showAddDialog = false
            }
        )
    }

    if (showSettingsDialog) {
        IncidentSubscriptionSettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
fun IncidentSubscriptionSettingsDialog(
    viewModel: GuardianViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val subscriptions by viewModel.incidentSubscriptions.collectAsState()
    val categories = listOf("Crime", "Hazard", "Weather", "Medical", "SOS", "Community")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Alert Notification Settings", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Subscribe or unsubscribe from specific types of incident alert notifications:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                categories.forEach { category ->
                    val isSubscribed = subscriptions[category] ?: true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(category, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = isSubscribed,
                            onCheckedChange = {
                                com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                                viewModel.toggleIncidentSubscription(category, it)
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun IncidentCard(incident: IncidentEntity, onUpvote: () -> Unit) {
    val context = LocalContext.current
    val severityColor = when (incident.severity) {
        "Critical" -> MaterialTheme.colorScheme.error
        "Warning" -> Color(0xFFFFA500)
        else -> MaterialTheme.colorScheme.primary
    }

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = severityColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = incident.severity.uppercase(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = severityColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = incident.category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Active",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = incident.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Text(
                text = incident.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Location",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = incident.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                        onUpvote()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "Confirm",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "${incident.upvotes}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun PaddingAlias(all: androidx.compose.ui.unit.Dp) = PaddingValues(all)

@Composable
fun AddIncidentDialog(onDismiss: () -> Unit, onSubmit: (String, String, String, String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Hazard") }
    var severity by remember { mutableStateOf("Warning") }
    var description by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report Safety Incident", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Incident Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Category selection
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (Crime, Hazard, Weather, Medical)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Severity selection
                OutlinedTextField(
                    value = severity,
                    onValueChange = { severity = it },
                    label = { Text("Severity (Critical, Warning, Info)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location Description") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Details & Description") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            val context = LocalContext.current
            Button(
                onClick = {
                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                    if (title.isNotBlank() && description.isNotBlank()) {
                        onSubmit(title, category, severity, description, location.ifBlank { "Current Area" })
                    }
                }
            ) {
                Text("Submit Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
