package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.LocationService
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationServiceTest {

    @Test
    fun testLocationServiceInstantiation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val locationService = LocationService(context)
        assertNotNull(locationService)
    }
}
