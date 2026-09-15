package com.guardian.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.guardian.safety.billing.ProState
import com.guardian.safety.billing.PurchaseOutcome
import com.guardian.safety.billing.RestoreOutcome
import com.guardian.safety.billing.SubscriptionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Subscription state must never be fabricated.
 *
 * These tests run on a build with no `REVENUECAT_ANDROID_API_KEY`, which is exactly
 * the configuration a developer has before the dashboard exists. The rules under
 * test: the SDK is never initialised, the state says "not configured" rather than
 * "not subscribed", and both purchase and restore fail with a real message instead
 * of reporting a purchase that did not happen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubscriptionManagerTest {

  private fun manager(): SubscriptionManager =
    SubscriptionManager(ApplicationProvider.getApplicationContext<Context>())

  @Test
  fun aBuildWithoutASdkKeyIsNotConfiguredAndSaysSo() {
    val subscriptions = manager()
    if (subscriptions.isConfigured) {
      // A developer machine with a real key: this assertion does not apply.
      return
    }
    assertEquals(ProState.NotConfigured, subscriptions.state.value)
  }

  @Test
  fun configureWithoutAKeyDoesNotInitialiseTheSdk() {
    val subscriptions = manager()
    if (com.revenuecat.purchases.Purchases.isConfigured) return
    val ready = subscriptions.configure("user-1")
    if (subscriptions.isConfigured) {
      assertTrue(ready)
    } else {
      assertFalse("A build without a key must not report that subscriptions are ready.", ready)
      assertEquals(ProState.NotConfigured, subscriptions.state.value)
    }
  }

  @Test
  fun purchaseWithoutConfigurationFailsInsteadOfPretending() = runBlocking {
    val subscriptions = manager()
    if (subscriptions.isConfigured) return@runBlocking
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val outcome = subscriptions.purchase(activity)
    assertTrue(
      "An unconfigured build must not report a successful purchase.",
      outcome is PurchaseOutcome.Failed,
    )
  }

  @Test
  fun restoreWithoutConfigurationFailsInsteadOfPretending() = runBlocking {
    val subscriptions = manager()
    if (subscriptions.isConfigured) return@runBlocking
    val outcome = subscriptions.restore()
    assertTrue(outcome is RestoreOutcome.Failed)
  }

  @Test
  fun theEntitlementIdentifierIsNeverBlank() {
    assertTrue(manager().entitlementId.isNotBlank())
  }
}
