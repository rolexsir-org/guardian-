package com.guardian.safety

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.guardian.safety.ui.GuardianApp
import com.guardian.safety.ui.GuardianViewModel
import com.guardian.safety.ui.GuardianViewModelFactory
import com.guardian.safety.ui.theme.GuardianTheme

class MainActivity : FragmentActivity() {

    private val viewModel: GuardianViewModel by viewModels {
        GuardianViewModelFactory(GuardianApplication.containerFrom(this) ?: error("Guardian container is missing"))
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onNotificationPermissionResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()

        setContent {
            GuardianTheme {
                GuardianApp(viewModel = viewModel)
            }
        }

        // The user is told why the permission is needed by the UI before it is asked;
        // the request itself only runs when the OS still needs it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Restores the emergency banner after process death instead of pretending
        // the emergency never happened.
        viewModel.restoreActiveEmergency()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val emergency = NotificationChannel(
            CHANNEL_EMERGENCY,
            "Emergency alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Emergency alerts and family safety notifications"
            enableVibration(true)
        }
        val safety = NotificationChannel(
            CHANNEL_SAFETY,
            "Safety updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Community safety events and location sharing updates"
        }
        manager.createNotificationChannels(listOf(emergency, safety))
    }

    companion object {
        const val CHANNEL_EMERGENCY = "guardian_emergency"
        const val CHANNEL_SAFETY = "guardian_safety"
    }
}
