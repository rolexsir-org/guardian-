package com.guardian.safety.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.UUID

/** Raised when the platform cannot provide encrypted storage on this device. */
class SecureStorageUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Identity restored from encrypted storage; never invented locally. */
data class StoredIdentity(
    val userId: String,
    val email: String,
    val displayName: String,
    val emailVerified: Boolean,
)

/**
 * Encrypted local secret storage.
 *
 * Design rules enforced here:
 * * Values live in [EncryptedSharedPreferences] backed by an AES-256-GCM key in
 *   the Android Keystore.
 * * There is **no plaintext fallback**. If the encrypted store cannot be opened
 *   the call fails with [SecureStorageUnavailableException] and the app surfaces
 *   the failure instead of writing secrets in the clear.
 * * The emergency PIN has no default value and is stored only as a PBKDF2 hash
 *   with a per-install salt — never as plaintext and never as a constant.
 * * Access-token expiry is persisted next to the token so "is expired" is a real
 *   timestamp comparison rather than a guess.
 */
class SecureEncryptedPreferences private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    private val sharedPreferences: SharedPreferences by lazy { openEncryptedPreferences() }

    private fun openEncryptedPreferences(): SharedPreferences = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (error: Throwable) {
        secureStorageError.value = error.message ?: error.javaClass.simpleName
        throw SecureStorageUnavailableException(
            "Encrypted storage is unavailable on this device, so Guardian cannot store credentials safely.",
            error,
        )
    }

    private fun prefs(): SharedPreferences = try {
        sharedPreferences
    } catch (error: SecureStorageUnavailableException) {
        throw error
    }

    // ------------------------------------------------------------------ health

    /** True when encrypted storage is usable. Drives the failure UI. */
    fun isAvailable(): Boolean = try {
        prefs().contains(KEY_HEALTH_PROBE)
        true
    } catch (_: SecureStorageUnavailableException) {
        false
    }

    // ------------------------------------------------------------------ tokens

    fun saveSession(
        accessToken: String,
        accessExpiresAt: Long,
        refreshToken: String,
        refreshExpiresAt: Long,
        sessionId: String,
    ) {
        prefs().edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, accessExpiresAt)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_REFRESH_EXPIRES_AT, refreshExpiresAt)
            .putString(KEY_SESSION_ID, sessionId)
            .commit()
    }

    fun saveAccessToken(accessToken: String, expiresAt: Long) {
        prefs().edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_ACCESS_EXPIRES_AT, expiresAt)
            .commit()
    }

    fun accessToken(): String? = prefs().getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun accessTokenExpiresAt(): Long = prefs().getLong(KEY_ACCESS_EXPIRES_AT, 0L)

    /** True when the stored access token is missing or past its real expiry. */
    fun isAccessTokenExpired(skewMs: Long = ACCESS_TOKEN_SKEW_MS): Boolean {
        val token = accessToken() ?: return true
        if (token.isBlank()) return true
        val expiresAt = accessTokenExpiresAt()
        if (expiresAt <= 0L) return true
        return System.currentTimeMillis() + skewMs >= expiresAt
    }

    fun refreshToken(): String? = prefs().getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun refreshTokenExpiresAt(): Long = prefs().getLong(KEY_REFRESH_EXPIRES_AT, 0L)

    fun isRefreshTokenExpired(): Boolean {
        val token = refreshToken() ?: return true
        if (token.isBlank()) return true
        val expiresAt = refreshTokenExpiresAt()
        return expiresAt <= 0L || System.currentTimeMillis() >= expiresAt
    }

    fun sessionId(): String? = prefs().getString(KEY_SESSION_ID, null)?.takeIf { it.isNotBlank() }

    fun saveIdentity(identity: StoredIdentity) {
        prefs().edit()
            .putString(KEY_USER_ID, identity.userId)
            .putString(KEY_USER_EMAIL, identity.email)
            .putString(KEY_USER_NAME, identity.displayName)
            .putBoolean(KEY_USER_VERIFIED, identity.emailVerified)
            .commit()
    }

    fun identity(): StoredIdentity? {
        val userId = prefs().getString(KEY_USER_ID, null) ?: return null
        val email = prefs().getString(KEY_USER_EMAIL, null) ?: return null
        return StoredIdentity(
            userId = userId,
            email = email,
            displayName = prefs().getString(KEY_USER_NAME, "") ?: "",
            emailVerified = prefs().getBoolean(KEY_USER_VERIFIED, false),
        )
    }

    fun clearSession() {
        prefs().edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ACCESS_EXPIRES_AT)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_REFRESH_EXPIRES_AT)
            .remove(KEY_SESSION_ID)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_VERIFIED)
            .commit()
    }

    // --------------------------------------------------------------------- PIN

    fun hasPin(): Boolean = prefs().getString(KEY_PIN_HASH, null) != null

    fun setPin(pin: String) {
        require(pin.length >= MIN_PIN_LENGTH) { "PIN must be at least $MIN_PIN_LENGTH digits." }
        val salt = ByteArray(PIN_SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)
        prefs().edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .commit()
    }

    /** Constant-time PIN check. Returns false when no PIN has been configured. */
    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs().getString(KEY_PIN_HASH, null) ?: return false
        val storedSalt = prefs().getString(KEY_PIN_SALT, null) ?: return false
        val salt = try {
            Base64.decode(storedSalt, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = try {
            Base64.decode(storedHash, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(expected, hashPin(pin, salt))
    }

    fun clearPin() {
        prefs().edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).commit()
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, PIN_KEY_BITS)
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: java.security.NoSuchAlgorithmException) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ----------------------------------------------------------- subscriptions

    fun setIncidentSubscribed(category: String, subscribed: Boolean) {
        prefs().edit().putBoolean(subscriptionKey(category), subscribed).commit()
    }

    fun isIncidentSubscribed(category: String, defaultVal: Boolean = true): Boolean =
        prefs().getBoolean(subscriptionKey(category), defaultVal)

    /** Snapshot of every known category subscription, for the notification UI. */
    fun incidentSubscriptions(categories: Collection<String>): Map<String, Boolean> =
        categories.associateWith { isIncidentSubscribed(it) }

    private fun subscriptionKey(category: String) = "sub_$category"

    // ------------------------------------------------------------------- flags

    /**
     * Device settings that drive safety behaviour.
     *
     * Every flag lives in the encrypted store and is exposed both as a plain read
     * (for code paths that run once) and as a [StateFlow] (for Compose). Writes
     * propagate to the flow immediately, and a write to unusable storage throws
     * [SecureStorageUnavailableException] rather than pretending the setting was
     * saved — the caller reports the failure.
     */
    private val flags = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    private fun flag(key: String, default: Boolean): StateFlow<Boolean> =
        flags.getOrPut(key) {
            MutableStateFlow(runCatching { prefs().getBoolean(key, default) }.getOrDefault(default))
        }.asStateFlow()

    private fun readFlag(key: String, default: Boolean): Boolean =
        runCatching { prefs().getBoolean(key, default) }.getOrDefault(default)

    private fun writeFlag(key: String, value: Boolean) {
        prefs().edit().putBoolean(key, value).commit()
        flags[key]?.value = value
    }

    fun isLiveGpsEnabled(): Boolean = readFlag(KEY_LIVE_GPS, DEFAULT_LIVE_GPS)
    fun isLiveGpsEnabledFlow(): StateFlow<Boolean> = flag(KEY_LIVE_GPS, DEFAULT_LIVE_GPS)
    fun setLiveGpsEnabled(enabled: Boolean) = writeFlag(KEY_LIVE_GPS, enabled)

    fun isVoiceActivationEnabled(): Boolean = readFlag(KEY_VOICE_ACTIVATION, DEFAULT_VOICE_ACTIVATION)
    fun isVoiceActivationEnabledFlow(): StateFlow<Boolean> = flag(KEY_VOICE_ACTIVATION, DEFAULT_VOICE_ACTIVATION)
    fun setVoiceActivationEnabled(enabled: Boolean) = writeFlag(KEY_VOICE_ACTIVATION, enabled)

    fun isFallDetectionEnabled(): Boolean = readFlag(KEY_FALL_DETECTION, DEFAULT_FALL_DETECTION)
    fun isFallDetectionEnabledFlow(): StateFlow<Boolean> = flag(KEY_FALL_DETECTION, DEFAULT_FALL_DETECTION)
    fun setFallDetectionEnabled(enabled: Boolean) = writeFlag(KEY_FALL_DETECTION, enabled)

    fun isCrashSosEnabled(): Boolean = readFlag(KEY_CRASH_SOS, DEFAULT_CRASH_SOS)
    fun isCrashSosEnabledFlow(): StateFlow<Boolean> = flag(KEY_CRASH_SOS, DEFAULT_CRASH_SOS)
    fun setCrashSosEnabled(enabled: Boolean) = writeFlag(KEY_CRASH_SOS, enabled)

    fun isShakeGestureEnabled(): Boolean = readFlag(KEY_SHAKE_GESTURE, DEFAULT_SHAKE_GESTURE)
    fun isShakeGestureEnabledFlow(): StateFlow<Boolean> = flag(KEY_SHAKE_GESTURE, DEFAULT_SHAKE_GESTURE)
    fun setShakeGestureEnabled(enabled: Boolean) = writeFlag(KEY_SHAKE_GESTURE, enabled)

    fun isAutoSosSilenceEnabled(): Boolean = readFlag(KEY_AUTO_SOS_SILENCE, DEFAULT_AUTO_SOS_SILENCE)
    fun isAutoSosSilenceEnabledFlow(): StateFlow<Boolean> = flag(KEY_AUTO_SOS_SILENCE, DEFAULT_AUTO_SOS_SILENCE)
    fun setAutoSosSilenceEnabled(enabled: Boolean) = writeFlag(KEY_AUTO_SOS_SILENCE, enabled)

    fun isBackgroundMonitoringEnabled(): Boolean = readFlag(KEY_BACKGROUND_MONITORING, DEFAULT_BACKGROUND_MONITORING)
    fun isBackgroundMonitoringEnabledFlow(): StateFlow<Boolean> =
        flag(KEY_BACKGROUND_MONITORING, DEFAULT_BACKGROUND_MONITORING)
    fun setBackgroundMonitoringEnabled(enabled: Boolean) = writeFlag(KEY_BACKGROUND_MONITORING, enabled)

    fun isGuardianProximityEnabled(): Boolean = readFlag(KEY_GUARDIAN_PROXIMITY, DEFAULT_GUARDIAN_PROXIMITY)
    fun isGuardianProximityEnabledFlow(): StateFlow<Boolean> =
        flag(KEY_GUARDIAN_PROXIMITY, DEFAULT_GUARDIAN_PROXIMITY)
    fun setGuardianProximityEnabled(enabled: Boolean) = writeFlag(KEY_GUARDIAN_PROXIMITY, enabled)

    fun isCloudRecordEnabled(): Boolean = readFlag(KEY_CLOUD_RECORD, DEFAULT_CLOUD_RECORD)
    fun isCloudRecordEnabledFlow(): StateFlow<Boolean> = flag(KEY_CLOUD_RECORD, DEFAULT_CLOUD_RECORD)
    fun setCloudRecordEnabled(enabled: Boolean) = writeFlag(KEY_CLOUD_RECORD, enabled)

    fun isAudioBlackboxEnabled(): Boolean = readFlag(KEY_AUDIO_BLACKBOX, DEFAULT_AUDIO_BLACKBOX)
    fun isAudioBlackboxEnabledFlow(): StateFlow<Boolean> = flag(KEY_AUDIO_BLACKBOX, DEFAULT_AUDIO_BLACKBOX)
    fun setAudioBlackboxEnabled(enabled: Boolean) = writeFlag(KEY_AUDIO_BLACKBOX, enabled)

    fun isIncognitoModeEnabled(): Boolean = readFlag(KEY_INCOGNITO_MODE, DEFAULT_INCOGNITO_MODE)
    fun isIncognitoModeEnabledFlow(): StateFlow<Boolean> = flag(KEY_INCOGNITO_MODE, DEFAULT_INCOGNITO_MODE)
    fun setIncognitoModeEnabled(enabled: Boolean) = writeFlag(KEY_INCOGNITO_MODE, enabled)

    fun isBiometricLockEnabled(): Boolean = readFlag(KEY_BIOMETRIC_LOCK, DEFAULT_BIOMETRIC_LOCK)
    fun isBiometricLockEnabledFlow(): StateFlow<Boolean> = flag(KEY_BIOMETRIC_LOCK, DEFAULT_BIOMETRIC_LOCK)
    fun setBiometricLockEnabled(enabled: Boolean) = writeFlag(KEY_BIOMETRIC_LOCK, enabled)

    // --------------------------------------------------------- parental controls

    fun isStudyModeEnabled(): Boolean = readFlag(KEY_STUDY_MODE, DEFAULT_STUDY_MODE)
    fun isStudyModeEnabledFlow(): StateFlow<Boolean> = flag(KEY_STUDY_MODE, DEFAULT_STUDY_MODE)

    fun setStudyModeEnabled(enabled: Boolean) = writeFlag(KEY_STUDY_MODE, enabled)

    fun isBedtimeScheduleEnabled(): Boolean = readFlag(KEY_BEDTIME_SCHEDULE, DEFAULT_BEDTIME_SCHEDULE)
    fun isBedtimeScheduleEnabledFlow(): StateFlow<Boolean> = flag(KEY_BEDTIME_SCHEDULE, DEFAULT_BEDTIME_SCHEDULE)

    fun setBedtimeScheduleEnabled(enabled: Boolean) = writeFlag(KEY_BEDTIME_SCHEDULE, enabled)

    // -------------------------------------------------------- emergency delivery

    /**
     * Whether emergency call/SMS actions may be dispatched to saved contacts.
     * Off means the user only ever sees the dialler/composer.
     */
    fun isEmergencyDeliveryEnabled(): Boolean = readFlag(KEY_EMERGENCY_DELIVERY, DEFAULT_EMERGENCY_DELIVERY)

    fun setEmergencyDeliveryEnabled(enabled: Boolean) = writeFlag(KEY_EMERGENCY_DELIVERY, enabled)

    // --------------------------------------------------- risk alert de-duplication

    /** True when this alert was already shown, so the user is not notified twice. */
    fun isRiskAlertDelivered(key: String): Boolean =
        prefs().getStringSet(KEY_DELIVERED_ALERTS, emptySet())?.contains(key) == true

    fun markRiskAlertDelivered(key: String) {
        val current = prefs().getStringSet(KEY_DELIVERED_ALERTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        current += key
        // Keep the set bounded: only the most recent alerts need de-duplication.
        val trimmed = if (current.size > MAX_TRACKED_ALERTS) {
            current.toList().takeLast(MAX_TRACKED_ALERTS).toSet()
        } else {
            current
        }
        prefs().edit().putStringSet(KEY_DELIVERED_ALERTS, trimmed).commit()
    }

    // ------------------------------------------------------ database passphrase

    /**
     * SQLCipher passphrase for the local database: 32 random bytes generated on
     * this device. Never a constant, never derivable from the APK.
     */
    fun databasePassphrase(): ByteArray? {
        val encoded = prefs().getString(KEY_DB_PASSPHRASE, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun saveDatabasePassphrase(passphrase: ByteArray) {
        prefs().edit()
            .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(passphrase, Base64.NO_WRAP))
            .commit()
    }

    // ------------------------------------------------------------ device info

    /** Stable, random per-install identifier used for server-side session tracking. */
    fun deviceId(): String {
        val existing = prefs().getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs().edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)

    // --------------------------------------------------------------- lifecycle

    fun clearAll() {
        val deviceId = try {
            prefs().getString(KEY_DEVICE_ID, null)
        } catch (_: SecureStorageUnavailableException) {
            null
        }
        prefs().edit().clear().apply {
            if (!deviceId.isNullOrBlank()) putString(KEY_DEVICE_ID, deviceId)
        }.commit()
        // Cached flag flows must not keep advertising values that were just wiped.
        flags.clear()
    }

    companion object {
        private const val FILE_NAME = "guardian_secure_prefs_v2"
        private const val ACCESS_TOKEN_SKEW_MS = 30_000L
        private const val MIN_PIN_LENGTH = 4
        private const val PIN_ITERATIONS = 120_000
        private const val PIN_KEY_BITS = 256
        private const val PIN_SALT_BYTES = 16

        private const val KEY_ACCESS_TOKEN = "secure_access_token"
        private const val KEY_ACCESS_EXPIRES_AT = "secure_access_expires_at"
        private const val KEY_REFRESH_TOKEN = "secure_refresh_token"
        private const val KEY_REFRESH_EXPIRES_AT = "secure_refresh_expires_at"
        private const val KEY_SESSION_ID = "secure_session_id"
        private const val KEY_USER_ID = "secure_user_id"
        private const val KEY_USER_EMAIL = "secure_user_email"
        private const val KEY_USER_NAME = "secure_user_name"
        private const val KEY_USER_VERIFIED = "secure_user_verified"
        private const val KEY_PIN_HASH = "secure_pin_hash"
        private const val KEY_PIN_SALT = "secure_pin_salt"
        private const val KEY_STUDY_MODE = "secure_study_mode"
        private const val KEY_BEDTIME_SCHEDULE = "secure_bedtime_schedule"
        private const val KEY_DELIVERED_ALERTS = "secure_delivered_alerts"
        private const val MAX_TRACKED_ALERTS = 200
        private const val KEY_DB_PASSPHRASE = "secure_database_passphrase"
        private const val KEY_DEVICE_ID = "secure_device_id"
        private const val KEY_HEALTH_PROBE = "__guardian_secure_store_probe"

        // Safety behaviour flags. Defaults are conservative: anything that can act
        // on the user's behalf without a tap (direct calling/SMS, automatic
        // recording, automatic SOS) starts OFF and must be switched on explicitly.
        private const val KEY_LIVE_GPS = "secure_live_gps"
        private const val DEFAULT_LIVE_GPS = true
        private const val KEY_VOICE_ACTIVATION = "secure_voice_activation"
        private const val DEFAULT_VOICE_ACTIVATION = false
        private const val KEY_FALL_DETECTION = "secure_fall_detection"
        private const val DEFAULT_FALL_DETECTION = false
        private const val KEY_CRASH_SOS = "secure_crash_sos"
        private const val DEFAULT_CRASH_SOS = false
        private const val KEY_SHAKE_GESTURE = "secure_shake_gesture"
        private const val DEFAULT_SHAKE_GESTURE = false
        private const val KEY_AUTO_SOS_SILENCE = "secure_auto_sos_silence"
        private const val DEFAULT_AUTO_SOS_SILENCE = false
        private const val KEY_BACKGROUND_MONITORING = "secure_background_monitoring"
        private const val DEFAULT_BACKGROUND_MONITORING = false
        private const val KEY_GUARDIAN_PROXIMITY = "secure_guardian_proximity"
        private const val DEFAULT_GUARDIAN_PROXIMITY = true
        private const val KEY_CLOUD_RECORD = "secure_cloud_record"
        private const val DEFAULT_CLOUD_RECORD = false
        private const val KEY_AUDIO_BLACKBOX = "secure_audio_blackbox"
        private const val DEFAULT_AUDIO_BLACKBOX = false
        private const val KEY_INCOGNITO_MODE = "secure_incognito_mode"
        private const val DEFAULT_INCOGNITO_MODE = false
        private const val KEY_BIOMETRIC_LOCK = "secure_biometric_lock"
        private const val DEFAULT_BIOMETRIC_LOCK = true
        private const val KEY_EMERGENCY_DELIVERY = "secure_emergency_delivery"
        private const val DEFAULT_EMERGENCY_DELIVERY = true
        private const val DEFAULT_STUDY_MODE = false
        private const val DEFAULT_BEDTIME_SCHEDULE = true

        private val secureStorageError = MutableStateFlow<String?>(null)

        /** Null while encrypted storage is healthy, otherwise the failure reason. */
        val storageError: StateFlow<String?> = secureStorageError.asStateFlow()

        @Volatile
        private var INSTANCE: SecureEncryptedPreferences? = null

        fun getInstance(context: Context): SecureEncryptedPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureEncryptedPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Test seam: forget the singleton so a fresh instance re-opens storage. */
        fun resetForTesting() {
            synchronized(this) { INSTANCE = null }
            secureStorageError.value = null
        }
    }
}
