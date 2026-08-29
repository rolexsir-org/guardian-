package com.example.ui.contacts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ContactEntity
import com.example.data.MedicalProfileEntity
import com.example.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(viewModel: GuardianViewModel) {
    val contacts by viewModel.contacts.collectAsState()
    val checkins by viewModel.checkins.collectAsState()
    val medicalProfile by viewModel.medicalProfile.collectAsState(initial = null)
    val contactStatuses by viewModel.contactStatuses.collectAsState()
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showCheckinDialog by remember { mutableStateOf(false) }
    var showEditMedicalDialog by remember { mutableStateOf(false) }
    var contactToEdit by remember { mutableStateOf<ContactEntity?>(null) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trusted Contacts & Check-In", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                        showCheckinDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                ) {
                    Icon(imageVector = Icons.Default.Timer, contentDescription = "Safety Timer")
                }
                FloatingActionButton(
                    onClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                        showAddContactDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Contact")
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                MedicalProfileCard(
                    profile = medicalProfile,
                    onEditClick = {
                        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                        showEditMedicalDialog = true
                    }
                )
            }

            // Safety Timer Status Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Walk With Me Safety Timer",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Set a check-in timer when walking alone. If you don't check in, Guardian alerts your trusted contacts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                                showCheckinDialog = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Safety Check-In Timer")
                        }

                        if (checkins.isNotEmpty()) {
                            val activeCheckin = checkins.firstOrNull { it.status == "Active" }
                            if (activeCheckin != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(text = "Active Timer: ${activeCheckin.durationMinutes} mins", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                            Text(text = "Note: ${activeCheckin.note}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Button(
                                            onClick = {
                                                com.example.util.HapticUtils.triggerHaptic(context, isHeavy = true)
                                                viewModel.updateCheckinStatus(activeCheckin, "Completed")
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                        ) {
                                            Text("I'm Safe", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Emergency & Trusted Contacts",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "SOS alerts sent to verified only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            items(contacts) { contact ->
                val isOnline = contactStatuses[contact.id] ?: (contact.id % 2L == 0L)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(text = contact.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Surface(
                                    color = if (contact.isVerified) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFFFA726).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = if (contact.isVerified) "Verified ✓" else "Unverified ⚠️",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = if (contact.isVerified) Color(0xFF2E7D32) else Color(0xFFE65100),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "${contact.relationship} • ${contact.phone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone}"))
                                    context.startActivity(intent)
                                }
                            ) {
                                Icon(imageVector = Icons.Default.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(
                                onClick = {
                                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                                    contactToEdit = contact
                                }
                            ) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(
                                onClick = {
                                    com.example.util.HapticUtils.triggerHaptic(context, isHeavy = true)
                                    viewModel.deleteContact(contact)
                                }
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddContactDialog) {
        AddContactDialog(
            onDismiss = { showAddContactDialog = false },
            onSubmit = { name, phone, relationship, isVerified ->
                viewModel.addContact(name, phone, relationship, isVerified)
                showAddContactDialog = false
            }
        )
    }

    if (contactToEdit != null) {
        EditContactDialog(
            contact = contactToEdit!!,
            onDismiss = { contactToEdit = null },
            onSubmit = { updated ->
                viewModel.updateContact(updated)
                contactToEdit = null
            }
        )
    }

    if (showCheckinDialog) {
        AddCheckinDialog(
            onDismiss = { showCheckinDialog = false },
            onSubmit = { duration, note ->
                viewModel.startCheckin(duration, note)
                showCheckinDialog = false
            }
        )
    }

    if (showEditMedicalDialog) {
        EditMedicalProfileDialog(
            currentProfile = medicalProfile,
            onDismiss = { showEditMedicalDialog = false },
            onSubmit = { name, blood, allergies, conditions, meds, notes, doctor, insurance ->
                viewModel.updateMedicalProfile(name, blood, allergies, conditions, meds, notes, doctor, insurance)
                showEditMedicalDialog = false
            }
        )
    }
}

@Composable
fun MedicalProfileCard(profile: MedicalProfileEntity?, onEditClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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
                Text(
                    text = "Emergency Medical Profile",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                TextButton(onClick = onEditClick) {
                    Text("Edit")
                }
            }
            if (profile != null && profile.name.isNotBlank()) {
                Text("Name: ${profile.name} • Blood: ${profile.bloodGroup.ifBlank { "N/A" }}", style = MaterialTheme.typography.bodyMedium)
                if (profile.allergies.isNotBlank()) Text("Allergies: ${profile.allergies}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (profile.medicalConditions.isNotBlank()) Text("Conditions: ${profile.medicalConditions}", style = MaterialTheme.typography.bodySmall)
                if (profile.medications.isNotBlank()) Text("Medications: ${profile.medications}", style = MaterialTheme.typography.bodySmall)
                if (profile.doctorContact.isNotBlank()) Text("Doctor: ${profile.doctorContact}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    text = "No medical profile configured. Tap Edit to add critical medical information for first responders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun EditMedicalProfileDialog(
    currentProfile: MedicalProfileEntity?,
    onDismiss: () -> Unit,
    onSubmit: (String, String, String, String, String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(currentProfile?.name ?: "") }
    var bloodGroup by remember { mutableStateOf(currentProfile?.bloodGroup ?: "") }
    var allergies by remember { mutableStateOf(currentProfile?.allergies ?: "") }
    var medicalConditions by remember { mutableStateOf(currentProfile?.medicalConditions ?: "") }
    var medications by remember { mutableStateOf(currentProfile?.medications ?: "") }
    var emergencyNotes by remember { mutableStateOf(currentProfile?.emergencyNotes ?: "") }
    var doctorContact by remember { mutableStateOf(currentProfile?.doctorContact ?: "") }
    var insuranceInfo by remember { mutableStateOf(currentProfile?.insuranceInfo ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emergency Medical Profile", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(350.dp)) {
                item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(value = bloodGroup, onValueChange = { bloodGroup = it }, label = { Text("Blood Group (e.g., O+, A-)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(value = allergies, onValueChange = { allergies = it }, label = { Text("Allergies") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(value = medicalConditions, onValueChange = { medicalConditions = it }, label = { Text("Medical Conditions") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(value = medications, onValueChange = { medications = it }, label = { Text("Medications") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(value = doctorContact, onValueChange = { doctorContact = it }, label = { Text("Primary Doctor & Phone") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                item { OutlinedTextField(value = insuranceInfo, onValueChange = { insuranceInfo = it }, label = { Text("Insurance Details (Optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(name, bloodGroup, allergies, medicalConditions, medications, emergencyNotes, doctorContact, insuranceInfo) }) {
                Text("Save Profile")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddContactDialog(onDismiss: () -> Unit, onSubmit: (String, String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("Family") }
    var isVerified by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Trusted Contact", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Contact Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = relationship, onValueChange = { relationship = it }, label = { Text("Relationship (Family, Friend, ICE)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Verified for Emergency SOS")
                    Switch(checked = isVerified, onCheckedChange = { isVerified = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank() && phone.isNotBlank()) onSubmit(name, phone, relationship, isVerified) }) {
                Text("Add Contact")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun EditContactDialog(
    contact: ContactEntity,
    onDismiss: () -> Unit,
    onSubmit: (ContactEntity) -> Unit
) {
    var name by remember { mutableStateOf(contact.name) }
    var phone by remember { mutableStateOf(contact.phone) }
    var relationship by remember { mutableStateOf(contact.relationship) }
    var isVerified by remember { mutableStateOf(contact.isVerified) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Trusted Contact", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Contact Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = relationship, onValueChange = { relationship = it }, label = { Text("Relationship") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Verified for Emergency SOS")
                    Switch(checked = isVerified, onCheckedChange = { isVerified = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isNotBlank() && phone.isNotBlank()) {
                    onSubmit(contact.copy(name = name, phone = phone, relationship = relationship, isVerified = isVerified))
                }
            }) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddCheckinDialog(onDismiss: () -> Unit, onSubmit: (Int, String) -> Unit) {
    var durationText by remember { mutableStateOf("15") }
    var note by remember { mutableStateOf("Walking home from station") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start Safety Check-In Timer", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = durationText, onValueChange = { durationText = it }, label = { Text("Duration (Minutes)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Activity Note") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                val duration = durationText.toIntOrNull() ?: 15
                onSubmit(duration, note)
            }) {
                Text("Start Timer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
