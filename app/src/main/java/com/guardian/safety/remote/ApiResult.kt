package com.guardian.safety.remote

import com.guardian.safety.remote.model.ApiErrorDto
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Structured outcome of every remote call.
 *
 * Nothing in the app is allowed to treat a failed request as a success, and no
 * caller may swallow an error: each branch carries a machine readable code plus
 * a message that is safe to display.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T, val statusCode: Int) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

val ApiResult<*>.isSuccess: Boolean get() = this is ApiResult.Success

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.data

fun <T> ApiResult<T>.errorOrNull(): ApiError? = (this as? ApiResult.Failure)?.error

data class ApiError(
    val code: String,
    val message: String,
    val httpStatus: Int? = null,
    /** True when retrying later can realistically succeed (offline, 5xx, timeout). */
    val retryable: Boolean = false,
    /** True when the device had no usable connection. */
    val offline: Boolean = false,
    /** True when the session/credentials are no longer valid. */
    val unauthorized: Boolean = false,
) {
    companion object {
        const val NOT_CONFIGURED = "not_configured"
        const val OFFLINE = "offline"
        const val NETWORK_ERROR = "network_error"
        const val TIMEOUT = "timeout"
        const val MALFORMED_RESPONSE = "malformed_response"
        const val SERVER_ERROR = "server_error"
        const val UNAUTHORIZED = "unauthorized"
        const val SECURE_STORAGE_UNAVAILABLE = "secure_storage_unavailable"
        const val NOT_AUTHENTICATED = "not_authenticated"

        fun notConfigured(detail: String): ApiError = ApiError(
            code = NOT_CONFIGURED,
            message = detail,
            httpStatus = null,
            retryable = false,
        )

        fun offline(): ApiError = ApiError(
            code = OFFLINE,
            message = "No connection. The action was saved on this device and will sync when you are online.",
            retryable = true,
            offline = true,
        )

        fun timeout(): ApiError = ApiError(
            code = TIMEOUT,
            message = "The server took too long to respond. Please try again.",
            retryable = true,
        )

        fun network(detail: String?): ApiError = ApiError(
            code = NETWORK_ERROR,
            message = detail ?: "Could not reach the Guardian service.",
            retryable = true,
        )

        fun malformed(detail: String?): ApiError = ApiError(
            code = MALFORMED_RESPONSE,
            message = detail ?: "The Guardian service returned an unexpected response.",
            retryable = false,
        )

        fun fromDto(status: Int, dto: ApiErrorDto?): ApiError {
            val code = dto?.code?.takeIf { it.isNotBlank() } ?: statusCodeName(status)
            val message = dto?.message?.takeIf { it.isNotBlank() } ?: defaultMessage(status)
            return ApiError(
                code = code,
                message = message,
                httpStatus = status,
                retryable = status >= 500 || status == 429,
                unauthorized = status == 401,
            )
        }

        fun secureStorageUnavailable(detail: String): ApiError = ApiError(
            code = SECURE_STORAGE_UNAVAILABLE,
            message = detail,
            retryable = false,
        )

        fun notAuthenticated(): ApiError = ApiError(
            code = NOT_AUTHENTICATED,
            message = "Sign in to continue.",
            httpStatus = 401,
            unauthorized = true,
        )

        private fun statusCodeName(status: Int): String = when (status) {
            400 -> "invalid_request"
            401 -> UNAUTHORIZED
            403 -> "forbidden"
            404 -> "not_found"
            409 -> "conflict"
            413 -> "payload_too_large"
            429 -> "rate_limited"
            in 500..599 -> SERVER_ERROR
            else -> "http_$status"
        }

        private fun defaultMessage(status: Int): String = when (status) {
            401 -> "Your session has expired. Please sign in again."
            403 -> "You do not have access to this resource."
            404 -> "Not found."
            409 -> "That action conflicts with existing data."
            413 -> "That file is too large."
            429 -> "Too many requests. Please wait a moment."
            in 500..599 -> "The Guardian service is temporarily unavailable."
            else -> "Request failed (HTTP $status)."
        }
    }
}

/** Maps transport-level failures onto structured errors. Never throws. */
internal fun mapTransportFailure(throwable: Throwable): ApiError = when (throwable) {
    is UnknownHostException -> ApiError.offline()
    is SocketTimeoutException -> ApiError.timeout()
    is IOException -> ApiError.network(throwable.message)
    else -> ApiError.malformed(throwable.message)
}

/**
 * Reads a `{ "data": ... }` envelope and converts it with [dataAdapter].
 *
 * The server contract is `{data}` on success and `{error:{code,message}}` on
 * failure; anything else is reported as a malformed response rather than being
 * guessed at. Never throws: all parsing problems become `ApiResult.Failure`.
 */
internal fun <T> parseEnvelope(
    body: String,
    dataAdapter: JsonAdapter<T>,
    errorAdapter: JsonAdapter<ApiErrorDto>,
): ApiResult<T> {
    return try {
        val buffer = Buffer().writeUtf8(body)
        JsonReader.of(buffer).use { reader ->
            if (reader.peek() == JsonReader.Token.END_DOCUMENT) {
                return ApiResult.Failure(ApiError.malformed("Empty response body."))
            }
            if (reader.peek() == JsonReader.Token.NULL) {
                reader.nextNull<Unit>()
                return ApiResult.Failure(ApiError.malformed("Empty response body."))
            }
            reader.beginObject()
            var dataResult: ApiResult<T>? = null
            var failure: ApiResult.Failure? = null
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "data" -> {
                        val decoded = dataAdapter.fromJson(reader)
                        dataResult = if (decoded == null) {
                            ApiResult.Failure(ApiError.malformed("Response data was empty."))
                        } else {
                            ApiResult.Success(decoded, 200)
                        }
                    }
                    "error" -> {
                        val dto = errorAdapter.fromJson(reader)
                        failure = ApiResult.Failure(ApiError.fromDto(200, dto))
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            failure ?: dataResult ?: ApiResult.Failure(ApiError.malformed("Response did not include a data field."))
        }
    } catch (error: JsonDataException) {
        ApiResult.Failure(ApiError.malformed(error.message))
    } catch (error: IOException) {
        ApiResult.Failure(ApiError.malformed(error.message))
    }
}
