package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.SessionManager
import com.example.service.TokenManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthenticationTest {

    private lateinit var context: Context
    private lateinit var tokenManager: TokenManager
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tokenManager = TokenManager(context)
        sessionManager = SessionManager(context)
    }

    @Test
    fun testTokenManagement() {
        val testToken = "jwt_test_token_98765"
        tokenManager.saveAuthToken(testToken)
        assertEquals(testToken, tokenManager.getAuthToken())
        assertFalse(tokenManager.isTokenExpired())

        tokenManager.clearToken()
        assertTrue(tokenManager.getAuthToken().isNullOrEmpty())
    }

    @Test
    fun testSessionLifecycle() {
        sessionManager.createSession(
            userId = "usr_1",
            email = "test@guardian.app",
            displayName = "Test Guardian",
            token = "token_abc",
            isVerified = true,
            provider = "Email"
        )

        assertNotNull(sessionManager.currentSession.value)
        assertEquals("test@guardian.app", sessionManager.currentSession.value?.email)
        assertTrue(sessionManager.currentSession.value?.isEmailVerified == true)

        val successRefresh = sessionManager.refreshToken()
        assertTrue(successRefresh)
        assertNotNull(sessionManager.currentSession.value?.token)

        sessionManager.logout()
        assertNull(sessionManager.currentSession.value)
    }
}
