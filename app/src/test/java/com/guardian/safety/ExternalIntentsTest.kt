package com.guardian.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.guardian.safety.util.ExternalIntents
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Handing an action to another app must never crash Guardian.
 *
 * These paths used to call `context.startActivity(...)` bare from a Compose
 * `onClick`. On a device with no dialler or no maps app that throws
 * `ActivityNotFoundException` and takes down a safety screen. The rule pinned
 * here: every hand-off returns a result, and an unavailable handler produces a
 * message for the user rather than an exception.
 *
 * A Robolectric environment has no dialler, maps app or browser installed, so
 * these tests exercise exactly the "nothing can handle this" branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalIntentsTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun dialingWithNoDiallerReportsInsteadOfThrowing() {
        // Must not throw: the call below returning at all is half the assertion.
        val result = ExternalIntents.dial(context(), "+15550100")
        assertTrue(
            result is ExternalIntents.LaunchResult.Opened ||
                result is ExternalIntents.LaunchResult.Unavailable,
        )
        if (result is ExternalIntents.LaunchResult.Unavailable) {
            assertTrue("An unavailable handler must explain itself.", result.message.isNotBlank())
        }
    }

    @Test
    fun aBlankNumberIsRefusedWithoutLaunchingAnything() {
        val result = ExternalIntents.dial(context(), "   ")
        assertTrue(result is ExternalIntents.LaunchResult.Unavailable)
        assertTrue(
            (result as ExternalIntents.LaunchResult.Unavailable).message.contains("No phone number"),
        )
    }

    @Test
    fun showingAMapNeverThrowsAndFallsBackToABrowser() {
        val result = ExternalIntents.showOnMap(
            context = context(),
            latitude = 51.5007,
            longitude = -0.1246,
            label = "Emergency location",
        )
        assertTrue(
            result is ExternalIntents.LaunchResult.Opened ||
                result is ExternalIntents.LaunchResult.Unavailable,
        )
        if (result is ExternalIntents.LaunchResult.Unavailable) {
            assertTrue(result.message.isNotBlank())
        }
    }

    @Test
    fun anUnresolvableIntentIsReportedRatherThanCrashing() {
        val impossible = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("guardian-nonexistent-scheme://nothing-handles-this"),
        )
        val result = ExternalIntents.launch(context(), impossible, "Nothing can open this.")
        assertTrue(result is ExternalIntents.LaunchResult.Unavailable)
        assertTrue((result as ExternalIntents.LaunchResult.Unavailable).message.isNotBlank())
    }
}
