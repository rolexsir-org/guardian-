package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import kotlin.math.*

class RiskAreaGeofenceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "RiskAreaGeofenceWorker"
    private val CHANNEL_ID = "risk_area_geofence_channel"

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Running RiskAreaGeofenceWorker to check for nearby hazards...")

            if (com.google.firebase.FirebaseApp.getApps(applicationContext).isEmpty()) {
                try {
                    com.google.firebase.FirebaseApp.initializeApp(applicationContext)
                } catch (e: Exception) {
                    Log.w(TAG, "FirebaseApp initialization attempt in worker failed: ${e.message}")
                }
            }

            if (com.google.firebase.FirebaseApp.getApps(applicationContext).isEmpty()) {
                Log.w(TAG, "FirebaseApp is not initialized. Skipping geofence check safely.")
                return Result.success()
            }

            // 1. Fetch live incidents from Firebase Realtime Database
            val database = FirebaseDatabase.getInstance()
            val snapshot = database.getReference("safety_incidents").get().await()

            if (!snapshot.exists()) {
                return Result.success()
            }

            // User's current location (defaulting to San Francisco center or last known)
            val userLat = 37.7749
            val userLng = -122.4194

            var nearestHazardTitle: String? = null
            var nearestDistanceMeters = Double.MAX_VALUE
            var hazardSeverity = "Warning"

            for (child in snapshot.children) {
                val title = child.child("title").getValue(String::class.java) ?: "Safety Hazard"
                val category = child.child("category").getValue(String::class.java) ?: "Hazard"
                val severity = child.child("severity").getValue(String::class.java) ?: "Warning"
                val lat = child.child("latitude").getValue(Double::class.java) ?: 37.7749
                val lng = child.child("longitude").getValue(Double::class.java) ?: -122.4194

                val distance = calculateDistanceMeters(userLat, userLng, lat, lng)
                if (distance < nearestDistanceMeters) {
                    nearestDistanceMeters = distance
                    nearestHazardTitle = "$title ($category)"
                    hazardSeverity = severity
                }
            }

            // If user enters high-risk area (< 1500 meters / 1.5km from a hazard)
            if (nearestHazardTitle != null && nearestDistanceMeters <= 1500.0) {
                val distFormatted = if (nearestDistanceMeters < 1000) {
                    "${nearestDistanceMeters.toInt()}m away"
                } else {
                    String.format(java.util.Locale.getDefault(), "%.1fkm away", nearestDistanceMeters / 1000.0)
                }

                triggerRiskNotification(
                    title = "⚠️ High-Risk Area Alert!",
                    message = "You are approaching $nearestHazardTitle ($distFormatted). Exercise caution."
                )
                Log.w(TAG, "User entered high-risk area: $nearestHazardTitle at $distFormatted")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking risk area geofence", e)
            Result.retry()
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusKm * c * 1000.0
    }

    private fun triggerRiskNotification(title: String, message: String) {
        val context = applicationContext
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "High-Risk Area Geofence Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when entering high-risk hazard zones"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(2026, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing notification permission for risk alert", e)
        }
    }
}
