package com.guardian.safety

import com.guardian.safety.service.EmergencyNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emergency number shown anywhere in the app must come from the device or from
 * a documented country table — never from a hard-coded guess about where the user
 * lives.
 */
class EmergencyNumbersTest {

  @Test
  fun northAmericanCountriesResolveToNineOneOne() {
    listOf("US", "us", "CA", "mx").forEach { iso ->
      assertEquals("911", EmergencyNumbers.forCountry(iso))
    }
  }

  @Test
  fun countriesWithTheirOwnPrimaryNumberResolveToIt() {
    assertEquals("999", EmergencyNumbers.forCountry("GB"))
    assertEquals("110", EmergencyNumbers.forCountry("jp"))
    assertEquals("000", EmergencyNumbers.forCountry("AU"))
  }

  @Test
  fun unlistedCountriesFallBackToTheInternationalNumber() {
    // Germany, France and most of the EU route 112; nothing more specific is claimed.
    assertNull(EmergencyNumbers.forCountry("DE"))
    assertNull(EmergencyNumbers.forCountry("FR"))
  }

  @Test
  fun anUnknownCountryNeverProducesAnInventedNumber() {
    assertNull(EmergencyNumbers.forCountry(null))
    assertNull(EmergencyNumbers.forCountry(""))
    assertNull(EmergencyNumbers.forCountry("NOT_A_COUNTRY"))
    // A three letter code is not an ISO 3166-1 alpha-2 code.
    assertNull(EmergencyNumbers.forCountry("USA"))
  }

  @Test
  fun theFallbackIsTheGloballyRoutedGsmNumber() {
    assertTrue(EmergencyNumbers.INTERNATIONAL_FALLBACK.isNotEmpty())
    assertEquals("112", EmergencyNumbers.INTERNATIONAL_FALLBACK)
  }
}
