package com.guardian.safety.ui.more

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guardian.safety.ui.GuardianViewModel
import com.guardian.safety.ui.theme.*

@Composable
fun MoreScreen(
    viewModel: GuardianViewModel,
    onNavigateToMedical: () -> Unit,
    onNavigateToContacts: () -> Unit,
    onNavigateToIncidents: () -> Unit,
    onNavigateToSafetyEvents: () -> Unit,
    onNavigateToFamilyHub: () -> Unit,
    onNavigateToProtocols: () -> Unit,
    onNavigateToResponder: () -> Unit,
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val accountLabel = (sessionState as? com.guardian.safety.service.SessionState.SignedIn)
        ?.session?.email ?: "Not signed in"
    val proState by viewModel.proState.collectAsState()
    var showProDialog by remember { mutableStateOf(false) }
    // The store sheet needs a foreground activity; without one the button is not shown.
    val activity = (LocalContext.current as? Activity)

    LaunchedEffect(Unit) { viewModel.refreshProState() }

    if (showProDialog) {
        GuardianProDialog(
            state = proState,
            onDismiss = { showProDialog = false },
            onPurchase = activity?.let { host -> { viewModel.purchasePro(host) } },
            onRestore = { viewModel.restoreProPurchases() },
        )
    }
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
                    text = "COMMAND CENTER",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Profiles, Incident Vault & Emergency Controls",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                MoreTile(title = "Medical ID & Emergency Profile", subtitle = "Blood type, allergies, medications", icon = Icons.Default.MedicalServices, onClick = onNavigateToMedical)
            }
            item {
                MoreTile(title = "Trusted Emergency Contacts", subtitle = "Manage guardian ring & phone dispatch", icon = Icons.Default.Group, onClick = onNavigateToContacts)
            }
            item {
                MoreTile(title = "Secure Incident Evidence Vault", subtitle = "Encrypted audio, video & location logs", icon = Icons.Default.Lock, onClick = onNavigateToIncidents)
            }
            item {
                MoreTile(title = "Community safety events", subtitle = "Live reports around your real location", icon = Icons.Default.Map, onClick = onNavigateToSafetyEvents)
            }
            item {
                MoreTile(title = "Family hub", subtitle = "Group, members, chat and location sharing", icon = Icons.Default.FamilyRestroom, onClick = onNavigateToFamilyHub)
            }
            item {
                MoreTile(title = "Emergency protocols", subtitle = "First-aid and emergency guidance", icon = Icons.Default.MenuBook, onClick = onNavigateToProtocols)
            }
            item {
                MoreTile(title = "Community responders", subtitle = "Help nearby Guardian users", icon = Icons.Default.VolunteerActivism, onClick = onNavigateToResponder)
            }
            item {
                MoreTile(
                    title = "Guardian Pro",
                    subtitle = when (proState) {
                        is com.guardian.safety.billing.ProState.Active -> "Active — thanks for supporting Guardian"
                        is com.guardian.safety.billing.ProState.NotConfigured -> "Subscriptions are not available in this build"
                        is com.guardian.safety.billing.ProState.Error -> (proState as com.guardian.safety.billing.ProState.Error).message
                        else -> "Optional. Every safety feature stays free"
                    },
                    icon = Icons.Default.WorkspacePremium,
                    onClick = { showProDialog = true },
                )
            }
            item {
                MoreTile(
                    title = "Account",
                    subtitle = "$accountLabel • sign out of this device",
                    icon = Icons.Default.Logout,
                    onClick = { viewModel.signOut() },
                )
            }
        }
    }
}

@Composable
fun MoreTile(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
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
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AccentPurple.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = AccentPurple)
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
        }
    }
}


/**
 * Guardian Pro details.
 *
 * Everything shown here comes from RevenueCat: the entitlement state, the real
 * product price, and the outcome of a purchase or restore. When subscriptions are
 * not configured for this build the dialog says so instead of offering a button that
 * cannot do anything.
 */
@Composable
fun GuardianProDialog(
    state: com.guardian.safety.billing.ProState,
    onDismiss: () -> Unit,
    onPurchase: (() -> Unit)?,
    onRestore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = { Text("Guardian Pro", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (state) {
                        is com.guardian.safety.billing.ProState.Active -> {
                            val until = state.expiresAt
                            if (until != null) {
                                "Active until " + java.text.DateFormat.getDateInstance().format(java.util.Date(until)) +
                                    ". Guardian Pro is handled by RevenueCat and the store; it never touches your safety data."
                            } else {
                                "Active. Guardian Pro is handled by RevenueCat and the store; it never touches your safety data."
                            }
                        }
                        is com.guardian.safety.billing.ProState.NotConfigured ->
                            "This build has no store configuration, so Guardian Pro cannot be purchased here. " +
                                "Every safety feature works without it."
                        is com.guardian.safety.billing.ProState.Error ->
                            state.message + " Nothing has been charged. Every safety feature still works."
                        is com.guardian.safety.billing.ProState.Inactive ->
                            "Guardian Pro supports development and unlocks nothing that is needed in an emergency: " +
                                "SOS, contacts, location alerts and evidence all stay free."
                        com.guardian.safety.billing.ProState.Unknown -> "Checking your subscription..."
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRestore) { Text("Restore purchases", color = AccentPurple) }
                    if (state !is com.guardian.safety.billing.ProState.NotConfigured &&
                        state !is com.guardian.safety.billing.ProState.Active
                    ) {
                        if (onPurchase != null) {
                            TextButton(onClick = onPurchase) { Text("Subscribe", color = SuccessGreen) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) } },
    )
}
