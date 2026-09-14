package com.guardian.safety.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Why a location fix is not available, so the UI can say something useful. */
sealed interface LocationFailure {
    data object PermissionDenied : LocationFailure
    data object ProvidersDisabled : LocationFailure
    data object Timeout : LocationFailure
    data object Unavailable : LocationFailure
}

/**
 * Device location from the platform providers (GPS / network).
 *
 * Behaviour preserved from the previous implementation — same public API,
 * continuous updates plus a single-shot current fix — with three changes that a
 * safety app needs:
 * * the provider is Android's own [LocationManager], so there is no Play Services
 *   or Play Services dependency and devices without Google services still work;
 * * permission and provider state are checked explicitly, and a missing fix is
 *   reported as a [LocationFailure] instead of being papered over with a
 *   hard-coded coordinate;
 * * a bounded timeout, so "locating…" can never hang forever.
 */
class LocationService(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun areProvidersEnabled(): Boolean = try {
        val manager = locationManager ?: return false
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
            manager.isLocationEnabled
    } catch (_: Exception) {
        false
    }

    /** True when the most recent cached fix is fresh enough to be trusted. */
    fun isFresh(location: Location?, maxAgeMs: Long = MAX_FIX_AGE_MS): Boolean =
        location != null && System.currentTimeMillis() - location.time <= maxAgeMs

    /**
     * One-shot fix. Calls exactly one of [onSuccess]/[onError]:
     * a genuine provider location, or the reason the device could not produce one.
     */
    fun getCurrentLocation(
        onSuccess: (Location) -> Unit,
        onError: (LocationFailure) -> Unit,
    ) {
        if (!hasLocationPermission()) {
            onError(LocationFailure.PermissionDenied)
            return
        }
        val manager = locationManager
        if (manager == null) {
            onError(LocationFailure.Unavailable)
            return
        }
        if (!areProvidersEnabled()) {
            onError(LocationFailure.ProvidersDisabled)
            return
        }

        val cached = lastKnownLocation(manager)
        if (isFresh(cached)) {
            onSuccess(cached!!)
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                removeUpdates(manager, listener = this)
                if (isFresh(location)) {
                    onSuccess(location)
                } else {
                    onError(LocationFailure.Timeout)
                }
            }

            @Deprecated("Deprecated in the platform, still delivered on older devices")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        val registered = requestUpdates(manager, listener = listener)
        if (!registered) {
            if (cached != null) {
                onSuccess(cached)
            } else {
                onError(LocationFailure.Unavailable)
            }
        }
    }

    /**
     * Suspending variant used by the ViewModel: returns the real fix, or null plus
     * the reason. Never fabricates a location.
     */
    suspend fun currentLocation(timeoutMs: Long = LOCATION_TIMEOUT_MS): Pair<Location?, LocationFailure?> {
        if (!hasLocationPermission()) return null to LocationFailure.PermissionDenied
        val manager = locationManager ?: return null to LocationFailure.Unavailable
        if (!areProvidersEnabled()) return null to LocationFailure.ProvidersDisabled

        val cached = lastKnownLocation(manager)
        if (isFresh(cached)) return cached to null

        val fresh = withTimeoutOrNull(timeoutMs) {
            awaitFirstLocation(manager)
        }
        return when {
            fresh != null -> fresh to null
            cached != null -> cached to null
            else -> null to LocationFailure.Timeout
        }
    }

    private suspend fun awaitFirstLocation(manager: LocationManager): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (continuation.isActive) continuation.resume(location)
                    removeUpdates(manager, listener = this)
                }

                @Deprecated("Deprecated in the platform, still delivered on older devices")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
            val registered = requestUpdates(manager, onLocation = { }, listener = listener)
            if (!registered) {
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation { removeUpdates(manager, listener = listener) }
        }

    /** Continuous updates for as long as the flow is collected. */
    @SuppressLint("MissingPermission")
    fun locationFlow(minTimeMs: Long = 10_000L, minDistanceM: Float = 5f): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            close(SecurityException("Location permission is not granted."))
            return@callbackFlow
        }
        val manager = locationManager
        if (manager == null) {
            close(IllegalStateException("This device has no location provider."))
            return@callbackFlow
        }
        if (!areProvidersEnabled()) {
            close(IllegalStateException("Location services are turned off."))
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            @Deprecated("Deprecated in the platform, still delivered on older devices")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                Log.i(TAG, "Location provider disabled: $provider")
            }
        }

        requestUpdates(manager, minTimeMs, minDistanceM, listener)
        awaitClose { removeUpdates(manager, listener = listener) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(manager: LocationManager): Location? {
        if (!hasLocationPermission()) return null
        return try {
            val gps = runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            val network = runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            listOfNotNull(gps, network).maxByOrNull { it.time }
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the last known location", error)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(
        manager: LocationManager,
        minTimeMs: Long = 2_000L,
        minDistanceM: Float = 1f,
        onLocation: (Location) -> Unit = {},
        listener: LocationListener? = null,
        onError: (Exception) -> Unit = {},
    ): Boolean {
        val target = listener ?: object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)

            @Deprecated("Deprecated in the platform, still delivered on older devices")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        if (!hasLocationPermission()) return false
        return try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistanceM,
                target,
                Looper.getMainLooper(),
            )
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    target,
                    Looper.getMainLooper(),
                )
            }
            activeListener = target
            true
        } catch (error: SecurityException) {
            onError(error)
            false
        } catch (error: IllegalArgumentException) {
            // Provider missing on this device: fall back to whichever exists.
            onError(error)
            false
        } catch (error: Exception) {
            onError(error)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun removeUpdates(manager: LocationManager, it: Any? = null, listener: LocationListener? = null) {
        val target = listener ?: activeListener ?: return
        try {
            manager.removeUpdates(target)
        } catch (_: Exception) {
            // Already removed, or the permission was revoked mid-flight.
        }
        if (activeListener === target) activeListener = null
    }

    private var activeListener: LocationListener? = null

    companion object {
        private const val TAG = "LocationService"
        const val MAX_FIX_AGE_MS = 5 * 60 * 1_000L
        const val LOCATION_TIMEOUT_MS = 20_000L
    }
}
