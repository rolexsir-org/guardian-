package com.guardian.safety.service

import android.content.Context
import com.guardian.safety.remote.ApiError
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.remote.AuthRepository
import com.guardian.safety.remote.model.AuthPayloadDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Authenticated Guardian identity, always sourced from the server. */
data class GuardianSession(
    val userId: String,
    val email: String,
    val displayName: String,
    val emailVerified: Boolean,
    val sessionId: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
)

sealed interface SessionState {
    /** Reading encrypted storage and validating stored credentials. */
    data object Restoring : SessionState

    data class SignedOut(
        val message: String? = null,
        /** Set when encrypted storage is unusable; the app must surface this. */
        val storageError: String? = null,
    ) : SessionState

    data class SignedIn(val session: GuardianSession) : SessionState
}

/**
 * Session lifecycle: restore, sign in, sign up, refresh, sign out, revocation.
 *
 * * Restoration never invents an identity — the profile comes from encrypted
 *   storage that was written after a real login, and access tokens are validated
 *   against their real expiry (and refreshed, or the user is signed out).
 * * A revoked or expired session surfaces as [SessionState.SignedOut] with a
 *   message instead of silently continuing with a dead token.
 */
class SessionManager(
    context: Context,
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository,
    private val securePrefs: SecureEncryptedPreferences = SecureEncryptedPreferences.getInstance(context),
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()

    fun currentSession(): GuardianSession? = (_state.value as? SessionState.SignedIn)?.session

    /**
     * Restores a session from encrypted storage. Safe to call repeatedly (for
     * example from the app process and from a background worker).
     */
    suspend fun restore(): SessionState = mutex.withLock {
        if (!runCatching { securePrefs.isAvailable() }.getOrDefault(false)) {
            val storageError = SecureEncryptedPreferences.storageError.value
                ?: "Encrypted storage is unavailable on this device."
            val state = SessionState.SignedOut(
                message = "Guardian cannot store credentials securely on this device, so sign-in is disabled.",
                storageError = storageError,
            )
            _state.value = state
            return state
        }

        val identity = tokenManager.identity()
        if (identity == null || tokenManager.refreshToken() == null) {
            val state = SessionState.SignedOut()
            _state.value = state
            return state
        }

        if (tokenManager.isTokenExpired()) {
            val refreshed = authRepository.refresh()
            if (!refreshed) {
                tokenManager.clearToken()
                val state = SessionState.SignedOut(
                    message = "Your Guardian session expired. Please sign in again.",
                )
                _state.value = state
                return state
            }
        }

        val session = buildSession(identity, tokenManager)
        val state = SessionState.SignedIn(session)
        _state.value = state
        return state
    }

    private fun buildSession(identity: StoredIdentity, tokenManager: TokenManager): GuardianSession =
        GuardianSession(
            userId = identity.userId,
            email = identity.email,
            displayName = identity.displayName,
            emailVerified = identity.emailVerified,
            sessionId = tokenManager.sessionId() ?: "",
            accessExpiresAt = tokenManager.accessTokenExpiresAt(),
            refreshExpiresAt = securePrefs.refreshTokenExpiresAt(),
        )

    suspend fun signIn(email: String, password: String): ApiResult<GuardianSession> = mutex.withLock {
        when (val result = authRepository.login(email, password)) {
            is ApiResult.Success -> persistAndReturn(result.data)
            is ApiResult.Failure -> {
                _state.value = SessionState.SignedOut(message = result.error.message)
                result
            }
        }
    }

    suspend fun signUp(
        email: String,
        password: String,
        displayName: String,
        phone: String? = null,
    ): ApiResult<GuardianSession> = mutex.withLock {
        when (val result = authRepository.register(email, password, displayName, phone)) {
            is ApiResult.Success -> persistAndReturn(result.data)
            is ApiResult.Failure -> {
                _state.value = SessionState.SignedOut(message = result.error.message)
                result
            }
        }
    }

    private fun persistAndReturn(payload: AuthPayloadDto): ApiResult<GuardianSession> {
        tokenManager.saveSession(payload.session)
        tokenManager.saveIdentity(payload.user)
        val session = buildSession(
            StoredIdentity(
                userId = payload.user.id,
                email = payload.user.email,
                displayName = payload.user.displayName,
                emailVerified = payload.user.emailVerified,
            ),
            tokenManager,
        )
        _state.value = SessionState.SignedIn(session)
        return ApiResult.Success(session, 200)
    }

    /**
     * Confirms the stored session against the server. Used on start-up so a
     * session revoked on another device is detected immediately.
     */
    suspend fun verifyWithServer(): ApiResult<GuardianSession> {
        val session = currentSession() ?: return ApiResult.Failure(ApiError.notAuthenticated())
        return when (val result = authRepository.me()) {
            is ApiResult.Success -> {
                tokenManager.saveIdentity(result.data.user)
                val verified = buildSession(
                    StoredIdentity(
                        userId = result.data.user.id,
                        email = result.data.user.email,
                        displayName = result.data.user.displayName,
                        emailVerified = result.data.user.emailVerified,
                    ),
                    tokenManager,
                )
                _state.value = SessionState.SignedIn(verified)
                ApiResult.Success(verified, result.statusCode)
            }
            is ApiResult.Failure -> {
                if (result.error.offline) {
                    // Offline is not a revocation: keep the local session.
                    ApiResult.Success(session, 200)
                } else {
                    if (result.error.unauthorized) onSessionInvalidated(result.error.message)
                    result
                }
            }
        }
    }

    /** Local sign-out. Server revocation failures are reported, never hidden. */
    suspend fun signOut(everyDevice: Boolean = false): ApiResult<Unit> = mutex.withLock {
        val result: ApiResult<Unit> = if (everyDevice) {
            when (val revoked = authRepository.logoutAll()) {
                is ApiResult.Success -> ApiResult.Success(Unit, revoked.statusCode)
                is ApiResult.Failure -> revoked
            }
        } else {
            authRepository.logout()
        }
        tokenManager.clearToken()
        _state.value = SessionState.SignedOut()
        result
    }

    /** Called by the API client when the server rejects the stored credentials. */
    fun onSessionInvalidated(reason: String?) {
        tokenManager.clearToken()
        _state.value = SessionState.SignedOut(
            message = reason ?: "Your Guardian session is no longer valid. Please sign in again.",
        )
    }
}
