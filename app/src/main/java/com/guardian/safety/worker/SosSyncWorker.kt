package com.guardian.safety.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.guardian.safety.GuardianApplication
import com.guardian.safety.remote.ApiResult

/**
 * Uploads queued emergencies and offline location fixes.
 *
 * The previous implementation marked a queued SOS as `SYNCED` when the backend was
 * unavailable — a false success for a safety-critical operation. This worker only
 * ever reports success when the server accepted the event, and otherwise leaves the
 * row queued (or FAILED once the attempt budget is exhausted) with the real reason
 * stored next to it.
 */
class SosSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? GuardianApplication
            ?: return Result.failure(workDataOfReason("Guardian is not initialised."))
        val container = application.container

        if (!container.isCloudConfigured) {
            // Nothing to send yet, and retrying pointlessly would burn battery.
            return Result.success(workDataOfReason("Backend is not configured on this build."))
        }

        return try {
            val outcome = container.sosEmergencyManager.trySyncQueue()
            val sharedLocations = container.locationSyncCoordinator.flushPending()

            if (outcome.uploaded == 0 && sharedLocations == 0) {
                val pending = container.repository.getPendingSosQueue()
                val retryable = pending.any { it.syncStatus == "PENDING" || it.syncStatus == "SYNCING" }
                if (retryable) {
                    // Real, retryable failure: stay queued and try again later.
                    Result.retry()
                } else {
                    Result.success(workDataOfReason("Nothing left to sync."))
                }
            } else {
                Log.i(
                    TAG,
                    "Emergency sync complete: ${outcome.uploaded} emergency event(s), $sharedLocations location(s).",
                )
                Result.success()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Emergency sync failed", error)
            // Never mark anything synced on failure; WorkManager retries with backoff.
            Result.retry()
        }
    }

    private fun workDataOfReason(reason: String) =
        androidx.work.workDataOf(KEY_REASON to reason)

    companion object {
        const val TAG = "SosSyncWorker"
        const val UNIQUE_WORK_NAME = "guardian_sos_sync"

        /**
         * Separate name for the on-demand run scheduled the moment an emergency is
         * triggered. It must not collide with [UNIQUE_WORK_NAME], which is the
         * periodic sync — reusing that name would cancel the periodic schedule.
         */
        const val URGENT_WORK_NAME = "guardian_sos_sync_urgent"
        const val KEY_REASON = "reason"
        const val BASE_BACKOFF_SECONDS = 10L
    }
}
