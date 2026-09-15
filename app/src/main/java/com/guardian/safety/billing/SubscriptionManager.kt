package com.guardian.safety.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.guardian.safety.BuildConfig
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseResult
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the app can honestly say about Guardian Pro on this device. */
sealed interface ProState {
    /** No public SDK key is compiled into this build, so purchases are unavailable. */
    data object NotConfigured : ProState

    /** Configured but the entitlement has not been read yet. */
    data object Unknown : ProState

    /** The backend entitlement is active. */
    data class Active(val expiresAt: Long?) : ProState

    /** Configured, reachable, and the entitlement is not active. */
    data object Inactive : ProState

    /**
     * The entitlement could not be determined. Never treated as "not subscribed"
     * in a way that hides the failure: the message says what went wrong.
     */
    data class Error(val message: String) : ProState
}

/** Result of a purchase attempt, so the UI never has to guess. */
sealed interface PurchaseOutcome {
    data class Success(val expiresAt: Long?) : PurchaseOutcome
    data object CancelledByUser : PurchaseOutcome
    data class Failed(val message: String) : PurchaseOutcome
}

/** Result of a restore attempt. */
sealed interface RestoreOutcome {
    data class Restored(val active: Boolean) : RestoreOutcome
    data class Failed(val message: String) : RestoreOutcome
}

/**
 * Guardian Pro subscriptions through RevenueCat.
 *
 * RevenueCat is completely independent from the Guardian Cloudflare backend: it
 * talks to the store and to RevenueCat, never to the Worker, and it holds no
 * safety data. Only the **public** Android SDK key is compiled in — a secret
 * RevenueCat key must never ship in a client.
 *
 * Failure rules:
 * * With no SDK key the manager stays in [ProState.NotConfigured] and every
 *   operation fails with a clear message. Nothing is faked.
 * * Entitlement state comes from RevenueCat's own `CustomerInfo`; when it cannot
 *   be read the state is [ProState.Error], never [ProState.Inactive].
 * * A purchase is [PurchaseOutcome.Success] only after RevenueCat returns the
 *   updated `CustomerInfo`. A user cancel is reported as a cancel.
 * * Identity follows the Guardian session: signing in calls `logIn` with the real
 *   user id so purchases attach to the account, signing out calls `logOut`.
 */
class SubscriptionManager(private val context: Context) {

    private val _state = MutableStateFlow<ProState>(initialState())
    val state: StateFlow<ProState> = _state.asStateFlow()

    private val _offerings = MutableStateFlow<Offerings?>(null)
    val offerings: StateFlow<Offerings?> = _offerings.asStateFlow()

    /** The entitlement this app treats as Guardian Pro. */
    val entitlementId: String =
        BuildConfig.REVENUECAT_ENTITLEMENT_ID.trim().takeIf { it.isNotEmpty() } ?: DEFAULT_ENTITLEMENT_ID

    /** True when a real public SDK key is compiled into this build. */
    val isConfigured: Boolean = sdkKey.isNotEmpty()

    @Volatile
    private var sdkInitialised = false

    /** The RevenueCat user the SDK is currently operating as, or null. */
    @Volatile
    var currentAppUserId: String? = null
        private set

    private fun initialState(): ProState = if (sdkKey.isEmpty()) ProState.NotConfigured else ProState.Unknown

    /**
     * Prepares the SDK. Safe to call more than once; a no-op when no key exists.
     *
     * @param appUserId the Guardian user id to attach purchases to, or null to
     *   start anonymously (the SDK then generates its own anonymous id).
     * @return true when the SDK is usable after this call.
     */
    fun configure(appUserId: String?): Boolean {
        if (sdkKey.isEmpty()) {
            _state.value = ProState.NotConfigured
            Log.w(TAG, "REVENUECAT_ANDROID_API_KEY is not configured; subscriptions are disabled.")
            return false
        }
        if (sdkInitialised && Purchases.isConfigured) {
            currentAppUserId = runCatching { Purchases.sharedInstance.appUserID }.getOrNull()
            return true
        }
        return try {
            Purchases.logLevel = LogLevel.WARN
            Purchases.configure(
                PurchasesConfiguration.Builder(context, sdkKey)
                    .appUserID(appUserId?.takeIf { it.isNotBlank() })
                    .build(),
            )
            sdkInitialised = true
            currentAppUserId = runCatching { Purchases.sharedInstance.appUserID }.getOrNull()
            if (_state.value == ProState.NotConfigured) _state.value = ProState.Unknown
            true
        } catch (error: Exception) {
            Log.e(TAG, "RevenueCat could not be initialised", error)
            _state.value = ProState.Error("Subscriptions could not be started on this device (${error.javaClass.simpleName}).")
            false
        }
    }

