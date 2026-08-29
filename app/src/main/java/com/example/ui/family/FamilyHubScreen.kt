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
import com.example.data.FamilyMemberEntity
import com.example.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyHubScreen(
    viewModel: GuardianViewModel,
    onNavigateToMap: () -> Unit,
    onNavigateToControls: () -> Unit,
    onNavigateToChat: () -> Unit
) {
    val familyGroup by viewModel.familyGroup.collectAsState()
    val familyMembers by viewModel.familyMembers.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var newOwnerName by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var joinName by remember { mutableStateOf("") }
    var joinRole by remember { mutableStateOf("MEMBER") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Safety Shield & Hub") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                        onNavigateToMap()
                    }, modifier = Modifier.testTag("nav_family_map")) {
                        Icon(Icons.Default.Map, contentDescription = "Live Map")
                    }
                    IconButton(onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                        onNavigateToControls()
                    }, modifier = Modifier.testTag("nav_family_controls")) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = "Device Controls")
                    }
                    IconButton(onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                        onNavigateToChat()
                    }, modifier = Modifier.testTag("nav_family_chat")) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Family Chat")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                    showCreateDialog = true
                },
                modifier = Modifier.testTag("create_family_fab")
            ) {
                Icon(Icons.Default.GroupAdd, contentDescription = "Create Family Group")
            }
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().testTag("family_group_card")
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                familyGroup?.groupName ?: "No Family Group Active",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Badge {
                                Text(familyGroup?.inviteCode ?: "NO-CODE")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Secure RBAC Family Group • Active Encryption & Geofencing",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                                    showJoinDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.testTag("join_family_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Join Group")
                            }
                            OutlinedButton(
                                onClick = {
                                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                                    onNavigateToMap()
                                },
                                modifier = Modifier.testTag("view_live_map_button")
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Live Map")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Family Members (${familyMembers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(familyMembers) { member ->
                FamilyMemberCard(member = member)
            }
        }

        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("Create Family Shield Group") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            label = { Text("Family Group Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_group_name")
                        )
                        OutlinedTextField(
                            value = newOwnerName,
                            onValueChange = { newOwnerName = it },
                            label = { Text("Your Name (Owner)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_owner_name")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newGroupName.isNotBlank() && newOwnerName.isNotBlank()) {
                                viewModel.createFamilyGroup(newGroupName, newOwnerName)
                                showCreateDialog = false
                                newGroupName = ""
                                newOwnerName = ""
                            }
                        },
                        modifier = Modifier.testTag("confirm_create_group")
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showJoinDialog) {
            AlertDialog(
                onDismissRequest = { showJoinDialog = false },
                title = { Text("Join Family Group") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = joinCode,
                            onValueChange = { joinCode = it },
                            label = { Text("Invite Code (e.g. GUARDIAN-9921)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_join_code")
                        )
                        OutlinedTextField(
                            value = joinName,
                            onValueChange = { joinName = it },
                            label = { Text("Your Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_join_name")
                        )
                        OutlinedTextField(
                            value = joinRole,
                            onValueChange = { joinRole = it },
                            label = { Text("Role (PARENT, CHILD, GUARDIAN, MEMBER)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_join_role")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (joinCode.isNotBlank() && joinName.isNotBlank()) {
                                viewModel.joinFamilyGroup(joinCode, joinName, joinRole)
                                showJoinDialog = false
                                joinCode = ""
                                joinName = ""
                            }
                        },
                        modifier = Modifier.testTag("confirm_join_group")
                    ) {
                        Text("Join")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showJoinDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun FamilyMemberCard(member: FamilyMemberEntity) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().testTag("family_member_card_${member.memberId}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (member.role) {
                            "OWNER" -> Icons.Default.AdminPanelSettings
                            "PARENT" -> Icons.Default.SupervisorAccount
                            "GUARDIAN" -> Icons.Default.Security
                            "CHILD" -> Icons.Default.ChildCare
                            else -> Icons.Default.Person
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(member.name, style = MaterialTheme.typography.titleMedium)
                        Text(member.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Badge {
                    Text(member.role)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${member.batteryLevel}%", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SignalCellularAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(member.connectionStatus, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("GPS Active", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
