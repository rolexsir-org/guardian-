package com.guardian.safety.service

/**
 * Outcome of an emergency action performed on the device.
 *
 * Android can refuse or partially complete an emergency action (missing
 * permission, no telephony, no SIM). The app must be able to tell the difference
 * between "the call was really placed", "the user still has to tap send" and
 * "this failed", so those states are modelled explicitly and never collapsed into
 * a success.
 */
sealed interface EmergencyActionResult {

    /** Android accepted the action and it was really started. */
    data class Dispatched(val detail: String) : EmergencyActionResult

    /**
     * The action needs the user: for example the dialer/SMS composer was opened
     * because the app is not allowed to send it directly. This is NOT a success
     * and must be shown as "action needed".
     */
    data class UserActionRequired(val detail: String) : EmergencyActionResult

    data class Failed(val message: String, val cause: String? = null) : EmergencyActionResult
}

/** Convenience helpers so call sites stay readable. */
val EmergencyActionResult.isDispatched: Boolean
    get() = this is EmergencyActionResult.Dispatched

val EmergencyActionResult.isFailure: Boolean
    get() = this is EmergencyActionResult.Failed

fun EmergencyActionResult.describe(): String = when (this) {
    is EmergencyActionResult.Dispatched -> detail
    is EmergencyActionResult.UserActionRequired -> detail
    is EmergencyActionResult.Failed -> cause?.let { "$message ($it)" } ?: message
}
