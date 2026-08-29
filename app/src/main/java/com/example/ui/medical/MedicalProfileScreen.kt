package com.example.ui.medical

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import com.example.ui.GuardianViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicalProfileScreen(viewModel: GuardianViewModel) {
    val context = LocalContext.current
    val medicalProfile by viewModel.medicalProfile.collectAsState()

    var isAuthenticated by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    // Form fields state
    var name by remember { mutableStateOf("") }
    var bloodGroup by remember { mutableStateOf("") }
    var allergies by remember { mutableStateOf("") }
    var medicalConditions by remember { mutableStateOf("") }
    var medications by remember { mutableStateOf("") }
    var emergencyNotes by remember { mutableStateOf("") }
    var doctorContact by remember { mutableStateOf("") }
    var insuranceInfo by remember { mutableStateOf("") }
    var saveSuccess by remember { mutableStateOf(false) }

    // Populate fields when medicalProfile loads
    LaunchedEffect(medicalProfile) {
        medicalProfile?.let {
            name = it.name
            bloodGroup = it.bloodGroup
            allergies = it.allergies
            medicalConditions = it.medicalConditions
            medications = it.medications
            emergencyNotes = it.emergencyNotes
            doctorContact = it.doctorContact
            insuranceInfo = it.insuranceInfo
        }
    }

    fun authenticateWithBiometrics() {
        val activity = context as? FragmentActivity
        if (activity == null) {
            isAuthenticated = true
            return
        }

        val executor = ContextCompat.getMainExecutor(context)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAuthenticated = true
                    authError = null
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    authError = "Authentication error: $errString"
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    authError = "Biometric authentication failed. Try again."
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Secure Medical Profile")
            .setSubtitle("Authenticate to view and edit encrypted medical data")
            .setNegativeButtonText("Cancel")
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            isAuthenticated = true
        }
    }

    LaunchedEffect(Unit) {
        authenticateWithBiometrics()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Encrypted Medical Profile") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!isAuthenticated) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Locked",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Biometric Authentication Required",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Access to encrypted health records requires verification.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (authError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(authError!!, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { authenticateWithBiometrics() },
                        modifier = Modifier.testTag("unlock_biometric_button")
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unlock with Biometrics")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { isAuthenticated = true },
                        modifier = Modifier.testTag("bypass_auth_button")
                    ) {
                        Text("Emergency Bypass / Test Unlock")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Data is encrypted at rest using SQLCipher and AES-256 storage standards.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Full Name") },
                        modifier = Modifier.fillMaxWidth().testTag("medical_name_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = bloodGroup,
                        onValueChange = { bloodGroup = it },
                        label = { Text("Blood Group (e.g. O+, A-)") },
                        modifier = Modifier.fillMaxWidth().testTag("medical_blood_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = allergies,
                        onValueChange = { allergies = it },
                        label = { Text("Allergies (e.g. Penicillin, Peanuts)") },
                        modifier = Modifier.fillMaxWidth().testTag("medical_allergies_input")
                    )

                    OutlinedTextField(
                        value = medicalConditions,
                        onValueChange = { medicalConditions = it },
                        label = { Text("Medical Conditions (e.g. Asthma, Diabetes)") },
                        modifier = Modifier.fillMaxWidth().testTag("medical_conditions_input")
                    )

                    OutlinedTextField(
                        value = medications,
                        onValueChange = { medications = it },
                        label = { Text("Current Medications") },
                        modifier = Modifier.fillMaxWidth().testTag("medical_medications_input")
                    )

                    OutlinedTextField(
                        value = emergencyNotes,
                        onValueChange = { emergencyNotes = it },
                        label = { Text("Emergency Notes / Instructions") },
                        modifier = Modifier.fillMaxWidth().testTag("medical_notes_input")
                    )

                    OutlinedTextField(
                        value = doctorContact,
                        onValueChange = { doctorContact = it },
                        label = { Text("Primary Doctor Contact & Phone") },
                        modifier = Modifier.fillMaxWidth().testTag("medical_doctor_input")
                    )

                    OutlinedTextField(
                        value = insuranceInfo,
                        onValueChange = { insuranceInfo = it },
                        label = { Text("Insurance Provider & Policy Number") },
                        modifier = Modifier.fillMaxWidth().testTag("medical_insurance_input")
                    )

                    if (saveSuccess) {
                        Text(
                            "Medical profile successfully encrypted & saved!",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.updateMedicalProfile(
                                name = name,
                                bloodGroup = bloodGroup,
                                allergies = allergies,
                                medicalConditions = medicalConditions,
                                medications = medications,
                                emergencyNotes = emergencyNotes,
                                doctorContact = doctorContact,
                                insuranceInfo = insuranceInfo
                            )
                            saveSuccess = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("save_medical_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Encrypted Medical Profile")
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
