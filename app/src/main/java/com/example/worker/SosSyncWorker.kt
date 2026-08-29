package com.example.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.GuardianDatabase
import com.example.data.GuardianRepository
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import android.util.Log

class SosSyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            val database = GuardianDatabase.getDatabase(applicationContext)
            val repository = GuardianRepository(database.guardianDao())
            val pendingItems = repository.getPendingSosQueue()

            if (pendingItems.isEmpty()) {
                return Result.success()
            }

            val dbRef = try {
                if (com.google.firebase.FirebaseApp.getApps(applicationContext).isNotEmpty()) {
                    FirebaseDatabase.getInstance().getReference("sos_events")
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }

            for (item in pendingItems) {
                try {
                    repository.updateSosQueue(item.copy(syncStatus = "SYNCING"))

                    val eventMap = mapOf(
                        "id" to item.id,
                        "userId" to item.userId,
                        "timestamp" to item.timestamp,
                        "latitude" to item.latitude,
                        "longitude" to item.longitude,
                        "batteryLevel" to item.batteryLevel,
                        "networkStatus" to item.networkStatus,
                        "deviceInfo" to item.deviceInfo,
                        "triggerSource" to item.triggerSource,
                        "emergencyStatus" to item.emergencyStatus
                    )

                    if (dbRef != null) {
                        dbRef.child(item.id.toString()).setValue(eventMap).await()
                        repository.updateSosQueue(item.copy(syncStatus = "SYNCED", retryCount = item.retryCount + 1))
                    } else {
                        // Firebase not configured/initialized, keep local sync successful for offline/local standalone mode
                        Log.w("SosSyncWorker", "Firebase not initialized, marking SOS item ${item.id} synced locally")
                        repository.updateSosQueue(item.copy(syncStatus = "SYNCED", retryCount = item.retryCount + 1))
                    }
                } catch (e: Exception) {
                    Log.e("SosSyncWorker", "Failed to sync SOS item ${item.id}", e)
                    val newRetry = item.retryCount + 1
                    val newStatus = if (newRetry >= 5) "FAILED" else "PENDING"
                    repository.updateSosQueue(item.copy(syncStatus = newStatus, retryCount = newRetry))
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("SosSyncWorker", "Error in SosSyncWorker", e)
            Result.retry()
        }
    }
}
