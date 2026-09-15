package com.guardian.safety.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.guardian.safety.util.HapticUtils
import kotlin.math.sqrt

/**
 * Accelerometer-based emergency triggers.
 *
 * This is a best-effort convenience trigger, **not** a medical-grade fall detector
 * and not a certified crash detection system. Every rule here is deliberately
 * conservative because a false emergency costs the user a real phone call and SMS.
 *
 * Hardening applied:
 * * Sensor values are validated — `NaN`/infinite readings and implausible spikes
 *   from a broken or simulated sensor are dropped instead of triggering anything.
 * * A fall requires two phases (a genuine free-fall dip, then an impact) so simply
 *   putting the phone down cannot fire it.
 * * Each trigger has its own cooldown, so one physical event produces one
 *   emergency rather than one per sensor sample.
 * * Availability is reported: [startMonitoring] returns false when the device has
 *   no accelerometer, and callers must surface that instead of implying protection.
 */
class SensorSafetyMonitor(
    private val context: Context,
    private val onFallDetected: () -> Unit,
    private val onCrashDetected: () -> Unit,
    private val onShakeDetected: () -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** True when this device has an accelerometer the monitor can register on. */
    val isAvailable: Boolean get() = accelerometer != null

    /** Reason the monitor cannot run, for the settings screen. Null when it can. */
    val unavailabilityReason: String?
        get() = when {
            sensorManager == null -> "This device exposes no sensor service."
            accelerometer == null -> "This device has no accelerometer, so motion triggers are unavailable."
            else -> null
        }

    @Volatile
    var isMonitoring: Boolean = false
        private set

    @Volatile
    private var fallGuardActive = false

    @Volatile
    private var crashSosActive = false

    @Volatile
    private var shakeActive = false

    private var lastShakeTime = 0L
    private var shakeCount = 0
    private var lastFallTriggerAt = 0L
    private var lastCrashTriggerAt = 0L
    private var lastShakeTriggerAt = 0L
    private var freeFallObservedAt = 0L
    private var droppedReadings = 0L

    /**
     * Registers the listener with the flags that are currently switched on.
     *
     * @return true when monitoring actually started. False means the device cannot
     *   provide the signal, and the caller must tell the user.
     */
    fun startMonitoring(
        fallGuardEnabled: Boolean = false,
        crashSosEnabled: Boolean = false,
        shakeGestureEnabled: Boolean = false,
    ): Boolean {
        fallGuardActive = fallGuardEnabled
        crashSosActive = crashSosEnabled
        shakeActive = shakeGestureEnabled

        if (!fallGuardActive && !crashSosActive && !shakeActive) {
            stopMonitoring()
            return false
        }
        val sensor = accelerometer ?: run {
            Log.w(TAG, unavailabilityReason ?: "Accelerometer unavailable")
            return false
        }
        if (isMonitoring) return true
        return try {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            isMonitoring = true
            Log.i(TAG, "Motion monitoring started (fall=$fallGuardActive crash=$crashSosActive shake=$shakeActive)")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not register the accelerometer", error)
            isMonitoring = false
            false
        }
    }

    /** Applies new toggle states without re-registering the listener. */
    fun updateFlags(
        fallGuardEnabled: Boolean,
        crashSosEnabled: Boolean,
        shakeGestureEnabled: Boolean,
    ) {
        fallGuardActive = fallGuardEnabled
        crashSosActive = crashSosEnabled
        shakeActive = shakeGestureEnabled
        if (!fallGuardActive && !crashSosActive && !shakeActive) stopMonitoring()
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        try {
            sensorManager?.unregisterListener(this)
            isMonitoring = false
            freeFallObservedAt = 0L
            shakeCount = 0
            Log.i(TAG, "Motion monitoring stopped")
        } catch (error: Exception) {
            Log.e(TAG, "Error unregistering sensors", error)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val values = event.values
        if (values == null || values.size < AXIS_COUNT) {
            countDropped("short sample")
            return
        }

        val x = values[0]
        val y = values[1]
        val z = values[2]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
            countDropped("non-finite sample")
            return
        }

        val totalG = sqrt(
            (x / SensorManager.GRAVITY_EARTH).let { it * it } +
                (y / SensorManager.GRAVITY_EARTH).let { it * it } +
                (z / SensorManager.GRAVITY_EARTH).let { it * it },
        )
        if (!totalG.isFinite() || totalG > IMPLAUSIBLE_G) {
            // A spike this large is a sensor fault, not an impact.
            countDropped("implausible magnitude ${totalG}g")
            return
        }

        val now = System.currentTimeMillis()

        // 1. Crash: a very high-G impact on its own.
        if (crashSosActive && totalG > CRASH_THRESHOLD_G && now - lastCrashTriggerAt > TRIGGER_COOLDOWN_MS) {
            lastCrashTriggerAt = now
            Log.w(TAG, "High-G impact detected: ${"%.2f".format(totalG)}g")
            HapticUtils.triggerHaptic(context, isHeavy = true)
            onCrashDetected()
            return
        }

        // 2. Fall: free fall first, then an impact inside the window.
        if (fallGuardActive) {
            if (totalG < FREE_FALL_THRESHOLD_G) {
                freeFallObservedAt = now
            }
            val withinFreeFallWindow = freeFallObservedAt > 0L && now - freeFallObservedAt <= FREE_FALL_WINDOW_MS
            if (withinFreeFallWindow && totalG > FALL_IMPACT_THRESHOLD_G &&
                now - lastFallTriggerAt > TRIGGER_COOLDOWN_MS
            ) {
                freeFallObservedAt = 0L
                lastFallTriggerAt = now
                Log.w(TAG, "Free fall followed by impact detected: ${"%.2f".format(totalG)}g")
                HapticUtils.triggerHaptic(context, isHeavy = true)
                onFallDetected()
                return
            }
        }

        // 3. Shake: repeated spikes in a short window.
        if (shakeActive && totalG > SHAKE_THRESHOLD_G) {
            if (now - lastShakeTime > SHAKE_RESET_MS) shakeCount = 0
            lastShakeTime = now
            shakeCount++
            if (shakeCount >= SHAKE_TRIGGER_COUNT && now - lastShakeTriggerAt > TRIGGER_COOLDOWN_MS) {
                shakeCount = 0
                lastShakeTriggerAt = now
                Log.w(TAG, "Repeated shake detected ($SHAKE_TRIGGER_COUNT spikes)")
                HapticUtils.triggerHaptic(context, isHeavy = true)
                onShakeDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun countDropped(reason: String) {
        droppedReadings++
        // Log sparingly: a faulty sensor can emit hundreds of samples a second.
        if (droppedReadings % LOG_EVERY_DROPPED == 1L) {
            Log.w(TAG, "Ignored sensor reading ($reason); $droppedReadings dropped so far")
        }
    }

    private companion object {
        const val TAG = "SensorSafetyMonitor"
        const val AXIS_COUNT = 3

        const val CRASH_THRESHOLD_G = 4.5f
        const val FALL_IMPACT_THRESHOLD_G = 3.2f
        const val FREE_FALL_THRESHOLD_G = 0.35f
        const val FREE_FALL_WINDOW_MS = 700L
        const val SHAKE_THRESHOLD_G = 2.5f
        const val SHAKE_TRIGGER_COUNT = 4
        const val SHAKE_RESET_MS = 1_500L

        /** One physical event must not produce a stream of emergencies. */
        const val TRIGGER_COOLDOWN_MS = 10_000L

        /** Anything above this is a broken sensor, not a 200 g impact. */
        const val IMPLAUSIBLE_G = 200f

        const val LOG_EVERY_DROPPED = 100L
    }
}
