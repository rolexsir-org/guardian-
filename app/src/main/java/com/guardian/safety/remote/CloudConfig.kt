package com.guardian.safety.remote

import com.guardian.safety.BuildConfig

/**
 * Client-side view of the production configuration.
 *
 * The Worker URL is injected at build time and must be an HTTPS origin. When it
 * is missing the app does not invent a development endpoint: every cloud
 * operation fails with a clear `not_configured` error while local safety
 * features (SOS, calls, SMS, offline queue) keep working.
 */
object CloudConfig {

    const val MIN_URL_LENGTH = 12

    val workerUrl: String = BuildConfig.CLOUDFLARE_WORKER_URL.trim().trimEnd('/')

    val configured: Boolean = isUsable(workerUrl)

    /** Human readable reason why the backend is unreachable, or null when valid. */
    val configurationError: String? = when {
        workerUrl.isEmpty() ->
            "Guardian is not connected to a backend on this build: CLOUDFLARE_WORKER_URL is not configured."
        !workerUrl.startsWith("https://") ->
            "CLOUDFLARE_WORKER_URL must use HTTPS. Cleartext endpoints are refused so tokens and location data are never sent unencrypted."
        workerUrl.length < MIN_URL_LENGTH ->
            "CLOUDFLARE_WORKER_URL does not look like a valid Worker origin."
        else -> null
    }

    /** Base URL with a trailing slash, as Retrofit requires. */
    val baseUrl: String = if (workerUrl.endsWith("/")) workerUrl else "$workerUrl/"

    val evidenceMaxBytes: Long = BuildConfig.EVIDENCE_MAX_BYTES.toLong()

    val evidenceMimeTypes: Set<String> = BuildConfig.EVIDENCE_MIME_ALLOWLIST
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun isUsable(url: String): Boolean =
        url.startsWith("https://") && url.length >= MIN_URL_LENGTH

    fun notConfiguredError(): ApiError = ApiError.notConfigured(
        configurationError ?: "Guardian is not connected to a backend on this build.",
    )
}
