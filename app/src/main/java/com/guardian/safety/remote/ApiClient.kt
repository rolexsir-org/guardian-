package com.guardian.safety.remote

import com.guardian.safety.BuildConfig
import com.guardian.safety.remote.model.ApiErrorDto
import com.guardian.safety.remote.model.AuthPayloadDto
import com.guardian.safety.remote.model.RefreshRequestDto
import com.guardian.safety.service.SecureStorageUnavailableException
import com.guardian.safety.service.TokenManager
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonObject
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * HTTPS client for the Guardian Worker.
 *
 * * Refuses to issue any request while [CloudConfig] is not configured — there
 *   is no fallback host and no fabricated endpoint.
 * * Attaches the access token only while it is genuinely unexpired; an expired
 *   token goes through the single-flight refresh path instead of being sent.
 * * Refreshes at most once per call chain, so an emergency request can never
 *   loop or duplicate.
 */
class ApiClient(
    private val tokenManager: TokenManager,
    private val onSessionInvalidated: () -> Unit = {},
) {

    private val moshi: Moshi = Moshi.Builder().build()
    private val errorAdapter: JsonAdapter<ApiErrorDto> = moshi.adapter(ApiErrorDto::class.java)
    private val authAdapter: JsonAdapter<AuthPayloadDto> = moshi.adapter(AuthPayloadDto::class.java)
    private val refreshAdapter: JsonAdapter<RefreshRequestDto> = moshi.adapter(RefreshRequestDto::class.java)

    private val refreshMutex = Mutex()

    private val userAgentInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", "Guardian-Android/${BuildConfig.VERSION_NAME}")
            .build()
        chain.proceed(request)
    }

    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (!requiresAuthentication(request)) {
            chain.proceed(request)
        } else {
            val token = tokenManager.accessToken()
            val attachable = token != null && !tokenManager.isTokenExpired()
            chain.proceed(
                if (attachable) {
                    request.newBuilder().header("Authorization", "Bearer $token").build()
                } else {
                    request
                },
            )
        }
    }

    private val refreshAuthenticator = RefreshAuthenticator()

    private val authenticatedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(userAgentInterceptor)
        .addInterceptor(authInterceptor)
        .authenticator(refreshAuthenticator)
        .build()

    // Used only for the refresh handshake so a failing refresh cannot recurse.
    private val credentialClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(userAgentInterceptor)
        .build()

    val api: GuardianApi = buildApi(authenticatedClient)

    private val credentialApi: GuardianApi = buildApi(credentialClient)

    private fun buildApi(client: OkHttpClient): GuardianApi = Retrofit.Builder()
        // Placeholder origin. Requests are refused by [execute] while the real
        // Worker URL is unconfigured, so this host is never contacted.
        .baseUrl(if (CloudConfig.configured) CloudConfig.baseUrl else UNCONFIGURED_PLACEHOLDER_ORIGIN)
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GuardianApi::class.java)

    private fun requiresAuthentication(request: Request): Boolean {
        val path = request.url.encodedPath
        return !(path.endsWith("/v1/auth/login") ||
            path.endsWith("/v1/auth/register") ||
            path.endsWith("/v1/auth/refresh"))
    }

    // --------------------------------------------------------------- execution

    /**
     * Executes [block] and decodes the `{data}` envelope into [T]. Transport
     * failures become offline/timeout errors; business failures keep the server's
     * own error code. No implicit retries: retry policy belongs to the caller.
     */
    suspend fun <T> execute(
        adapter: JsonAdapter<T>,
        block: suspend (GuardianApi) -> Response<JsonObject>,
    ): ApiResult<T> {
        if (!CloudConfig.configured) {
            return ApiResult.Failure(CloudConfig.notConfiguredError())
        }
        val response = try {
            block(api)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: SecureStorageUnavailableException) {
            return ApiResult.Failure(ApiError.secureStorageUnavailable(error.message ?: SECURE_STORAGE_MESSAGE))
        } catch (error: Exception) {
            return ApiResult.Failure(mapTransportFailure(error))
        }
        return decode(response, adapter)
    }

    /** Executes an endpoint whose success payload the caller does not need. */
    suspend fun executeUnit(
        block: suspend (GuardianApi) -> Response<JsonObject>,
    ): ApiResult<Unit> {
        if (!CloudConfig.configured) {
            return ApiResult.Failure(CloudConfig.notConfiguredError())
        }
        val response = try {
            block(api)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: SecureStorageUnavailableException) {
            return ApiResult.Failure(ApiError.secureStorageUnavailable(error.message ?: SECURE_STORAGE_MESSAGE))
        } catch (error: Exception) {
            return ApiResult.Failure(mapTransportFailure(error))
        }
        if (response.isSuccessful) return ApiResult.Success(Unit, response.code())
        val envelope = readEnvelopeOrNull(response)
        return ApiResult.Failure(ApiError.fromDto(response.code(), envelope))
    }

    private fun <T> decode(response: Response<JsonObject>, adapter: JsonAdapter<T>): ApiResult<T> {
        val raw = response.body()?.toString() ?: ""
        val envelope = try {
            if (raw.isBlank()) Envelope(null, null) else readEnvelope(raw, adapter)
        } catch (error: JsonDataException) {
            return ApiResult.Failure(ApiError.malformed(error.message))
        } catch (error: Exception) {
            return ApiResult.Failure(ApiError.malformed(error.message))
        }

        return if (response.isSuccessful) {
            val data = envelope.data
                ?: return ApiResult.Failure(ApiError.malformed("The server returned an empty payload."))
            ApiResult.Success(data, response.code())
        } else {
            ApiResult.Failure(ApiError.fromDto(response.code(), envelope.error))
        }
    }

    private fun readEnvelopeOrNull(response: Response<JsonObject>): ApiErrorDto? = try {
        val raw = response.body()?.toString() ?: ""
        if (raw.isBlank()) null else readError(raw)
    } catch (_: Exception) {
        null
    }

    private fun readError(raw: String): ApiErrorDto? =
        JsonReader.of(Buffer().writeUtf8(raw)).use { reader ->
            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                var error: ApiErrorDto? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "error") {
                        error = errorAdapter.fromJson(reader)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                error
            } else {
                null
            }
        }

    private class Envelope<T>(val data: T?, val error: ApiErrorDto?)

    private fun <T> readEnvelope(raw: String, adapter: JsonAdapter<T>): Envelope<T> =
        JsonReader.of(Buffer().writeUtf8(raw)).use { reader ->
            var data: T? = null
            var error: ApiErrorDto? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "data" -> data = adapter.fromJson(reader)
                    "error" -> error = errorAdapter.fromJson(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            Envelope(data, error)
        }

    // ----------------------------------------------------------------- refresh

    /** Explicit refresh used by the session layer. Returns false when it cannot. */
    suspend fun refreshSession(): Boolean = refreshTokens()

    private suspend fun refreshTokens(): Boolean = refreshMutex.withLock {
        if (!CloudConfig.configured) return false
        val refreshToken = try {
            tokenManager.refreshToken()
        } catch (error: SecureStorageUnavailableException) {
            onSessionInvalidated()
            return false
        }
        if (refreshToken.isNullOrBlank()) return false

        val response = try {
            credentialApi.refresh(toJsonBody(refreshAdapter, RefreshRequestDto(refreshToken = refreshToken)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Transport failure: keep the stored session, retry later. Only a
            // definitive 401 clears credentials.
            return false
        }

        return when (val result = decode(response, authAdapter)) {
            is ApiResult.Success -> {
                tokenManager.saveSession(result.data.session)
                tokenManager.saveIdentity(result.data.user)
                true
            }
            is ApiResult.Failure -> {
                if (result.error.unauthorized) {
                    tokenManager.clearToken()
                    onSessionInvalidated()
                }
                false
            }
        }
    }

    private inner class RefreshAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (responseCount(response) >= MAX_AUTH_ATTEMPTS) return null
            if (!tokenManager.hasRefreshToken()) {
                onSessionInvalidated()
                return null
            }
            val refreshed = runBlocking { refreshTokens() }
            if (!refreshed) {
                onSessionInvalidated()
                return null
            }
            val newToken = tokenManager.accessToken() ?: return null
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        }

        private fun responseCount(response: Response): Int {
            var count = 1
            var prior: Response? = response.priorResponse
            while (prior != null) {
                count++
                prior = prior.priorResponse
            }
            return count
        }
    }

    // ------------------------------------------------------------------ bodies

    fun <T> toJsonBody(adapter: JsonAdapter<T>, value: T): RequestBody =
        adapter.toJson(value).toRequestBody(JSON_MEDIA_TYPE)

    fun jsonBody(json: String): RequestBody = json.toRequestBody(JSON_MEDIA_TYPE)

    fun emptyJsonBody(): RequestBody = EMPTY_JSON.toRequestBody(JSON_MEDIA_TYPE)

    fun rawBody(bytes: ByteArray, contentType: String): RequestBody =
        bytes.toRequestBody(contentType.toMediaType())

    fun <T> adapter(type: Class<T>): JsonAdapter<T> = moshi.adapter(type)

    fun <T> listAdapter(element: Class<T>): JsonAdapter<List<T>> =
        moshi.adapter(com.squareup.moshi.Types.newParameterizedType(List::class.java, element))

    companion object {
        const val MAX_AUTH_ATTEMPTS = 2
        const val SECURE_STORAGE_MESSAGE = "Encrypted storage is unavailable on this device."
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 120L
        private const val UNCONFIGURED_PLACEHOLDER_ORIGIN = "https://unconfigured.invalid/"
        private const val EMPTY_JSON = "{}"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
