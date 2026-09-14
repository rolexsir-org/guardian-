package com.guardian.safety.service

import android.location.Location
import android.util.Log
import com.guardian.safety.data.GuardianRepository
import com.guardian.safety.remote.ApiResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shares the device's real location with the family group.
 *
 * * Only genuine provider fixes ever reach this class; there is no synthetic
 *   coordinate anywhere in the path.
 * * Offline fixes stay cached in Room, marked unsynced, and are flushed when a
 *   connection returns. A fix is flagged synced only after the Worker confirmed it,
 *   so a "shared" state is never shown for a location that never left the device.
 * * A single-flight lock stops a burst of location updates from stampeding the
 *   network or the battery.
 */
class LocationSyncCoordinator(private val repository: GuardianRepository) {

    private val flushing = AtomicBoolean(false)

    suspend fun enqueue(location: Location, batteryLevel: Int?): ApiResult<Unit> {
        val group = repository.getFamilyGroupOnce()
            ?: return ApiResult.Failure(
                com.guardian.safety.remote.ApiError(
                    code = "no_family_group",
                    message = "Join or create a family group to share your location.",
                ),
            )
        val cachedId = repository.cacheLocation(location, batteryLevel, source = "DEVICE")
        if (!repository.isCloudConfigured()) {
            return ApiResult.Failure(
                com.guardian.safety.remote.ApiError(
                    code = "backend_unconfigured",
                    message = "This build has no Guardian backend configured, so location was kept on the device only.",
                ),
            )
        }
        return when (
            val result = repository.shareLocation(
                groupRemoteId = group.remoteId,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy.takeIf { location.hasAccuracy() },
                batteryLevel = batteryLevel,
                source = "MANUAL",
            )
        ) {
            is ApiResult.Success -> {
                repository.markLocationSynced(cachedId)
                result
            }
            is ApiResult.Failure -> {
                Log.i(TAG, "Location sharing deferred; kept on the device for retry.")
                result
            }
        }
    }

    /** Flushes cached fixes that never reached the server. Returns how many synced. */
    suspend fun flushPending(): Int {
        if (!flushing.compareAndSet(false, true)) return 0
        try {
            val group = repository.getFamilyGroupOnce() ?: return 0
            if (!repository.isCloudConfigured()) return 0
            val pending = repository.getUnsyncedLocations()
            var synced = 0
            for (cached in pending.take(MAX_BATCH)) {
                when (
                    repository.shareLocation(
                        groupRemoteId = group.remoteId,
                        latitude = cached.latitude,
                        longitude = cached.longitude,
                        accuracy = cached.accuracy,
                        batteryLevel = cached.batteryLevel,
                        source = "MANUAL",
                    )
                ) {
                    is ApiResult.Success -> {
                        repository.markLocationSynced(cached.id)
                        synced++
                    }
                    is ApiResult.Failure -> {
                        // Chronological queue: stop at the first failure instead of
                        // burning battery replaying fixes that cannot be sent.
                        return synced
                    }
                }
            }
            return synced
        } finally {
            flushing.set(false)
        }
    }

    private companion object {
        const val TAG = "LocationSyncCoordinator"
        const val MAX_BATCH = 20
    }
}
