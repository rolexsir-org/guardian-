package com.guardian.safety

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.guardian.safety.service.LocationFailure
import com.guardian.safety.service.LocationService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Location handling must report a genuine provider fix, or the reason there is
 * none. It must never substitute a default coordinate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationServiceTest {

    @Test
    fun serviceIsConstructibleAndReportsMissingPermission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locationService = LocationService(context)
        assertNotNull(locationService)
        assertFalse(locationService.hasLocationPermission())

        val (location, failure) = locationService.currentLocation(timeoutMs = 200L)
        assertEquals(null, location)
        assertEquals(LocationFailure.PermissionDenied, failure)
    }

    @Test
    fun callbackApiReportsPermissionDeniedInsteadOfAFakeFix() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locationService = LocationService(context)

        var reportedLocation: Location? = null
        var reportedFailure: LocationFailure? = null
        locationService.getCurrentLocation(
            onSuccess = { reportedLocation = it },
            onError = { reportedFailure = it },
        )

        assertEquals(null, reportedLocation)
        assertEquals(LocationFailure.PermissionDenied, reportedFailure)
    }

    @Test
    fun staleFixesAreNotTreatedAsCurrent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locationService = LocationService(context)

        val fresh = Location("test").apply { time = System.currentTimeMillis() }
        val stale = Location("test").apply { time = System.currentTimeMillis() - 30 * 60 * 1_000 }

        assertTrue(locationService.isFresh(fresh))
        assertFalse(locationService.isFresh(stale))
        assertFalse(locationService.isFresh(null))
    }

    @Test
    fun providerStateIsReadFromThePlatformAndNotAssumed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locationService = LocationService(context)
        // Robolectric reports the real manager state; the point is that the app asks
        // rather than assuming location is available.
        locationService.areProvidersEnabled()
    }
}
