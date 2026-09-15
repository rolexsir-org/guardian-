package com.guardian.safety.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.guardian.safety.GuardianApplication

/**
 * Daily local housekeeping.
 *
 * Runs the documented retention policy on the encrypted on-device store: location
 * fixes the server already accepted are dropped after the retention window, and
 * fixes that never reached the server are kept longer so an offline stretch can
 * still be replayed. Nothing user-visible is deleted here — emergency records,
 * contacts and medical details are the user's to remove.
 *
 * The worker reports how many rows it actually removed rather than claiming
 * success unconditionally.
 */
class CleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? GuardianApplication)?.container
            ?: return Result.failure(workDataOf(KEY_REASON to "Guardian is not initialised."))

        return try {
            val purged = container.repository.purgeOldLocations()
            Log.i(TAG, "Retention sweep removed $purged expired location fix(es).")
            Result.success(workDataOf(KEY_PURGED_LOCATIONS to purged))
        } catch (error: Exception) {
            Log.e(TAG, "Retention sweep failed", error)
            // Storage pressure is transient: try again on the next window.
            Result.retry()
        }
    }

    companion object {
        const val TAG = "CleanupWorker"
        const val UNIQUE_WORK_NAME = "guardian_cleanup"
        const val KEY_REASON = "reason"
        const val KEY_PURGED_LOCATIONS = "purged_locations"
    }
}
