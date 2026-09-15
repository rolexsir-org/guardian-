package com.guardian.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.guardian.safety.service.EmergencyActionResult
import com.guardian.safety.service.EmergencyCallManager
import com.guardian.safety.service.EmergencySmsManager
import com.guardian.safety.service.SmsStatus
import com.guardian.safety.service.describe
import com.guardian.safety.service.isDispatched
import com.guardian.safety.service.isFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The emergency call/SMS contract every screen relies on.
 *
 * Guardian's UI decides what to tell the user purely from these result types, so a
 * regression here would silently turn "you still have to press call" into "the call
 * was placed". The rules pinned below:
 *
 * * only [EmergencyActionResult.Dispatched] means Android really started the action;
 * * a dialler / composer hand-off is [EmergencyActionResult.UserActionRequired] and
 *   is **not** dispatched;
 * * every non-dispatched outcome carries a non-blank explanation, because the
 *   screens surface [describe] to the user;
 * * with no `SEND_SMS` permission the SMS manager reports `COMPOSE_OPENED` or a
 *   failure — never `SENT`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EmergencyActionResultTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun onlyADispatchedResultCountsAsACompletedAction() {
        val dispatched: EmergencyActionResult = EmergencyActionResult.Dispatched("Calling 911 now.")
        val handedToUser: EmergencyActionResult =
            EmergencyActionResult.UserActionRequired("The dialler is open with 911.")
        val failed: EmergencyActionResult = EmergencyActionResult.Failed("This device cannot place calls.")

        assertTrue(dispatched.isDispatched)
        assertFalse("A dialler hand-off must never count as a placed call.", handedToUser.isDispatched)
        assertFalse(failed.isDispatched)
        assertTrue(failed.isFailure)
        assertFalse(handedToUser.isFailure)
    }

    @Test
    fun everyNonDispatchedOutcomeExplainsItselfToTheUser() {
        val outcomes = listOf(
            EmergencyActionResult.UserActionRequired("Calling permission is not granted — press call."),
            EmergencyActionResult.Failed("No dialer app is available on this device."),
            EmergencyActionResult.Failed("The call could not be started.", "SecurityException"),
        )
        outcomes.forEach { outcome ->
            assertTrue(
                "A non-dispatched emergency outcome must carry a message the UI can show.",
                outcome.describe().isNotBlank(),
            )
        }
        assertEquals(
            "The call could not be started. (SecurityException)",
            EmergencyActionResult.Failed("The call could not be started.", "SecurityException").describe(),
        )
    }

    @Test
    fun aBlankNumberIsRefusedRatherThanDialled() {
        val outcome = EmergencyCallManager.callNumber(context(), "   ")
        assertTrue(
            "An empty phone number must fail rather than open the dialler.",
            outcome is EmergencyActionResult.Failed,
        )
        assertFalse(outcome.isDispatched)
    }

    @Test
    fun callPermissionIsReadFromThePlatformAndNotAssumed() {
        // No permission is granted in a Robolectric unit test, so the app must know
        // it cannot place a call by itself.
        assertFalse(EmergencyCallManager.canPlaceCalls(context()))
        assertFalse(EmergencySmsManager.canSendSms(context()))
    }

    @Test
    fun withoutSmsPermissionTheMessageIsNeverReportedAsSent() {
        val delivery = EmergencySmsManager.sendEmergencySms(
            context = context(),
            phoneNumber = "+15550100",
            userName = "Guardian test",
            latitude = null,
            longitude = null,
            accuracyM = null,
            batteryLevel = null,
            timestamp = System.currentTimeMillis(),
            locationAvailable = false,
        )
        assertTrue(
            "Without SEND_SMS the result must be a composer hand-off or a failure, never SENT/DELIVERED.",
            delivery.status == SmsStatus.COMPOSE_OPENED ||
                delivery.status == SmsStatus.FAILED ||
                delivery.status == SmsStatus.PERMISSION_REQUIRED,
        )
        assertTrue(delivery.detail.isNotBlank())
    }

    @Test
    fun anAlertWithNoFixSaysSoInsteadOfInventingCoordinates() {
        val body = EmergencySmsManager.buildEmergencyMessage(
            userName = "Guardian test",
            latitude = null,
            longitude = null,
            accuracyM = null,
            batteryLevel = 42,
            timestamp = System.currentTimeMillis(),
            locationAvailable = false,
        )
        assertTrue(body.contains("Location: unavailable"))
        assertFalse("A message with no fix must not contain a map link.", body.contains("maps.google.com"))
        assertFalse(body.contains("37.7749"))
    }

    @Test
    fun aRealFixIsRenderedAsTheActualCoordinates() {
        val body = EmergencySmsManager.buildEmergencyMessage(
            userName = "Guardian test",
            latitude = 51.5007,
            longitude = -0.1246,
            accuracyM = 8f,
            batteryLevel = null,
            timestamp = System.currentTimeMillis(),
            locationAvailable = true,
        )
        assertTrue(body.contains("51.5007"))
        assertTrue(body.contains("-0.1246"))
        assertTrue(body.contains("±8 m"))
    }
}
