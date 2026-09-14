package com.guardian.safety.util

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Why biometric confirmation could not be completed. */
sealed interface BiometricOutcome {
    data object Authenticated : BiometricOutcome

    /** The device has no usable biometric/credential lock: never treated as success. */
    data class NotEnrolled(val message: String) : BiometricOutcome

    data class Rejected(val message: String) : BiometricOutcome
}

/**
 * Biometric/credential confirmation for protected screens.
 *
 * The previous implementation called `onSuccess()` whenever biometrics were
 * unavailable, whenever an unexpected error occurred, and on every error that was
 * not an explicit user cancel — three ways to bypass the protection this screen
 * exists for. It now fails closed: an unenrolled device shows a clear explanation
 * instead of silently opening protected data.
 */
object BiometricAuthManager {

    private const val TAG = "BiometricAuthManager"

    val allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(context: Context): BiometricOutcome {
        val manager = BiometricManager.from(context)
        return when (val status = manager.canAuthenticate(allowedAuthenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricOutcome.Authenticated
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricOutcome.NotEnrolled(
                "Set up a screen lock or fingerprint on this device to open protected areas of Guardian.",
            )
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            -> BiometricOutcome.NotEnrolled(
                "Biometric or device-credential confirmation is not available on this device (status $status).",
            )
            else -> BiometricOutcome.NotEnrolled("Device authentication is unavailable (status $status).")
        }
    }

    /**
     * Prompts for biometric or device-credential confirmation.
     *
     * [onResult] always receives an outcome — success is reported only after the
     * platform actually confirmed the user.
     */
    fun authenticate(
        context: Context,
        title: String = "Biometric security",
        subtitle: String = "Confirm it is you to open protected settings",
        onResult: (BiometricOutcome) -> Unit,
    ) {
        val activity = context as? FragmentActivity
        if (activity == null) {
            // Without a host activity there is no way to ask the user, so the
            // protected screen stays closed rather than opening silently.
            onResult(
                BiometricOutcome.Rejected(
                    "Guardian could not show the confirmation prompt in this context. Reopen the screen to try again.",
                ),
            )
            return
        }

        when (val availability = canAuthenticate(activity)) {
            is BiometricOutcome.NotEnrolled -> {
                onResult(availability)
                return
            }
            else -> Unit
        }

        try {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onResult(BiometricOutcome.Authenticated)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val message = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> "Confirmation was cancelled."

                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                            -> "Too many attempts. Unlock your device with its PIN, pattern or password and try again."

                            BiometricPrompt.ERROR_NO_BIOMETRICS -> "No biometric is enrolled on this device."

                            else -> errString.toString()
                        }
                        onResult(BiometricOutcome.Rejected(message))
                    }

                    override fun onAuthenticationFailed() {
                        // A single mismatch: the system shows its own retry UI.
                        super.onAuthenticationFailed()
                    }
                },
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(allowedAuthenticators)
                .build()

            prompt.authenticate(promptInfo)
        } catch (error: Exception) {
            Log.e(TAG, "Biometric prompt failed", error)
            onResult(
                BiometricOutcome.Rejected(
                    "Device confirmation could not be started (${error.javaClass.simpleName}).",
                ),
            )
        }
    }
}
