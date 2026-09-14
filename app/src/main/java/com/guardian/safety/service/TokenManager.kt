package com.guardian.safety.service

import android.content.Context
import com.guardian.safety.remote.model.SessionDto
import com.guardian.safety.remote.model.UserDto

/**
 * Owns the persisted credential material.
 *
 * Every value written here lands in [SecureEncryptedPreferences]; expiry is
 * stored alongside the token so [isTokenExpired] performs a real timestamp
 * comparison instead of assuming a token must still be valid.
 */
class TokenManager(context: Context) {

    private val securePrefs = SecureEncryptedPreferences.getInstance(context)

    fun saveSession(session: SessionDto) {
        securePrefs.saveSession(
            accessToken = session.accessToken,
            accessExpiresAt = session.accessExpiresAt,
            refreshToken = session.refreshToken,
            refreshExpiresAt = session.refreshExpiresAt,
            sessionId = session.sessionId,
        )
    }

    fun saveIdentity(user: UserDto) {
        securePrefs.saveIdentity(
            StoredIdentity(
                userId = user.id,
                email = user.email,
                displayName = user.displayName,
                emailVerified = user.emailVerified,
            ),
        )
    }

    /** Persists a refreshed access token without touching the refresh token. */
    fun saveAccessToken(accessToken: String, expiresAt: Long) {
        securePrefs.saveAccessToken(accessToken, expiresAt)
    }

    fun accessToken(): String? = securePrefs.accessToken()

    fun accessTokenExpiresAt(): Long = securePrefs.accessTokenExpiresAt()

    /** True when there is no usable, unexpired access token. */
    fun isTokenExpired(): Boolean = securePrefs.isAccessTokenExpired()

    fun refreshToken(): String? = securePrefs.refreshToken()

    fun hasRefreshToken(): Boolean = !securePrefs.isRefreshTokenExpired()

    fun sessionId(): String? = securePrefs.sessionId()

    fun identity(): StoredIdentity? = securePrefs.identity()

    fun hasStoredSession(): Boolean = securePrefs.identity() != null && securePrefs.refreshToken() != null

    fun clearToken() {
        securePrefs.clearSession()
    }

    fun isSecureStorageAvailable(): Boolean = securePrefs.isAvailable()
}
