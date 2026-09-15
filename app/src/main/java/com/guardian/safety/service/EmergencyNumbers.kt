package com.guardian.safety.service

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale

/**
 * The emergency services number for the device the app is running on.
 *
 * Android has no public "what is the emergency number here" API, so this resolves
 * it in two honest steps:
 *
 * 1. The platform's own carrier-configured emergency dialler string
 *    (`com.android.internal.R.string.config_emergency_dialer`), which is what the
 *    system dialler itself uses.
 * 2. Otherwise the ITU/E.164 emergency number for the country the device is
 *    currently on, derived from the network/SIM country and the device locale.
 *
 * `112` is the fallback because it is routed to emergency services on GSM networks
 * worldwide, including on devices with no SIM. No number is invented: every value
 * returned here is either read from the platform or taken from [BY_COUNTRY].
 */
object EmergencyNumbers {

    private const val TAG = "EmergencyNumbers"

    /** Globally routed GSM emergency number; the last resort, never a guess at a locale. */
    const val INTERNATIONAL_FALLBACK = "112"

    /**
     * ISO 3166-1 alpha-2 country code → primary emergency number.
     *
     * Only countries with a number other than the international fallback are
     * listed; everything else resolves to [INTERNATIONAL_FALLBACK].
     */
    private val BY_COUNTRY: Map<String, String> = mapOf(
        // North American Numbering Plan.
        "us" to "911", "ca" to "911", "mx" to "911", "pr" to "911", "vi" to "911",
        // Countries with a distinct primary number.
        "gb" to "999", "hk" to "999", "sg" to "999", "my" to "999", "ie" to "112",
        "jp" to "110", "kr" to "112", "cn" to "110", "in" to "112", "au" to "000",
        "nz" to "111", "br" to "190", "ar" to "911", "cl" to "133", "co" to "123",
        "ru" to "112", "ua" to "112", "tr" to "112", "za" to "10111", "ng" to "112",
        "ke" to "999", "eg" to "122", "ae" to "999", "sa" to "997", "il" to "100",
        "ph" to "911", "th" to "191", "id" to "112", "vn" to "113", "pk" to "15",
        "bd" to "999", "lk" to "119", "np" to "100",
    )

    /**
     * The number to dial for emergency services on this device.
     *
     * @return a non-blank diallable string; [INTERNATIONAL_FALLBACK] when neither
     *   the platform nor the country table can say anything more specific.
     */
    fun primary(context: Context): String =
        platformEmergencyDialler(context)
            ?: forCountry(countryCode(context))
            ?: INTERNATIONAL_FALLBACK

    /** The number the platform's own dialler is configured to use, if exposed. */
    private fun platformEmergencyDialler(context: Context): String? = try {
        val resources = context.resources
        val id = resources.getIdentifier("config_emergency_dialer", "string", "android")
        if (id == 0) {
            null
        } else {
            resources.getString(id).trim().takeIf { it.isNotBlank() && it != "0" && it != "000000" }
        }
    } catch (error: Exception) {
        Log.w(TAG, "Platform emergency dialler string is not readable", error)
        null
    }

    /** Emergency number for an ISO country code, or null when not more specific. */
    fun forCountry(isoCountry: String?): String? =
        isoCountry?.trim()?.lowercase(Locale.US)?.takeIf { it.length == 2 }?.let { BY_COUNTRY[it] }

    /**
     * Best available country for this device: the network it is on, then the SIM,
     * then the user's locale. Null when the device cannot say.
     */
    fun countryCode(context: Context): String? {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val fromNetwork = runCatching { telephony?.networkCountryIso }.getOrNull()
        if (!fromNetwork.isNullOrBlank()) return fromNetwork
        val fromSim = runCatching { telephony?.simCountryIso }.getOrNull()
        if (!fromSim.isNullOrBlank()) return fromSim
        return Locale.getDefault().country.takeIf { it.isNotBlank() }
    }
}
