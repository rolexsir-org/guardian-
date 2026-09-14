package com.guardian.safety.remote

import com.guardian.safety.BuildConfig
import com.guardian.safety.remote.model.AuthPayloadDto
import com.guardian.safety.remote.model.LoginRequestDto
import com.guardian.safety.remote.model.MePayloadDto
import com.guardian.safety.remote.model.RegisterRequestDto
import com.guardian.safety.remote.model.RevokedSessionsDto
import com.guardian.safety.remote.model.ServerSessionDto
import com.guardian.safety.remote.model.ServerSessionListDto
import com.guardian.safety.service.SecureEncryptedPreferences
import com.squareup.moshi.JsonAdapter

/**
 * Authentication against the Guardian Worker.
 *
 * The server is the only source of identity: tokens, user ids and session ids all
 * come from `/v1/auth/*`. Nothing here fabricates an account, a token or an
 * expiry — a failed call is reported as a failure.
 */
class AuthRepository(
    private val apiClient: ApiClient,
    private val securePrefs: SecureEncryptedPreferences,
) {

    private val authAdapter: JsonAdapter<AuthPayloadDto> = apiClient.adapter(AuthPayloadDto::class.java)
    private val meAdapter: JsonAdapter<MePayloadDto> = apiClient.adapter(MePayloadDto::class.java)
    private val sessionListAdapter: JsonAdapter<ServerSessionListDto> =
        apiClient.adapter(ServerSessionListDto::class.java)
    private val revokedAdapter: JsonAdapter<RevokedSessionsDto> =
        apiClient.adapter(RevokedSessionsDto::class.java)
    private val registerAdapter: JsonAdapter<RegisterRequestDto> =
        apiClient.adapter(RegisterRequestDto::class.java)
    private val loginAdapter: JsonAdapter<LoginRequestDto> = apiClient.adapter(LoginRequestDto::class.java)

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        phone: String? = null,
    ): ApiResult<AuthPayloadDto> {
        val body = RegisterRequestDto(
            email = email.trim().lowercase(),
            password = password,
            displayName = displayName.trim(),
            phone = phone?.trim()?.takeIf { it.isNotEmpty() },
            deviceId = securePrefs.deviceId(),
            deviceLabel = securePrefs.deviceLabel(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        return apiClient.execute(authAdapter) { it.register(apiClient.toJsonBody(registerAdapter, body)) }
    }

    suspend fun login(email: String, password: String): ApiResult<AuthPayloadDto> {
        val body = LoginRequestDto(
            email = email.trim().lowercase(),
            password = password,
            deviceId = securePrefs.deviceId(),
            deviceLabel = securePrefs.deviceLabel(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        return apiClient.execute(authAdapter) { it.login(apiClient.toJsonBody(loginAdapter, body)) }
    }

    /** Rotates the refresh token. Returns false when it cannot be refreshed. */
    suspend fun refresh(): Boolean = apiClient.refreshSession()

    suspend fun me(): ApiResult<MePayloadDto> = apiClient.execute(meAdapter) { it.me() }

    suspend fun logout(): ApiResult<Unit> = apiClient.executeUnit { it.logout() }

    /** Revokes every session for the account (used on "sign out everywhere"). */
    suspend fun logoutAll(): ApiResult<Int> {
        return when (val result = apiClient.execute(revokedAdapter) { it.logoutAll() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.revokedSessions, result.statusCode)
            is ApiResult.Failure -> result
        }
    }

    suspend fun sessions(): ApiResult<List<ServerSessionDto>> {
        return when (val result = apiClient.execute(sessionListAdapter) { it.sessions() }) {
            is ApiResult.Success -> ApiResult.Success(result.data.sessions, result.statusCode)
            is ApiResult.Failure -> result
        }
    }

    suspend fun revokeSession(sessionId: String): ApiResult<Unit> =
        apiClient.executeUnit { it.revokeSession(sessionId) }
}
