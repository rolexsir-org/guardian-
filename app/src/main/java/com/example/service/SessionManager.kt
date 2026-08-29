package com.example.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSession(
    val userId: String,
    val email: String,
    val displayName: String,
    val isEmailVerified: Boolean,
    val token: String,
    val authProvider: String = "Email"
)

class SessionManager(private val context: Context) {
    private val tokenManager = TokenManager(context)
    private val securePrefs = SecureEncryptedPreferences.getInstance(context)

    private val _currentSession = MutableStateFlow<UserSession?>(null)
    val currentSession: StateFlow<UserSession?> = _currentSession.asStateFlow()

    init {
        restoreSession()
    }

    fun restoreSession() {
        val token = tokenManager.getAuthToken()
        if (!token.isNullOrEmpty()) {
            // Restore active session
            _currentSession.value = UserSession(
                userId = "user_restored_123",
                email = "guardian.user@example.com",
                displayName = "Guardian User",
                isEmailVerified = true,
                token = token,
                authProvider = "Email"
            )
        }
    }

    fun createSession(userId: String, email: String, displayName: String, token: String, isVerified: Boolean = true, provider: String = "Email") {
        tokenManager.saveAuthToken(token)
        _currentSession.value = UserSession(
            userId = userId,
            email = email,
            displayName = displayName,
            isEmailVerified = isVerified,
            token = token,
            authProvider = provider
        )
    }

    fun logout() {
        tokenManager.clearToken()
        securePrefs.clearAll()
        _currentSession.value = null
    }

    fun refreshToken(): Boolean {
        val current = _currentSession.value ?: return false
        val newToken = "jwt_refreshed_${System.currentTimeMillis()}"
        tokenManager.saveAuthToken(newToken)
        _currentSession.value = current.copy(token = newToken)
        return true
    }

    fun updateEmailVerification(verified: Boolean) {
        _currentSession.value = _currentSession.value?.copy(isEmailVerified = verified)
    }
}
