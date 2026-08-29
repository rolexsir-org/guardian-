package com.example.service

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log

class SecureEncryptedPreferences(context: Context) {

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "guardian_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("SecureEncryptedPrefs", "Error initializing EncryptedSharedPreferences, falling back to standard SharedPreferences for testing/compatibility", e)
            context.getSharedPreferences("guardian_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    fun saveAuthToken(token: String) {
        sharedPreferences.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getAuthToken(): String? {
        return sharedPreferences.getString(KEY_AUTH_TOKEN, null)
    }

    fun saveUserPin(pin: String) {
        sharedPreferences.edit().putString(KEY_USER_PIN, pin).apply()
    }

    fun getUserPin(): String? {
        return sharedPreferences.getString(KEY_USER_PIN, "1234") // default fallback
    }

    fun saveEmergencyConfig(config: String) {
        sharedPreferences.edit().putString(KEY_EMERGENCY_CONFIG, config).apply()
    }

    fun getEmergencyConfig(): String? {
        return sharedPreferences.getString(KEY_EMERGENCY_CONFIG, null)
    }

    fun setIncidentSubscribed(category: String, subscribed: Boolean) {
        sharedPreferences.edit().putBoolean("sub_$category", subscribed).apply()
    }

    fun isIncidentSubscribed(category: String, defaultVal: Boolean = true): Boolean {
        return sharedPreferences.getBoolean("sub_$category", defaultVal)
    }

    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }

    companion object {
        private const val KEY_AUTH_TOKEN = "secure_auth_token"
        private const val KEY_USER_PIN = "secure_user_pin"
        private const val KEY_EMERGENCY_CONFIG = "secure_emergency_config"

        @Volatile
        private var INSTANCE: SecureEncryptedPreferences? = null

        fun getInstance(context: Context): SecureEncryptedPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureEncryptedPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
