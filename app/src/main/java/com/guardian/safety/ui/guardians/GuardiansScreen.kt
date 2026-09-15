package com.guardian.safety.ui.guardians

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guardian.safety.service.EmergencyActionResult
import com.guardian.safety.service.EmergencyCallManager
import com.guardian.safety.service.describe
import com.guardian.safety.ui.GuardianViewModel
import com.guardian.safety.ui.relativeTime
import com.guardian.safety.ui.theme.*
import com.guardian.safety.util.HapticUtils

@Composable
fun GuardiansScreen(viewModel: GuardianViewModel) {
    val context = LocalContext.current
    val members by viewModel.familyMembers.collectAsState()
    val presence by viewModel.presence.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val realtimeState by viewModel.realtimeState.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "TRUSTED GUARDIANS",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (realtimeState) {
                        is com.guardian.safety.remote.RealtimeState.Connected -> "Live connection to your guardian circle"
                        is com.guardian.safety.remote.RealtimeState.Reconnecting -> "Reconnecting to your guardian circle…"
                        is com.guardian.safety.remote.RealtimeState.Failed ->
                            "Live updates are unavailable: ${(realtimeState as com.guardian.safety.remote.RealtimeState.Failed).reason}"
                        else -> "Your family group members appear here"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (members.isEmpty()) {
                item {
                    Text(
                        text = "No family members yet. Open More → Family hub to create a group or join one with an invite code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }

            items(members, key = { it.memberId }) { member ->
                val isOnline = presence.any { it.userId == member.userId && it.status == "ONLINE" } || member.online
                GuardianCard(
                    name = member.name,
                    relation = member.role.lowercase().replaceFirstChar { it.uppercase() },
                    status = when {
                        isOnline -> "Online now"
                        member.lastSeenAt != null -> "Last seen ${relativeTime(member.lastSeenAt!!)}"
                        else -> "Offline"
                    },
                    online = isOnline,
                    // Family members are identified by account, not by phone
                    // number, so there may be nothing to dial. A blank number
                    // disables the button instead of leaving it a silent no-op.
                    phoneNumber = member.phone.takeIf { it.isNotBlank() },
                    onCall = { number ->
                        HapticUtils.triggerHaptic(context, isHeavy = true)
                        val outcome = EmergencyCallManager.callNumber(context, number)
                        if (outcome !is EmergencyActionResult.Dispatched) {
                            viewModel.reportActionProblem(outcome.describe())
                        }
                    },
                )
            }

            if (contacts.isNotEmpty()) {
                item {
                    Text(
                        text = "EMERGENCY CONTACTS",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(contacts, key = { it.id }) { contact ->
                    GuardianCard(
                        name = contact.name,
                        relation = contact.relationship,
                        status = if (contact.isVerified) {
                            "Verified • ${contact.phone}"
                        } else {
                            "Awaiting verification • ${contact.phone}"
                        },
                        online = contact.isVerified,
                        phoneNumber = contact.phone.takeIf { it.isNotBlank() },
                        onCall = { number ->
                            HapticUtils.triggerHaptic(context, isHeavy = true)
                            val outcome = EmergencyCallManager.callNumber(context, number)
                            if (outcome !is EmergencyActionResult.Dispatched) {
                                viewModel.reportActionProblem(outcome.describe())
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * One guardian or emergency contact.
 *
 * The call button is only enabled when there is a real number to dial, and the
 * caller reports the actual [EmergencyActionResult] — a dialler hand-off is never
 * presented as a placed call.
 */
@Composable
fun GuardianCard(
    name: String,
    relation: String,
    status: String,
    online: Boolean,
    phoneNumber: String? = null,
    onCall: (String) -> Unit = {},
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
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(AccentPurple.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.take(2).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = AccentPurple,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(relation, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (online) SuccessGreen else TextSecondary)
                        )
                        Text(status, style = MaterialTheme.typography.labelSmall, color = if (online) SuccessGreen else TextSecondary)
                    }
                }
            }

            IconButton(
                onClick = { phoneNumber?.let(onCall) },
                enabled = phoneNumber != null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(DarkSurface)
            ) {
                Icon(
                    Icons.Default.Phone,
                    contentDescription = if (phoneNumber != null) {
                        "Call $name"
                    } else {
                        "No phone number saved for $name"
                    },
                    tint = if (phoneNumber != null) TextPrimary else TextSecondary,
                )
            }
        }
    }
}
