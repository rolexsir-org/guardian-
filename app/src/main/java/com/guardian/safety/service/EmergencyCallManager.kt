package com.guardian.safety.service

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Places emergency calls.
 *
 * Android will not let the app place a call without `CALL_PHONE`, and some devices
 * have no telephony at all. Instead of pretending the call happened, this returns
 * a structured result so the UI can say exactly what happened: the call was
 * placed, the dialer was opened for the user, or the device cannot call.
 */
object EmergencyCallManager {

    private const val TAG = "EmergencyCallManager"

    /** Emergency services number for the device's current locale. */
    fun emergencyNumber(context: Context): String = "112"

    /**
     * Calls [phoneNumber]. Falls back to the dialer when the app is not allowed to
     * place calls directly, and reports that as [EmergencyActionResult.UserActionRequired]
     * rather than a success.
     */
    fun callNumber(context: Context, phoneNumber: String): EmergencyActionResult {
        val normalized = phoneNumber.trim()
        if (normalized.isBlank()) {
            return EmergencyActionResult.Failed("No phone number is available for this emergency contact.")
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)
            && !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        ) {
            return EmergencyActionResult.Failed("This device cannot place phone calls.")
        }

        val canCallDirectly = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val uri = Uri.fromParts("tel", normalized, null)

        if (canCallDirectly) {
            val direct = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(direct)
                EmergencyActionResult.Dispatched("Calling $normalized now.")
            } catch (error: SecurityException) {
                Log.w(TAG, "CALL_PHONE permission rejected by the platform", error)
                openDialer(context, uri, normalized, "Call permission was denied by the system")
            } catch (error: ActivityNotFoundException) {
                Log.w(TAG, "No activity can place calls", error)
                openDialer(context, uri, normalized, "No calling app is available")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to place call", error)
                EmergencyActionResult.Failed(
                    "The call could not be started.",
                    error.javaClass.simpleName,
                )
            }
        }

        return openDialer(context, uri, normalized, "Calling permission is not granted")
    }

    private fun openDialer(
        context: Context,
        uri: Uri,
        phoneNumber: String,
        reason: String,
    ): EmergencyActionResult {
        val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(dial)
            EmergencyActionResult.UserActionRequired(
                "$reason — the dialer is open with $phoneNumber. Press call to connect.",
            )
        } catch (error: ActivityNotFoundException) {
            EmergencyActionResult.Failed("No dialer app is available on this device.", reason)
        } catch (error: Exception) {
            EmergencyActionResult.Failed("The dialer could not be opened.", error.javaClass.simpleName)
        }
    }
}
