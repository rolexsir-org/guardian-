package com.guardian.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.guardian.safety.service.SecureEncryptedPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every protection switch is a real, persisted value in encrypted storage.
 *
 * Defaults matter for safety: anything that acts on the user's behalf without a tap
 * — placing calls, sending messages, recording audio, firing an SOS from a sensor —
 * starts **off**, so the app never silently does something on their behalf.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreferencesFlagsTest {

  private fun context(): Context = ApplicationProvider.getApplicationContext()

  private fun prefs(): SecureEncryptedPreferences = SecureEncryptedPreferences.getInstance(context())

  @Test
  fun featuresThatActForTheUserDefaultToOff() {
    val preferences = prefs()
    assertFalse("Voice activation must not be on before the user turns it on.", preferences.isVoiceActivationEnabled())
    assertFalse(preferences.isFallDetectionEnabled())
    assertFalse(preferences.isCrashSosEnabled())
    assertFalse(preferences.isShakeGestureEnabled())
    assertFalse(preferences.isAutoSosSilenceEnabled())
    assertFalse(preferences.isBackgroundMonitoringEnabled())
    assertFalse("Audio is never recorded until the user asks for it.", preferences.isAudioBlackboxEnabled())
    assertFalse("Evidence is never uploaded until the user asks for it.", preferences.isCloudRecordEnabled())
  }

  @Test
  fun featuresThatOnlyProtectTheUserDefaultToOn() {
    val preferences = prefs()
    assertTrue(preferences.isLiveGpsEnabled())
    assertTrue(preferences.isBiometricLockEnabled())
    assertTrue(preferences.isGuardianProximityEnabled())
  }

  @Test
  fun flagsRoundTripThroughEncryptedStorage() {
    val preferences = prefs()
    preferences.setCrashSosEnabled(true)
    preferences.setAudioBlackboxEnabled(true)
    preferences.setFallDetectionEnabled(true)
    assertTrue(preferences.isCrashSosEnabled())
    assertTrue(preferences.isAudioBlackboxEnabled())
    assertTrue(preferences.isFallDetectionEnabled())

    preferences.setCrashSosEnabled(false)
    assertFalse(preferences.isCrashSosEnabled())
  }

  @Test
  fun flowVariantsMatchTheSynchronousReads() = runBlocking {
    val preferences = prefs()
    preferences.setFallDetectionEnabled(true)
    assertTrue(preferences.isFallDetectionEnabledFlow().first())
    preferences.setFallDetectionEnabled(false)
    assertFalse(preferences.isFallDetectionEnabledFlow().first())
    assertEquals(preferences.isCrashSosEnabled(), preferences.isCrashSosEnabledFlow().first())
    assertEquals(preferences.isLiveGpsEnabled(), preferences.isLiveGpsEnabledFlow().first())
  }

  @Test
  fun incidentSubscriptionsCoverEveryCategoryAndCanBeTurnedOff() {
    val preferences = prefs()
    val categories = listOf("Crime", "Hazard", "Weather", "Medical", "SOS", "Community")
    val defaults = preferences.incidentSubscriptions(categories)
    assertEquals(categories.size, defaults.size)
    assertTrue("Alerts are on by default so the user is not silently unprotected.", defaults.values.all { it })

    preferences.setIncidentSubscribed("Weather", false)
    val updated = preferences.incidentSubscriptions(categories)
    assertFalse(updated.getValue("Weather"))
    assertTrue(updated.getValue("Crime"))

    // An unknown category is not materialised into an invented preference.
    preferences.setIncidentSubscribed("NotACategory", false)
    assertFalse("NotACategory" in preferences.incidentSubscriptions(categories))
  }

  @Test
  fun flagsAreNeverWrittenToAPlaintextPreferenceFile() {
    val preferences = prefs()
    preferences.setCrashSosEnabled(true)
    preferences.setAudioBlackboxEnabled(true)
    preferences.setFallDetectionEnabled(true)

    val sharedPrefs = File(context().applicationInfo.dataDir, "shared_prefs")
    val files = sharedPrefs.listFiles()?.toList().orEmpty()
    assertTrue("Encrypted storage should have created its file.", files.isNotEmpty())

    // EncryptedSharedPreferences encrypts keys *and* values, so the plaintext key
    // names must not appear in any file on disk — not in the encrypted file and not
    // in an unencrypted fallback (there is no fallback, and this asserts it).
    files.forEach { file ->
      val contents = runCatching { file.readText() }.getOrDefault("")
      listOf("secure_crash_sos", "secure_audio_blackbox", "secure_fall_detection").forEach { key ->
        assertFalse("$key was found in plaintext in ${file.name}.", contents.contains(key))
      }
    }
  }

  @Test
  fun theStoreIsASingletonAndReportsNoErrorWhenItOpens() {
    assertSame(prefs(), prefs())
    assertNull("Storage opened successfully, so there is nothing to report.", SecureEncryptedPreferences.storageError.value)
  }
}
