package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.home.HomeScreen
import com.example.ui.protection.ProtectionScreen
import com.example.ui.live.LiveMapScreen
import com.example.ui.guardians.GuardiansScreen
import com.example.ui.more.MoreScreen
import com.example.ui.sos.EmergencyActiveScreen
import com.example.ui.medical.MedicalProfileScreen
import com.example.ui.contacts.ContactsScreen
import com.example.ui.incidents.IncidentsScreen
import com.example.ui.components.BiometricProtectedScreen
import com.example.ui.hazards.FirebaseHazardsMapScreen
import com.example.ui.theme.*

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Shield)
    object Protection : Screen("protection", "Protection", Icons.Default.Security)
    object Live : Screen("live", "Live", Icons.Default.Map)
    object Guardians : Screen("guardians", "Guardians", Icons.Default.Group)
    object More : Screen("more", "More", Icons.Default.Menu)
}

@Composable
fun GuardianApp(viewModel: GuardianViewModel) {
    val navController = rememberNavController()
    val isSosActive by viewModel.isSosActive.collectAsState()

    val items = listOf(
        Screen.Home,
        Screen.Protection,
        Screen.Live,
        Screen.Guardians,
        Screen.More
    )

    if (isSosActive) {
        EmergencyActiveScreen(
            viewModel = viewModel,
            onCancelSos = {
                // reset SOS active state if needed or add method
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                val context = androidx.compose.ui.platform.LocalContext.current
                NavigationBar(
                    containerColor = DarkSurface,
                    contentColor = TextPrimary
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentRoute == screen.route,
                            onClick = {
                                com.example.util.HapticUtils.triggerHaptic(context, isHeavy = false)
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
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
                                indicatorColor = DarkCard
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        viewModel = viewModel,
                        onTriggerSos = {
                            // trigger SOS
                        },
                        onNavigateToLive = {
                            navController.navigate(Screen.Live.route)
                        }
                    )
                }
                composable(Screen.Protection.route) {
                    BiometricProtectedScreen(
                        title = "SOS & Protection Configuration",
                        onBack = { navController.popBackStack() }
                    ) {
                        ProtectionScreen(viewModel)
                    }
                }
                composable(Screen.Live.route) {
                    LiveMapScreen(viewModel)
                }
                composable(Screen.Guardians.route) {
                    GuardiansScreen(viewModel)
                }
                composable(Screen.More.route) {
                    MoreScreen(
                        viewModel = viewModel,
                        onNavigateToMedical = { navController.navigate("medical_profile") },
                        onNavigateToContacts = { navController.navigate("emergency_contacts") },
                        onNavigateToIncidents = { navController.navigate("incidents_reports") },
                        onNavigateToFirebaseHazards = { navController.navigate("firebase_hazards_map") }
                    )
                }
                composable("medical_profile") {
                    MedicalProfileScreen(viewModel)
                }
                composable("emergency_contacts") {
                    BiometricProtectedScreen(
                        title = "Trusted Contacts",
                        onBack = { navController.popBackStack() }
                    ) {
                        ContactsScreen(viewModel)
                    }
                }
                composable("incidents_reports") {
                    IncidentsScreen(viewModel)
                }
                composable("firebase_hazards_map") {
                    FirebaseHazardsMapScreen(viewModel)
                }
            }
        }
    }
}
