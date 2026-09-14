package com.guardian.safety.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object HapticUtils {
    fun triggerHaptic(context: Context, isHeavy: Boolean = false) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = if (isHeavy) {
                        VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 200, 50, 300), -1)
                    } else {
                        VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    if (isHeavy) {
                        vibrator.vibrate(longArrayOf(0, 100, 50, 200, 50, 300), -1)
                    } else {
                        vibrator.vibrate(40)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore if vibration permissions or hardware not present
        }
    }
}