    /** Reads the entitlement and the current offerings. Never throws. */
    suspend fun refresh() {
        if (!ensureReady()) return
        val purchases = purchasesOrNull() ?: return
        try {
            val info = purchases.awaitCustomerInfo()
            applyEntitlement(info.entitlements.all[entitlementId]?.isActive == true, info.entitlements.all[entitlementId]?.expirationDate?.time)
        } catch (error: PurchasesException) {
            Log.w(TAG, "Could not read customer info: ${error.underlyingErrorMessage ?: error.message}")
            _state.value = ProState.Error(
                error.underlyingErrorMessage ?: "Guardian could not check your subscription. Try again when you have a connection.",
            )
        } catch (error: Exception) {
            _state.value = ProState.Error("Guardian could not check your subscription (${error.javaClass.simpleName}).")
        }

        try {
            _offerings.value = purchases.awaitOfferings()
        } catch (error: Exception) {
            // Offerings are only needed to start a purchase; the entitlement state
            // above is what the rest of the app depends on.
            Log.w(TAG, "Could not load offerings: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Attaches the SDK to a signed-in Guardian account.
     *
     * @return null on success, otherwise the reason it failed.
     */
    suspend fun signIn(userId: String): String? {
        if (!ensureReady()) return "Subscriptions are not configured on this build."
        val purchases = purchasesOrNull() ?: return "Subscriptions are unavailable right now."
        return try {
            val result = purchases.awaitLogIn(userId)
            currentAppUserId = userId
            applyEntitlement(
                result.customerInfo.entitlements.all[entitlementId]?.isActive == true,
                result.customerInfo.entitlements.all[entitlementId]?.expirationDate?.time,
            )
            null
        } catch (error: Exception) {
            Log.w(TAG, "RevenueCat logIn failed", error)
            "Purchases could not be linked to your account (${error.javaClass.simpleName})."
        }
    }

    /** Detaches the SDK from the account, back to an anonymous id. */
    suspend fun signOut(): String? {
        if (!ensureReady()) return null
        val purchases = purchasesOrNull() ?: return null
        return try {
            val info = purchases.awaitLogOut()
            currentAppUserId = runCatching { purchases.appUserID }.getOrNull()
            applyEntitlement(
                info.entitlements.all[entitlementId]?.isActive == true,
                info.entitlements.all[entitlementId]?.expirationDate?.time,
            )
            null
        } catch (error: Exception) {
            Log.w(TAG, "RevenueCat logOut failed", error)
            "Purchases could not be detached from your account (${error.javaClass.simpleName})."
        }
    }

    /** The package the user is offered for Guardian Pro, or null with a reason. */
    fun proPackage(): Package? = _offerings.value?.current?.monthly
        ?: _offerings.value?.current?.availablePackages?.firstOrNull()

    /** Starts a purchase in [activity]. Only a RevenueCat-confirmed result is success. */
    suspend fun purchase(activity: Activity): PurchaseOutcome {
        if (!ensureReady()) {
            return PurchaseOutcome.Failed("Subscriptions are not configured on this build.")
        }
        val purchases = purchasesOrNull()
            ?: return PurchaseOutcome.Failed("Subscriptions are unavailable right now.")
        val selected = proPackage()
            ?: return PurchaseOutcome.Failed("No Guardian Pro product is available from the store yet. Pull to refresh and try again.")

        return try {
            val result: PurchaseResult = purchases.awaitPurchase(
                com.revenuecat.purchases.PurchaseParams.Builder(activity, selected).build(),
            )
            val entitlement = result.customerInfo.entitlements.all[entitlementId]
            val active = entitlement?.isActive == true
            applyEntitlement(active, entitlement?.expirationDate?.time)
            if (active) {
                PurchaseOutcome.Success(entitlement?.expirationDate?.time)
            } else {
                // The store accepted the payment but the entitlement is not active
                // yet. That is not something to report as a completed upgrade.
                PurchaseOutcome.Failed("The store completed the purchase but Guardian Pro is not active yet. Try 'Restore purchases'.")
            }
        } catch (error: PurchasesTransactionException) {
            if (error.userCancelled) {
                PurchaseOutcome.CancelledByUser
            } else {
                PurchaseOutcome.Failed(
                    error.underlyingErrorMessage ?: "The purchase could not be completed (${error.code.name}).",
                )
            }
        } catch (error: Exception) {
            PurchaseOutcome.Failed("The purchase could not be completed (${error.javaClass.simpleName}).")
        }
    }

    /** Asks RevenueCat to re-read entitlements from the store. */
    suspend fun restore(): RestoreOutcome {
        if (!ensureReady()) {
            return RestoreOutcome.Failed("Subscriptions are not configured on this build.")
        }
        val purchases = purchasesOrNull()
            ?: return RestoreOutcome.Failed("Subscriptions are unavailable right now.")
        return try {
            val info = purchases.awaitRestore()
            val entitlement = info.entitlements.all[entitlementId]
            val active = entitlement?.isActive == true
            applyEntitlement(active, entitlement?.expirationDate?.time)
            RestoreOutcome.Restored(active)
        } catch (error: Exception) {
            RestoreOutcome.Failed(
                (error as? PurchasesException)?.underlyingErrorMessage
                    ?: "Purchases could not be restored (${error.javaClass.simpleName}).",
            )
        }
    }

    private fun applyEntitlement(active: Boolean, expiresAt: Long?) {
        _state.value = if (active) ProState.Active(expiresAt) else ProState.Inactive
    }

    private fun ensureReady(): Boolean {
        if (sdkKey.isEmpty()) {
            _state.value = ProState.NotConfigured
            return false
        }
        if (sdkInitialised && Purchases.isConfigured) return true
        return configure(currentAppUserId)
    }

    private fun purchasesOrNull(): Purchases? = try {
        if (Purchases.isConfigured) Purchases.sharedInstance else null
    } catch (error: Exception) {
        Log.w(TAG, "RevenueCat singleton is not available", error)
        null
    }

    companion object {
        private const val TAG = "SubscriptionManager"

        /** Public Android SDK key. Empty means "subscriptions are not available". */
        private val sdkKey: String = BuildConfig.REVENUECAT_ANDROID_API_KEY.trim()

        /**
         * Fallback entitlement identifier. An entitlement id is dashboard
         * configuration, not a secret, so a documented default is safe.
         */
        const val DEFAULT_ENTITLEMENT_ID = "guardian_pro"
    }
}
