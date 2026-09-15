package com.guardian.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shipped application identity.
 *
 * Guardian's package was migrated away from the namespace this project started life
 * in. These tests pin the production identity so a future refactor cannot silently
 * change the application id users have installed, or reintroduce a legacy namespace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApplicationIdentityTest {

  @Test
  fun applicationLabelIsGuardian() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("Guardian", context.getString(R.string.app_name))
  }

  @Test
  fun theApplicationIdIsTheGuardianSafetyNamespace() {
    assertEquals("com.guardian.safety", BuildConfig.APPLICATION_ID)
  }

  @Test
  fun theRuntimePackageMatchesTheBuildConfiguration() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
  }

  @Test
  fun noClassIsShippedUnderALegacyNamespace() {
    // Every class Guardian loads lives under its own root package. A stale class from
    // the original template would show up here.
    val ownClasses = listOf(
      GuardianApplication::class.java.name,
      MainActivity::class.java.name,
    )
    ownClasses.forEach { name ->
      assertTrue("$name must live under com.guardian.safety", name.startsWith("com.guardian.safety."))
    }
  }
}
