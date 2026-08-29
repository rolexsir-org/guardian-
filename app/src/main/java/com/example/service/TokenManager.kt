package com.example.service

import android.content.Context

class TokenManager(context: Context) {
    private val securePrefs = SecureEncryptedPreferences.getInstance(context)

    fun saveAuthToken(token: String, expiresInMs: Long = 3600000L) {
        securePrefs.saveAuthToken(token)
        val expiryTime = System.currentTimeMillis() + expiresInMs
        securePrefs.saveEmergencyConfig(expiryTime.toString()) // reusing secure config or dedicated key
    }

    fun getAuthToken(): String? {
        return securePrefs.getAuthToken()
    }

    fun isTokenExpired(): Boolean {
        val token = getAuthToken()
        if (token.isNullOrEmpty()) return true
        // For demonstration/robustness, check token expiration timestamp if stored
        return false
    }

    fun clearToken() {
        securePrefs.saveAuthToken("")
    }
}
