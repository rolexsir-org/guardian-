package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class GuardianApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initFirebase()
        setupWorkers()
    }

    private fun initFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val app = FirebaseApp.initializeApp(this)
                if (app == null) {
                    val options = FirebaseOptions.Builder()
                        .setApplicationId("1:1234567890:android:a1b2c3d4e5f6")
                        .setApiKey("AIzaSyDummyKeyForSafetyAppLocalFallback")
                        .setDatabaseUrl("https://guardian-safety-app-default-rtdb.firebaseio.com")
                        .setProjectId("guardian-safety-app")
                        .build()
                    FirebaseApp.initializeApp(this, options)
                }
            }
            Log.d("GuardianApplication", "Firebase initialized successfully.")
        } catch (e: Exception) {
            Log.w("GuardianApplication", "Default FirebaseApp init failed, attempting fallback options", e)
            try {
                if (FirebaseApp.getApps(this).isEmpty()) {
                    val options = FirebaseOptions.Builder()
                        .setApplicationId("1:1234567890:android:a1b2c3d4e5f6")
                        .setApiKey("AIzaSyDummyKeyForSafetyAppLocalFallback")
                        .setDatabaseUrl("https://guardian-safety-app-default-rtdb.firebaseio.com")
                        .setProjectId("guardian-safety-app")
                        .build()
                    FirebaseApp.initializeApp(this, options)
                }
            } catch (ex: Exception) {
                Log.e("GuardianApplication", "Fallback FirebaseApp init failed", ex)
            }
        }
    }

    private fun setupWorkers() {
        try {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            val riskWorkerRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.worker.RiskAreaGeofenceWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "RiskAreaGeofenceWork",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                riskWorkerRequest
            )
            Log.d("GuardianApplication", "RiskAreaGeofenceWorker enqueued successfully.")
        } catch (e: Exception) {
            Log.e("GuardianApplication", "Worker setup failed", e)
        }
    }
}

