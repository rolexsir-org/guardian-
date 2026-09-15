package com.guardian.safety.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Safe launching of other apps (dialler, maps, messaging, share sheet).
 *
 * Guardian cannot assume any of these exist. A phone with no dialler, a tablet
 * with no maps app, or an Android 11+ device that simply cannot *see* the target
 * package all make [Context.startActivity] throw [ActivityNotFoundException] —
 * which, from a Compose `onClick`, crashes the app. On a safety screen that is
 * the worst possible outcome, so every hand-off goes through here and returns a
 * result the caller must surface.
 *
 * Note on package visibility: Android 11 (API 30) hides most installed packages
 * from `resolveActivity`, so a null result there does **not** prove the action is
 * impossible. The manifest declares the `<queries>` this app needs; on top of
 * that we simply attempt the launch and handle the failure, which is correct
 * whether or not the package is visible.
 */
object ExternalIntents {

    /** Outcome of handing an action to another app. */
    sealed interface LaunchResult {
        /** Another app accepted the intent. */
        data object Opened : LaunchResult

        /** Nothing on this device can handle it; [message] explains that. */
        data class Unavailable(val message: String) : LaunchResult
    }

    /**
     * Starts [intent], adding [Intent.FLAG_ACTIVITY_NEW_TASK] so it works from a
     * non-activity context. Never throws.
     *
     * @param unavailableMessage shown to the user when no app can handle it.
     */
    fun launch(context: Context, intent: Intent, unavailableMessage: String): LaunchResult = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        LaunchResult.Opened
    } catch (_: ActivityNotFoundException) {
        LaunchResult.Unavailable(unavailableMessage)
    } catch (error: SecurityException) {
        LaunchResult.Unavailable("$unavailableMessage (blocked by the system: ${error.javaClass.simpleName})")
    } catch (error: Exception) {
        LaunchResult.Unavailable("$unavailableMessage (${error.javaClass.simpleName})")
    }

    /**
     * Opens the dialler pre-filled with [phoneNumber]. This never places the call
     * itself — use `EmergencyCallManager` when the call must actually be placed.
     */
    fun dial(context: Context, phoneNumber: String): LaunchResult {
        val normalized = phoneNumber.trim()
        if (normalized.isEmpty()) {
            return LaunchResult.Unavailable("No phone number is saved for this contact.")
        }
        return launch(
            context,
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", normalized, null)),
            "No dialler app is available on this device.",
        )
    }

    /**
     * Opens a map at [latitude]/[longitude]. Coordinates are always real values
     * supplied by the caller; this never substitutes a default location.
     */
    fun showOnMap(context: Context, latitude: Double, longitude: Double, label: String): LaunchResult {
        val encodedLabel = Uri.encode(label)
        val geo = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($encodedLabel)")
        return when (val direct = launch(context, Intent(Intent.ACTION_VIEW, geo), MAP_UNAVAILABLE)) {
            is LaunchResult.Opened -> direct
            is LaunchResult.Unavailable -> launch(
                // Fall back to a browser map when no geo: handler exists.
                context,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=17/$latitude/$longitude")),
                MAP_UNAVAILABLE,
            )
        }
    }

    private const val MAP_UNAVAILABLE = "No maps or browser app is available to show this location."
}
