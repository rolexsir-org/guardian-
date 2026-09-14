package com.guardian.safety.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.guardian.safety.ui.DataState
import com.guardian.safety.ui.GuardianViewModel
import com.guardian.safety.ui.theme.DarkCard
import com.guardian.safety.ui.theme.DarkSurface
import com.guardian.safety.ui.theme.PrimaryRed
import com.guardian.safety.ui.theme.TextPrimary
import com.guardian.safety.ui.theme.TextSecondary

/**
 * Sign-in and sign-up against the Guardian service.
 *
 * The app has no offline identity: without a real session the protected screens are
 * not reachable, and a failed sign-in shows the server's own reason instead of
 * letting the user in with a placeholder account.
 */
@Composable
fun SignInScreen(viewModel: GuardianViewModel) {
    var isRegistering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    val authState by viewModel.authState.collectAsState()
    val backendConfigured = viewModel.isBackendConfigured

    Surface(modifier = Modifier.fillMaxSize(), color = DarkSurface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = PrimaryRed,
                modifier = Modifier.height(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "GUARDIAN",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (isRegistering) "Create your safety account" else "Sign in to your safety account",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            if (!backendConfigured) {
                Surface(
                    color = DarkCard,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = viewModel.backendConfigurationError
                            ?: "This build of Guardian has no backend configured, so signing in is unavailable.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            if (isRegistering) {
                AuthField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = "Full name",
                    icon = Icons.Default.Person,
                    keyboardType = KeyboardType.Text,
                )
                Spacer(Modifier.height(12.dp))
            }

            AuthField(
                value = email,
                onValueChange = { email = it },
                label = "Email",
                icon = Icons.Default.Email,
                keyboardType = KeyboardType.Email,
            )
            Spacer(Modifier.height(12.dp))
            AuthField(
                value = password,
                onValueChange = { password = it },
                label = if (isRegistering) {
                    "Password (at least ${GuardianViewModel.MIN_PASSWORD_LENGTH} characters)"
                } else {
                    "Password"
                },
                icon = Icons.Default.Lock,
                keyboardType = KeyboardType.Password,
                isPassword = true,
            )

            if (isRegistering) {
                Spacer(Modifier.height(12.dp))
                AuthField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = "Mobile number (optional)",
                    icon = Icons.Default.Phone,
                    keyboardType = KeyboardType.Phone,
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    if (isRegistering) {
                        viewModel.signUp(email, password, displayName, phone.ifBlank { null })
                    } else {
                        viewModel.signIn(email, password)
                    }
                },
                enabled = backendConfigured && authState !is DataState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (authState is DataState.Loading) {
                    CircularProgressIndicator(color = TextPrimary, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (isRegistering) "Create account" else "Sign in",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { isRegistering = !isRegistering }) {
                Text(
                    text = if (isRegistering) {
                        "Already have an account? Sign in"
                    } else {
                        "New to Guardian? Create an account"
                    },
                    color = TextSecondary,
                )
            }

            when (val state = authState) {
                is DataState.Failure -> AuthMessage(state.message, isError = true)
                is DataState.Success -> AuthMessage(state.message, isError = false)
                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Guardian never stores your password on the device. " +
                    "Credentials are kept in the device's encrypted keystore.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = TextSecondary) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = PrimaryRed,
            unfocusedBorderColor = TextSecondary,
            cursorColor = PrimaryRed,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AuthMessage(message: String, isError: Boolean) {
    Surface(
        color = if (isError) PrimaryRed.copy(alpha = 0.15f) else DarkCard,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    color = if (isError) PrimaryRed else TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
