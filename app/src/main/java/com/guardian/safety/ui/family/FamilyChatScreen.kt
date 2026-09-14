package com.guardian.safety.ui.family

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
import com.guardian.safety.data.FamilyMessageEntity
import com.guardian.safety.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyChatScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit
) {
    val messages by viewModel.familyMessages.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val familyGroup by viewModel.familyGroup.collectAsState()
    val senderName = (sessionState as? com.guardian.safety.service.SessionState.SignedIn)
        ?.session?.displayName?.takeIf { it.isNotBlank() } ?: "You"
    var inputText by remember { mutableStateOf("") }
    var isEmergencyBroadcast by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family chat") },
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
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Type family message...") },
                            modifier = Modifier.weight(1f).testTag("family_chat_input"),
                            maxLines = 3
                        )
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.sendFamilyMessage(inputText, isEmergencyBroadcast, senderName)
                                    inputText = ""
                                    isEmergencyBroadcast = false
                                }
                            },
                            modifier = Modifier.testTag("send_family_message_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isEmergencyBroadcast,
                                onCheckedChange = { isEmergencyBroadcast = it },
                                modifier = Modifier.testTag("emergency_broadcast_checkbox")
                            )
                            Text("Emergency SOS Broadcast", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            text = "Encrypted in transit · stored on your Guardian service",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            reverseLayout = true
        ) {
            item {
                if (familyGroup == null) {
                    Text(
                        text = "Join a family group to send messages. Open More → Family hub.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (messages.isEmpty()) {
                    Text(
                        text = "No messages yet. Send the first one to your family group.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(messages, key = { it.messageId }) { message ->
                FamilyMessageBubble(message = message)
            }
        }
    }
}

@Composable
fun FamilyMessageBubble(message: FamilyMessageEntity) {
    val isEmergency = message.isEmergencyBroadcast
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isEmergency) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().testTag("family_msg_${message.messageId}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isEmergency) Icons.Default.Warning else Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isEmergency) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(message.senderName, style = MaterialTheme.typography.titleSmall)
                }
                if (isEmergency) {
                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                        Text("SOS EMERGENCY")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
