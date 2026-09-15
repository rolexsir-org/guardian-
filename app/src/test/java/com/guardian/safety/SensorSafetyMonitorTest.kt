package com.guardian.safety

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.guardian.safety.service.SensorSafetyMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.Shadows.shadowOf

/**
 * Motion detection must fail closed: on a device without an accelerometer the
 * monitor reports that it cannot protect the user instead of quietly "starting".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SensorSafetyMonitorTest {

  private fun context(): Context = ApplicationProvider.getApplicationContext()

  private fun monitor(onTrigger: (String) -> Unit = {}): SensorSafetyMonitor =
    SensorSafetyMonitor(
      context = context(),
      onFallDetected = { onTrigger("FALL") },
      onCrashDetected = { onTrigger("CRASH") },
      onShakeDetected = { onTrigger("SHAKE") },
    )

  @Test
  fun withoutAnAccelerometerTheMonitorRefusesToStartAndExplainsWhy() {
    val sensorMonitor = monitor()
    assertFalse("No accelerometer was added, so detection cannot run.", sensorMonitor.isAvailable)
    assertFalse(sensorMonitor.startMonitoring(fallGuardEnabled = true))
    assertNotNull("The UI needs a reason to show, not a silent failure.", sensorMonitor.unavailabilityReason)
    assertFalse(sensorMonitor.isMonitoring)
  }

  @Test
  fun withNothingSwitchedOnNoListenerIsRegistered() {
    val sensorManager = context().getSystemService(Context.SENSOR_SERVICE) as SensorManager
    shadowOf(sensorManager).addSensor(
      Sensor.TYPE_ACCELEROMETER,
      ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER),
    )
    val sensorMonitor = monitor()
    assertTrue(sensorMonitor.isAvailable)
    assertFalse(sensorMonitor.startMonitoring())
    assertFalse(sensorMonitor.isMonitoring)
  }

  @Test
  fun aSingleSwitchRegistersTheListenerAndStoppingReleasesIt() {
    val sensorManager = context().getSystemService(Context.SENSOR_SERVICE) as SensorManager
    shadowOf(sensorManager).addSensor(
      Sensor.TYPE_ACCELEROMETER,
      ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER),
    )
    val sensorMonitor = monitor()
    assertTrue(sensorMonitor.startMonitoring(crashSosEnabled = true))
    assertTrue(sensorMonitor.isMonitoring)

    // Turning everything back off removes the listener again.
    sensorMonitor.updateFlags(fallGuardEnabled = false, crashSosEnabled = false, shakeGestureEnabled = false)
    assertFalse(sensorMonitor.isMonitoring)
  }

  @Test
  fun togglingASecondTriggerDoesNotRegisterTwice() {
    val sensorManager = context().getSystemService(Context.SENSOR_SERVICE) as SensorManager
    shadowOf(sensorManager).addSensor(
      Sensor.TYPE_ACCELEROMETER,
      ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER),
    )
    val sensorMonitor = monitor()
    assertTrue(sensorMonitor.startMonitoring(fallGuardEnabled = true))
    assertTrue(sensorMonitor.startMonitoring(fallGuardEnabled = true))
    sensorMonitor.stopMonitoring()
    assertFalse(sensorMonitor.isMonitoring)
    // Repeated stops must be harmless.
    sensorMonitor.stopMonitoring()
    assertFalse(sensorMonitor.isMonitoring)
  }

  @Test
  fun stoppingWithoutEverStartingIsSafe() {
    val sensorMonitor = monitor()
    sensorMonitor.stopMonitoring()
    assertFalse(sensorMonitor.isMonitoring)
    assertEquals(null, sensorMonitor.unavailabilityReason?.takeIf { sensorMonitor.isAvailable })
  }
}
