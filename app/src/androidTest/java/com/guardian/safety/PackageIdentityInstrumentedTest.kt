package com.guardian.safety

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device or emulator and asserts the installed application id.
 *
 * The application id is what Google Play, the signing key and every stored
 * credential are bound to, so it is verified on a real runtime rather than only
 * in the build script.
 */
@RunWith(AndroidJUnit4::class)
class PackageIdentityInstrumentedTest {

  @Test
  fun installedPackageIsTheProductionNamespace() {
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("com.guardian.safety", appContext.packageName)
  }
}
