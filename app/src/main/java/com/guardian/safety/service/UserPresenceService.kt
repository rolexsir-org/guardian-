package com.guardian.safety.service

import android.location.Location
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
 * Presence is an authenticated `POST /v1/me/presence`; the Durable Object expires
 * stale entries (120 s) server-side, so the heartbeat interval here is comfortably
 * shorter. Offline attempts are skipped rather than queued: a presence ping is not
 * worth replaying later, and a stale "online" would be worse than no ping at all.
 *
 * [markActive] lets a fresh location fix nudge the heartbeat so the circle sees the
 * device alive as soon as it produced one, without a ping per fix.
 */
class UserPresenceService(
    private val repository: GuardianRepository,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    @Volatile
    private var batteryLevelProvider: () -> Int? = { null }

    @Volatile
    private var lastPublishedAt: Long = 0L

    @Volatile
    private var lastKnownFixAt: Long = 0L

    /** Starts the periodic heartbeat. Safe to call repeatedly. */
    fun start(batteryProvider: () -> Int? = { null }) {
        batteryLevelProvider = batteryProvider
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                publish("ONLINE", batteryLevelProvider())
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /** Alias used by callers that do not have a battery source to hand. */
    fun startPresenceMonitoring(batteryLevelProvider: () -> Int? = { null }) = start(batteryLevelProvider)

    /** Stops the heartbeat and sends a best-effort OFFLINE ping. */
    fun stop() {
        val current = job
        job = null
        current?.cancel()
        // Best effort: the server also expires presence on its own.
        scope.launch { publish("OFFLINE", batteryLevelProvider()) }
    }

    /** Alias matching the older call sites. */
    fun stopPresenceMonitoring() = stop()

    /**
     * Records that the device just produced a real fix and nudges the heartbeat.
     *
     * Never fabricates a location: [location] is whatever the platform provider
     * returned, and nothing is sent when there is nothing fresh to say.
     */
    fun markActive(location: Location?) {
        if (location == null) return
        lastKnownFixAt = System.currentTimeMillis()
        if (job?.isActive != true) return
        if (lastKnownFixAt - lastPublishedAt < MIN_PUBLISH_INTERVAL_MS) return
        scope.launch { publish("ONLINE", batteryLevelProvider()) }
    }

    /** True when the most recent fix is still fresh enough to act on. */
    fun hasFreshFix(maxAgeMs: Long = FRESH_FIX_MS): Boolean =
        lastKnownFixAt > 0L && System.currentTimeMillis() - lastKnownFixAt <= maxAgeMs

    suspend fun publish(status: String, batteryLevel: Int?) {
        when (val result = repository.sendPresence(status, batteryLevel)) {
            is ApiResult.Success -> lastPublishedAt = System.currentTimeMillis()
            is ApiResult.Failure -> Log.i(TAG, "Presence update skipped: ${result.error.code}")
        }
    }

    private companion object {
        const val TAG = "UserPresenceService"

        /** Comfortably below the backend's 120 s stale-presence window. */
        const val HEARTBEAT_INTERVAL_MS = 45_000L

        /** Minimum spacing for fix-triggered pings, so a fix stream cannot spam. */
        const val MIN_PUBLISH_INTERVAL_MS = 10_000L

        const val FRESH_FIX_MS = 5 * 60 * 1_000L
    }
}
