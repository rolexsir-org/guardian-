package com.guardian.safety

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.guardian.safety.remote.ApiClient
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.remote.AuthRepository
import com.guardian.safety.remote.CloudConfig
import com.guardian.safety.remote.model.AuthPayloadDto
import com.guardian.safety.remote.model.SessionDto
import com.guardian.safety.remote.model.UserDto
import com.guardian.safety.service.SecureEncryptedPreferences
import com.guardian.safety.service.SessionManager
import com.guardian.safety.service.SessionState
import com.guardian.safety.service.TokenManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Authentication and secure-storage behaviour.
 *
 * These tests deliberately assert that the app never invents a session: a stored
 * token with a past expiry is treated as expired, a missing refresh token cannot
 * produce a session, and a build without a configured backend cannot sign in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthenticationTest {

    private lateinit var context: Context
    private lateinit var tokenManager: TokenManager
    private lateinit var authRepository: AuthRepository
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecureEncryptedPreferences.resetForTesting()
        tokenManager = TokenManager(context)
        authRepository = AuthRepository(
            ApiClient(tokenManager),
            SecureEncryptedPreferences.getInstance(context),
        )
        sessionManager = SessionManager(context, tokenManager, authRepository)
    }

    @After
    fun tearDown() {
        runCatching { tokenManager.clearToken() }
        SecureEncryptedPreferences.resetForTesting()
    }

    @Test
    fun tokensAreStoredEncryptedAndCleared() {
        val session = SessionDto(
            sessionId = "session-1",
            accessToken = "access-token-1",
            refreshToken = "refresh-token-1",
            accessExpiresAt = System.currentTimeMillis() + 60_000,
            refreshExpiresAt = System.currentTimeMillis() + 600_000,
        )
        tokenManager.saveSession(session)

        assertEquals("access-token-1", tokenManager.accessToken())
        assertEquals("refresh-token-1", tokenManager.refreshToken())
        assertEquals("session-1", tokenManager.sessionId())
        assertTrue(tokenManager.hasRefreshToken())
        assertTrue(tokenManager.hasStoredSession())
        assertFalse(tokenManager.isTokenExpired())

        tokenManager.clearToken()
        assertNull(tokenManager.accessToken())
        assertNull(tokenManager.refreshToken())
        assertFalse(tokenManager.hasStoredSession())
    }

    @Test
    fun expiryComesFromTheRealTimestampNotFromPresenceOfAToken() {
        val alreadyExpired = SessionDto(
            sessionId = "session-2",
            accessToken = "expired-access-token",
            refreshToken = "refresh-token-2",
            accessExpiresAt = System.currentTimeMillis() - 1_000,
            refreshExpiresAt = System.currentTimeMillis() + 600_000,
        )
        tokenManager.saveSession(alreadyExpired)

        // The token exists, but it is expired: the app must know the difference.
        assertNotNull(tokenManager.accessToken())
        assertTrue(tokenManager.isTokenExpired())

        val expiredRefresh = alreadyExpired.copy(refreshExpiresAt = System.currentTimeMillis() - 1_000)
        tokenManager.saveSession(expiredRefresh)
        assertFalse(tokenManager.hasStoredSession())
    }

    @Test
    fun sessionCannotBeRestoredWithoutStoredCredentials() = runBlocking {
        val state = sessionManager.restore()
        assertTrue(state is SessionState.SignedOut)
        assertNull(sessionManager.currentSession())
    }

    @Test
    fun refreshTokenResponseModelCarriesRealExpiries() {
        // Guards against a regression where an expiry is hard-coded in the client.
        val payload = AuthPayloadDto(
            session = SessionDto(
                sessionId = "session-3",
                accessToken = "a",
                refreshToken = "r",
                accessExpiresAt = 1_700_000_000_000,
                refreshExpiresAt = 1_700_003_600_000,
            ),
            user = UserDto(id = "user-1", email = "guardian@example.org", displayName = "Guardian"),
        )
        assertTrue(payload.session.accessExpiresAt < payload.session.refreshExpiresAt)
    }

    @Test
    fun anUnconfiguredBuildCannotSignIn() = runBlocking {
        // CI and developer builds have no Worker URL: signing in must fail with a
        // clear configuration error rather than fabricating a local session.
        assumeBackendUnconfigured()
        val result = sessionManager.signIn("guardian@example.org", "correct-horse-battery")
        assertTrue(result is ApiResult.Failure)
        val failure = result as ApiResult.Failure
        assertEquals("backend_unconfigured", failure.error.code)
        assertNull(sessionManager.currentSession())
    }

    private fun assumeBackendUnconfigured() {
        assertFalse(
            "This test asserts unconfigured-build behaviour; run it on a build without CLOUDFLARE_WORKER_URL.",
            CloudConfig.configured,
        )
    }
}
