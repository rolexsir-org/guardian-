package com.example.ui.family

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.AppUsageEntity
import com.example.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentalControlsScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit
) {
    val appUsageList by viewModel.appUsage.collectAsState()
    var studyModeEnabled by remember { mutableStateOf(false) }
    var bedtimeScheduleEnabled by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parental Controls & Screen Time") },
                navigationIcon = {
                    IconButton(onClick = { onBack() }, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().testTag("screen_time_summary_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Digital Wellbeing & Daily Limits", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Monitor screen time, enforce app restrictions, and schedule study/bedtime blocks in compliance with Google Play family guidelines.", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Study Mode (Block Social)", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = studyModeEnabled,
                                onCheckedChange = { studyModeEnabled = it },
                                modifier = Modifier.testTag("study_mode_switch")
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Bedtime Lock (9 PM - 6 AM)", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = bedtimeScheduleEnabled,
                                onCheckedChange = { bedtimeScheduleEnabled = it },
                                modifier = Modifier.testTag("bedtime_switch")
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "App Usage & Restrictions (${appUsageList.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(appUsageList) { usage ->
                AppUsageCard(usage = usage, onUpdate = { limit, restricted ->
                    viewModel.updateAppLimit(usage.appId, limit, restricted)
                })
            }
        }
    }
}

@Composable
fun AppUsageCard(usage: AppUsageEntity, onUpdate: (Int, Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().testTag("app_usage_card_${usage.appId}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (usage.category) {
                            "Social" -> Icons.Default.Share
                            "Games" -> Icons.Default.SportsEsports
                            else -> Icons.Default.Apps
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(usage.appName, style = MaterialTheme.typography.titleMedium)
                        Text("Category: ${usage.category} • Used: ${usage.usedMinutes} mins / Limit: ${usage.dailyLimitMinutes} mins", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Badge(
                    containerColor = if (usage.isRestricted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(if (usage.isRestricted) "Restricted" else "Approved")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (usage.usedMinutes.toFloat() / usage.dailyLimitMinutes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (usage.usedMinutes >= usage.dailyLimitMinutes) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { onUpdate(usage.dailyLimitMinutes + 30, usage.isRestricted) },
                    modifier = Modifier.testTag("extend_time_${usage.appId}")
                ) {
                    Text("+30 mins")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onUpdate(usage.dailyLimitMinutes, !usage.isRestricted) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (usage.isRestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("toggle_restrict_${usage.appId}")
                ) {
                    Text(if (usage.isRestricted) "Unrestrict" else "Restrict App")
                }
            }
        }
    }
}
