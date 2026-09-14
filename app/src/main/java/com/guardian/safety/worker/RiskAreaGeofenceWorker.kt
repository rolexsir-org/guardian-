package com.guardian.safety.worker

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.guardian.safety.GuardianApplication
import com.guardian.safety.MainActivity
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.service.LocationFailure
import com.guardian.safety.service.LocationService
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Warns the user when a community safety event is close by.
 *
 * Replaces the previous realtime-database version, which compared against a
 * hard-coded San Francisco coordinate whenever no device fix was available. This
 * worker requires a genuine provider fix; without a permission or a fix it simply
 * does nothing (it never guesses where the user is) and it will not alert twice for
 * the same event.
 */
class RiskAreaGeofenceWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? GuardianApplication
            ?: return Result.success()
        val container = application.container
        if (!container.isCloudConfigured) {
            Log.i(TAG, "Backend is not configured; nothing to check.")
            return Result.success()
        }
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Location permission not granted; skipping risk check.")
            return Result.success()
        }

        // Only run for a signed-in user: community events are personalised to the account.
        val session = container.sessionManager.restore()
        if (session !is com.guardian.safety.service.SessionState.SignedIn) {
            Log.i(TAG, "No signed-in session; skipping risk check.")
            return Result.success()
        }

        val locationService = LocationService(applicationContext)
        val (location, failure) = locationService.currentLocation(timeoutMs = 15_000L)
        if (location == null) {
            // No real fix: report nothing rather than alerting for the wrong place.
            Log.i(TAG, "No location fix (${failure ?: LocationFailure.Unavailable}); skipping risk check.")
            return Result.success()
        }

        return try {
            when (
                val result = container.repository.refreshNearbySafetyEvents(
                    location = location,
                    radiusMeters = ALERT_RADIUS_METERS,
                )
            ) {
                is ApiResult.Success -> {
                    notifyNearestEvent(location)
                    Result.success()
                }
                is ApiResult.Failure -> {
                    if (result.error.offline || result.error.retryable) Result.retry() else Result.success()
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Risk-area check failed", error)
            Result.retry()
        }
    }

    private suspend fun notifyNearestEvent(userLocation: Location) {
        val incidents = container().repository.getAllIncidentsOnce()
        val activeEvents = incidents.filter { it.status == "Active" }

        var nearest: com.guardian.safety.data.IncidentEntity? = null
        var nearestDistance = Double.MAX_VALUE
        activeEvents.forEach { event ->
            val lat = event.latitude ?: return@forEach
            val lng = event.longitude ?: return@forEach
            val distance = distanceMeters(userLocation.latitude, userLocation.longitude, lat, lng)
            if (distance < nearestDistance) {
                nearest = event
                nearestDistance = distance
            }
        }

        val event = nearest ?: return
        if (nearestDistance > ALERT_RADIUS_METERS) return
        if (!shouldAlert(event.id)) return

        val distanceText = if (nearestDistance < 1_000) {
            "${nearestDistance.toInt()} m away"
        } else {
            String.format(java.util.Locale.getDefault(), "%.1f km away", nearestDistance / 1_000.0)
        }
        notify(
            title = "Safety event nearby",
            message = "${event.title} (${event.category}) reported $distanceText. Stay alert.",
        )
    }

    /** Alerts once per event per install so the user is not spammed every 15 minutes. */
    private fun shouldAlert(eventId: Long): Boolean {
        val prefs = container().securePreferences
        val key = "risk_area_alert_$eventId"
        if (prefs.isRiskAlertDelivered(key)) return false
        prefs.markRiskAlertDelivered(key)
        return true
    }

    private fun container() = (applicationContext as GuardianApplication).container

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }

    private fun notify(title: String, message: String) {
        val context = applicationContext
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, MainActivity.CHANNEL_SAFETY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission missing for the risk-area alert", error)
        }
    }

    companion object {
        private const val TAG = "RiskAreaGeofenceWorker"
        const val UNIQUE_WORK_NAME = "guardian_risk_area_check"
        const val ALERT_RADIUS_METERS = 1_500
        private const val NOTIFICATION_ID = 2026
    }
}
