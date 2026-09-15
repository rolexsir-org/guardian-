package com.guardian.safety.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.guardian.safety.service.SessionState
import com.guardian.safety.ui.auth.SignInScreen
import com.guardian.safety.ui.components.BiometricProtectedScreen
import com.guardian.safety.ui.contacts.ContactsScreen
import com.guardian.safety.ui.family.FamilyChatScreen
import com.guardian.safety.ui.family.FamilyHubScreen
import com.guardian.safety.ui.family.FamilyMapScreen
import com.guardian.safety.ui.family.ParentalControlsScreen
import com.guardian.safety.ui.guardians.GuardiansScreen
import com.guardian.safety.ui.hazards.SafetyHazardsMapScreen
import com.guardian.safety.ui.home.HomeScreen
import com.guardian.safety.ui.incidents.IncidentsScreen
import com.guardian.safety.ui.live.LiveMapScreen
import com.guardian.safety.ui.medical.MedicalProfileScreen
import com.guardian.safety.ui.more.MoreScreen
import com.guardian.safety.ui.protection.ProtectionScreen
import com.guardian.safety.ui.protocols.ProtocolsScreen
import com.guardian.safety.ui.sos.CommunityResponderScreen
import com.guardian.safety.ui.sos.EmergencyActiveScreen
import com.guardian.safety.ui.theme.*

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Shield)
    data object Protection : Screen("protection", "Protection", Icons.Default.Security)
    data object Live : Screen("live", "Live", Icons.Default.Map)
    data object Guardians : Screen("guardians", "Guardians", Icons.Default.Group)
    data object More : Screen("more", "More", Icons.Default.Menu)
}

object Routes {
    const val MEDICAL_PROFILE = "medical_profile"
    const val EMERGENCY_CONTACTS = "emergency_contacts"
    const val INCIDENTS = "incidents_reports"
    const val SAFETY_EVENTS_MAP = "safety_events_map"
    const val FAMILY_HUB = "family_hub"
    const val FAMILY_CHAT = "family_chat"
    const val FAMILY_MAP = "family_map"
    const val PARENTAL_CONTROLS = "parental_controls"
    const val PROTOCOLS = "protocols"
    const val RESPONDER = "community_responder"
}

@Composable
fun GuardianApp(viewModel: GuardianViewModel) {
    val sessionState by viewModel.sessionState.collectAsState()

    when (sessionState) {
        SessionState.Restoring -> RestoringScreen()
        is SessionState.SignedOut -> SignInScreen(viewModel)
        is SessionState.SignedIn -> SignedInApp(viewModel = viewModel)
    }
}

@Composable
private fun RestoringScreen() {
    Surface(color = DarkBackground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = PrimaryRed)
            Text(
                text = "Checking your secure session…",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun SignedInApp(viewModel: GuardianViewModel) {
    val navController = rememberNavController()
    val sosActive by viewModel.sosActive.collectAsState()
    val dataState by viewModel.dataState.collectAsState()
    val context = LocalContext.current

    val items = listOf(Screen.Home, Screen.Protection, Screen.Live, Screen.Guardians, Screen.More)

    if (sosActive != null) {
        EmergencyActiveScreen(
            viewModel = viewModel,
            onCancelSos = { viewModel.cancelSos() },
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBackground,
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = TextPrimary,
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            com.guardian.safety.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryRed,
                            selectedTextColor = PrimaryRed,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = DarkCard,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Failures and confirmations are always visible: never a silent no-op.
            when (val state = dataState) {
                is DataState.Failure -> StatusBanner(message = state.message, isError = true) { viewModel.clearDataMessage() }
                is DataState.Success -> StatusBanner(message = state.message, isError = false) { viewModel.clearDataMessage() }
                DataState.Loading -> StatusBanner(message = "Working…", isError = false, onDismiss = null)
                DataState.Idle -> Unit
            }

            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        viewModel = viewModel,
                        onTriggerSos = { viewModel.triggerSos("BUTTON") },
                        onNavigateToLive = { navController.navigate(Screen.Live.route) },
                    )
                }
                composable(Screen.Protection.route) {
                    BiometricProtectedScreen(
                        title = "SOS & protection configuration",
                        onBack = { navController.popBackStack() },
                    ) {
                        ProtectionScreen(viewModel)
                    }
                }
                composable(Screen.Live.route) { LiveMapScreen(viewModel) }
                composable(Screen.Guardians.route) { GuardiansScreen(viewModel) }
                composable(Screen.More.route) {
                    MoreScreen(
                        viewModel = viewModel,
                        onNavigateToMedical = { navController.navigate(Routes.MEDICAL_PROFILE) },
                        onNavigateToContacts = { navController.navigate(Routes.EMERGENCY_CONTACTS) },
                        onNavigateToIncidents = { navController.navigate(Routes.INCIDENTS) },
                        onNavigateToSafetyEvents = { navController.navigate(Routes.SAFETY_EVENTS_MAP) },
                        onNavigateToFamilyHub = { navController.navigate(Routes.FAMILY_HUB) },
                        onNavigateToProtocols = { navController.navigate(Routes.PROTOCOLS) },
                        onNavigateToResponder = { navController.navigate(Routes.RESPONDER) },
                    )
                }
                composable(Routes.MEDICAL_PROFILE) { MedicalProfileScreen(viewModel) }
                composable(Routes.EMERGENCY_CONTACTS) {
                    BiometricProtectedScreen(
                        title = "Trusted contacts",
                        onBack = { navController.popBackStack() },
                    ) {
                        ContactsScreen(viewModel)
                    }
                }
                composable(Routes.INCIDENTS) { IncidentsScreen(viewModel) }
                composable(Routes.SAFETY_EVENTS_MAP) { SafetyHazardsMapScreen(viewModel) }
                composable(Routes.FAMILY_HUB) {
                    FamilyHubScreen(
                        viewModel = viewModel,
                        onNavigateToMap = { navController.navigate(Routes.FAMILY_MAP) },
                        onNavigateToControls = { navController.navigate(Routes.PARENTAL_CONTROLS) },
                        onNavigateToChat = { navController.navigate(Routes.FAMILY_CHAT) },
                    )
                }
                composable(Routes.FAMILY_CHAT) {
                    FamilyChatScreen(viewModel, onBack = { navController.popBackStack() })
                }
                composable(Routes.FAMILY_MAP) {
                    FamilyMapScreen(viewModel, onBack = { navController.popBackStack() })
                }
                composable(Routes.PARENTAL_CONTROLS) {
                    ParentalControlsScreen(viewModel, onBack = { navController.popBackStack() })
                }
                composable(Routes.PROTOCOLS) {
                    ProtocolsScreen(
                        onBack = { navController.popBackStack() },
                        onCallProblem = { viewModel.reportActionProblem(it) },
                    )
                }
                composable(Routes.RESPONDER) {
                    CommunityResponderScreen(viewModel, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

/** Inline status banner used for every operation result. */
@Composable
private fun StatusBanner(message: String, isError: Boolean, onDismiss: (() -> Unit)?) {
    Surface(
        color = if (isError) PrimaryRed.copy(alpha = 0.2f) else DarkCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = if (isError) PrimaryRed else TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
