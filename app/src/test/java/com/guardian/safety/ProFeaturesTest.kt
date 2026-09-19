package com.guardian.safety

import com.guardian.safety.billing.ProFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardian Pro must raise limits without ever gating a life-safety feature.
 *
 * These are plain JVM tests: the limit rules are pure functions precisely so the
 * paywall's behaviour can be asserted without a device, a store or a network.
 */
class ProFeaturesTest {

    @Test
    fun freeUsersGetAUsableNumberOfContacts() {
        // A free plan that allowed zero or one contact would effectively gate
        // emergency notification, which Guardian does not do.
        assertTrue(
            "The free contact limit must be usable in a real emergency.",
            ProFeatures.FREE_CONTACT_LIMIT >= 3,
        )
    }

    @Test
    fun proRaisesEveryLimitItAdvertises() {
        assertTrue(ProFeatures.PRO_CONTACT_LIMIT > ProFeatures.FREE_CONTACT_LIMIT)
        assertTrue(ProFeatures.PRO_LOCATION_RETENTION_DAYS > ProFeatures.FREE_LOCATION_RETENTION_DAYS)
        assertTrue(ProFeatures.PRO_AUDIO_BUFFER_MINUTES > ProFeatures.FREE_AUDIO_BUFFER_MINUTES)
    }

    @Test
    fun theContactLimitIsEnforcedForFreeUsers() {
        val limit = ProFeatures.FREE_CONTACT_LIMIT
        assertTrue(ProFeatures.canAddContact(currentCount = limit - 1, pro = false))
        assertFalse(ProFeatures.canAddContact(currentCount = limit, pro = false))
        assertFalse(ProFeatures.canAddContact(currentCount = limit + 5, pro = false))
    }

    @Test
    fun proUsersKeepAddingPastTheFreeLimit() {
        assertTrue(ProFeatures.canAddContact(currentCount = ProFeatures.FREE_CONTACT_LIMIT, pro = true))
        assertFalse(ProFeatures.canAddContact(currentCount = ProFeatures.PRO_CONTACT_LIMIT, pro = true))
    }

    @Test
    fun theLimitFunctionsAgreeWithTheAdvertisedConstants() {
        assertEquals(ProFeatures.FREE_CONTACT_LIMIT, ProFeatures.contactLimit(pro = false))
        assertEquals(ProFeatures.PRO_CONTACT_LIMIT, ProFeatures.contactLimit(pro = true))
        assertEquals(
            ProFeatures.FREE_LOCATION_RETENTION_DAYS,
            ProFeatures.locationRetentionDays(pro = false),
        )
        assertEquals(
            ProFeatures.PRO_LOCATION_RETENTION_DAYS,
            ProFeatures.locationRetentionDays(pro = true),
        )
        assertEquals(
            ProFeatures.FREE_AUDIO_BUFFER_MINUTES,
            ProFeatures.audioBufferMinutes(pro = false),
        )
        assertEquals(
            ProFeatures.PRO_AUDIO_BUFFER_MINUTES,
            ProFeatures.audioBufferMinutes(pro = true),
        )
    }

    @Test
    fun theRefusalMessageExplainsTheLimitAndNamesTheFreeFloor() {
        val message = ProFeatures.contactLimitMessage(pro = false)
        assertTrue(message.contains(ProFeatures.FREE_CONTACT_LIMIT.toString()))
        assertTrue(message.contains(ProFeatures.PRO_CONTACT_LIMIT.toString()))
        // The user must be told the emergency features are unaffected.
        assertTrue(message.lowercase().contains("free"))
    }

    @Test
    fun theProDialogNeverClaimsToUnlockAnEmergencyFeature() {
        val notice = ProFeatures.FREE_FOREVER_NOTICE.lowercase()
        listOf("sos", "emergency calling", "medical id").forEach { promise ->
            assertTrue(
                "The paywall must state that '$promise' is free.",
                notice.contains(promise),
            )
        }
    }

    @Test
    fun everyAdvertisedBenefitQuotesARealNumber() {
        assertTrue("The paywall must list benefits.", ProFeatures.benefits.isNotEmpty())
        // Each benefit that promises a bigger number must actually cite the
        // constants, so marketing copy cannot drift from enforced behaviour.
        assertTrue(
            ProFeatures.benefits.any { it.contains(ProFeatures.PRO_CONTACT_LIMIT.toString()) },
        )
        assertTrue(
            ProFeatures.benefits.any { it.contains(ProFeatures.PRO_LOCATION_RETENTION_DAYS.toString()) },
        )
        assertTrue(
            ProFeatures.benefits.any { it.contains(ProFeatures.PRO_AUDIO_BUFFER_MINUTES.toString()) },
        )
    }
}
