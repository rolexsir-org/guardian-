package com.guardian.safety.service

import android.util.Log
import com.guardian.safety.data.GuardianRepository
import com.guardian.safety.remote.ApiResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Publishes this device's presence to the Guardian backend.
 *
 * Replaces the legacy realtime-database connection listener: presence is now an authenticated
 * `POST /v1/me/presence` and the server expires stale entries (120 s) itself. Offline
 * attempts are simply skipped, because a presence ping is not worth queueing while
 * the device has no connection.
 */
class UserPresenceService(
    private val repository: GuardianRepository,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    fun startPresenceMonitoring(batteryLevelProvider: () -> Int? = { null }) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                publish("ONLINE", batteryLevelProvider())
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    suspend fun publish(status: String, batteryLevel: Int?) {
        when (val result = repository.sendPresence(status, batteryLevel)) {
            is ApiResult.Success -> Unit
            is ApiResult.Failure -> Log.i(TAG, "Presence update skipped: ${result.error.code}")
        }
    }

    fun stopPresenceMonitoring() {
        val current = job
        job = null
        current?.cancel()
        // Best effort: the server also expires presence on its own.
        scope.launch { publish("OFFLINE", null) }
    }

    private companion object {
        const val TAG = "UserPresenceService"

        /** Comfortably below the backend's 120 s stale-presence window. */
        const val HEARTBEAT_INTERVAL_MS = 45_000L
    }
}
