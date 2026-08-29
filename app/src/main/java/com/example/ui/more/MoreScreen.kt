package com.example.ui.more

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.GuardianViewModel
import com.example.ui.theme.*

@Composable
fun MoreScreen(
    viewModel: GuardianViewModel,
    onNavigateToMedical: () -> Unit,
    onNavigateToContacts: () -> Unit,
    onNavigateToIncidents: () -> Unit,
    onNavigateToFirebaseHazards: () -> Unit,
    onNavigateToAi: (() -> Unit)? = null
) {
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
                MoreTile(title = "Firebase Real-Time Hazards Map", subtitle = "Live Firebase DB safety incidents & radar", icon = Icons.Default.Map, onClick = onNavigateToFirebaseHazards)
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
