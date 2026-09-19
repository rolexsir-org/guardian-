package com.guardian.safety.billing

/**
 * What Guardian Pro actually changes.
 *
 * Guardian's rule: **nothing that can save a life is ever behind the paywall.**
 * SOS, emergency calling, emergency SMS, the medical ID, offline records and
 * live location during an emergency are free forever, on every build, signed in
 * or not.
 *
 * Pro raises *capacity and convenience* limits on top of that floor. Every limit
 * below is enforced in one place so the free tier can never silently drift into
 * blocking an emergency.
 */
object ProFeatures {

    /** Trusted contacts that can be stored without Pro. */
    const val FREE_CONTACT_LIMIT = 3

    /** Trusted contacts with Pro. */
    const val PRO_CONTACT_LIMIT = 25

    /** Days of encrypted location history retained without Pro. */
    const val FREE_LOCATION_RETENTION_DAYS = 7

    /** Days of encrypted location history retained with Pro. */
    const val PRO_LOCATION_RETENTION_DAYS = 90

    /** Minutes of rolling audio evidence buffered without Pro. */
    const val FREE_AUDIO_BUFFER_MINUTES = 5

    /** Minutes of rolling audio evidence buffered with Pro. */
    const val PRO_AUDIO_BUFFER_MINUTES = 30

    /**
     * How many trusted contacts this user may store.
     *
     * The limit applies to *adding* a contact. Contacts saved before a
     * subscription lapsed are never deleted and are still used in an emergency —
     * losing Pro must never quietly shrink someone's emergency contact list.
     */
    fun contactLimit(pro: Boolean): Int = if (pro) PRO_CONTACT_LIMIT else FREE_CONTACT_LIMIT

    /** Location-history retention window in days. */
    fun locationRetentionDays(pro: Boolean): Int =
        if (pro) PRO_LOCATION_RETENTION_DAYS else FREE_LOCATION_RETENTION_DAYS

    /** Rolling audio-evidence buffer length in minutes. */
    fun audioBufferMinutes(pro: Boolean): Int =
        if (pro) PRO_AUDIO_BUFFER_MINUTES else FREE_AUDIO_BUFFER_MINUTES

    /**
     * True when another contact may be added.
     *
     * [pro] is the *entitlement*, not a guess: callers pass
     * `ProState.Active`. An unknown or errored entitlement is treated as
     * not-Pro for limit purposes but never blocks an emergency action, because
     * no emergency action consults this function.
     */
    fun canAddContact(currentCount: Int, pro: Boolean): Boolean =
        currentCount < contactLimit(pro)

    /** Why an add was refused, for the UI to show verbatim. */
    fun contactLimitMessage(pro: Boolean): String = if (pro) {
        "Guardian Pro stores up to $PRO_CONTACT_LIMIT trusted contacts. Remove one to add another."
    } else {
        "The free plan stores $FREE_CONTACT_LIMIT trusted contacts. Guardian Pro raises this to " +
            "$PRO_CONTACT_LIMIT. Emergency calling, SOS and your medical ID stay free either way."
    }

    /** The benefit list shown on the paywall. Kept here so it cannot drift from the limits. */
    val benefits: List<String> = listOf(
        "Up to $PRO_CONTACT_LIMIT trusted contacts instead of $FREE_CONTACT_LIMIT",
        "$PRO_LOCATION_RETENTION_DAYS days of encrypted location history instead of $FREE_LOCATION_RETENTION_DAYS",
        "$PRO_AUDIO_BUFFER_MINUTES-minute rolling audio evidence buffer instead of $FREE_AUDIO_BUFFER_MINUTES",
        "Supports independent, ad-free safety software",
    )

    /** Stated plainly on the paywall so the value proposition is never misread. */
    const val FREE_FOREVER_NOTICE: String =
        "SOS, emergency calling, emergency SMS, your medical ID and live location during an " +
            "emergency are free forever and are never behind the paywall."
}
