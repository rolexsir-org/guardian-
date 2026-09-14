package com.guardian.safety.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.guardian.safety.util.HapticUtils
import kotlin.math.sqrt

class SensorSafetyMonitor(
    private val context: Context,
    private val onFallDetected: () -> Unit,
    private val onCrashDetected: () -> Unit,
    private val onShakeDetected: () -> Unit
) : SensorEventListener {
    private val TAG = "SensorSafetyMonitor"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var isMonitoring = false
    private var lastShakeTime = 0L
    private var shakeCount = 0

    fun startMonitoring(
        fallGuardEnabled: Boolean,
        crashSosEnabled: Boolean,
        shakeGestureEnabled: Boolean
    ) {
        if (isMonitoring || accelerometer == null) return
        this.fallGuardActive = fallGuardEnabled
        this.crashSosActive = crashSosEnabled
        this.shakeActive = shakeGestureEnabled

        sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        isMonitoring = true
        Log.d(TAG, "SensorSafetyMonitor started monitoring sensors")
    }

    private var fallGuardActive = true
    private var crashSosActive = true
    private var shakeActive = true

    fun updateFlags(fall: Boolean, crash: Boolean, shake: Boolean) {
        fallGuardActive = fall
        crashSosActive = crash
        shakeActive = shake
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        try {
            sensorManager?.unregisterListener(this)
            isMonitoring = false
            Log.d(TAG, "SensorSafetyMonitor stopped monitoring")
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering sensors", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH

            val accelerationSq = gX * gX + gY * gY + gZ * gZ
            val totalG = sqrt(accelerationSq)

            // 1. Crash SOS detection (> 4.5G sudden impact)
            if (crashSosActive && totalG > 4.5f) {
                Log.w(TAG, "High-G Crash impact detected: ${totalG}G")
                HapticUtils.triggerHaptic(context, isHeavy = true)
                onCrashDetected()
                return
            }

            // 2. Fall Guard detection (Free fall < 0.3G followed by impact > 3.0G)
            if (fallGuardActive && totalG > 3.2f) {
                Log.w(TAG, "Fall impact deceleration detected: ${totalG}G")
                HapticUtils.triggerHaptic(context, isHeavy = true)
                onFallDetected()
                return
            }

            // 3. Shake Gesture detection (> 2.5G spikes)
            if (shakeActive && totalG > 2.5f) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > 1500) {
                    shakeCount = 0
                }
                lastShakeTime = now
                shakeCount++
                if (shakeCount >= 4) {
                    shakeCount = 0
                    Log.w(TAG, "Vigorous shake gesture detected (4x)")
                    HapticUtils.triggerHaptic(context, isHeavy = true)
                    onShakeDetected()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
