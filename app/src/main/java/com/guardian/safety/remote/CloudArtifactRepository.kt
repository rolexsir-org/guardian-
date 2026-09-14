package com.guardian.safety.remote

import com.guardian.safety.remote.model.EvidenceFileDto
import com.guardian.safety.remote.model.EvidenceListDto
import com.guardian.safety.remote.model.EvidencePayloadDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.squareup.moshi.JsonAdapter

/**
 * Evidence files stored in private R2 buckets through the Worker.
 *
 * Files upload one at a time (a queue, not a thundering herd), failures stay in
 * the queue for a later attempt with their error message preserved, and access is
 * always through short-lived signed URLs minted by the Worker — nothing in the
 * app ever holds R2 credentials, and no bucket is public.
 */
class CloudArtifactRepository(private val apiClient: ApiClient) {

    private val listAdapter: JsonAdapter<EvidenceListDto> = apiClient.adapter(EvidenceListDto::class.java)
    private val payloadAdapter: JsonAdapter<EvidencePayloadDto> = apiClient.adapter(EvidencePayloadDto::class.java)

    private val _pending = MutableStateFlow<List<PendingEvidence>>(emptyList())
    val pending: Flow<List<PendingEvidence>> = _pending.asStateFlow()

    private val _uploads = MutableStateFlow<List<EvidenceFileDto>>(emptyList())
    val files: Flow<List<EvidenceFileDto>> = _uploads.asStateFlow()

    private val mutex = Mutex()

    data class PendingEvidence(
        val localPath: String,
        val contentType: String,
        val fileName: String,
        val sosEventId: String? = null,
        val attempts: Int = 0,
        val lastError: String? = null,
    )

    fun evidenceFiles(): Flow<List<EvidenceFileDto>> = files

    suspend fun refresh(): ApiResult<List<EvidenceFileDto>> = when (
        val result = apiClient.execute(listAdapter) { it.evidenceFiles() }
    ) {
        is ApiResult.Success -> {
            _uploads.value = result.data.files
            ApiResult.Success(result.data.files, result.statusCode)
        }
        is ApiResult.Failure -> result
    }

    /**
     * Uploads one file immediately. Size and MIME type are validated against the
     * backend contract before any bytes leave the device.
     */
    suspend fun uploadEvidence(
        bytes: ByteArray,
        contentType: String,
        fileName: String,
        sosEventId: String?,
    ): ApiResult<EvidenceFileDto> {
        val normalized = contentType.substringBefore(';').trim().lowercase()
        if (normalized !in CloudConfig.evidenceMimeTypes) {
            return ApiResult.Failure(
                ApiError(
                    code = "unsupported_media_type",
                    message = "Only images, audio, PDF and text evidence can be uploaded.",
                ),
            )
        }
        if (bytes.isEmpty()) {
            return ApiResult.Failure(ApiError("empty_file", "The selected file is empty."))
        }
        if (bytes.size.toLong() > CloudConfig.evidenceMaxBytes) {
            return ApiResult.Failure(
                ApiError(
                    code = "payload_too_large",
                    message = "Evidence files must be smaller than ${CloudConfig.evidenceMaxBytes / (1024 * 1024)} MB.",
                ),
            )
        }
        return apiClient.execute(payloadAdapter) {
            it.uploadEvidence(
                body = apiClient.rawBody(bytes, normalized),
                contentType = normalized,
                fileName = fileName.take(96),
                sosEventId = sosEventId,
            )
        }.let { result ->
            when (result) {
                is ApiResult.Success -> {
                    _uploads.update { current -> current.filterNot { it.id == result.data.file.id } + result.data.file }
                    ApiResult.Success(result.data.file, result.statusCode)
                }
                is ApiResult.Failure -> result
            }
        }
    }

    /** Adds a file to the offline queue so it can be uploaded once connected. */
    suspend fun enqueue(localPath: String, contentType: String, fileName: String, sosEventId: String? = null) {
        mutex.withLock {
            if (_pending.value.any { it.localPath == localPath }) return@withLock
            _pending.update {
                it + PendingEvidence(
                    localPath = localPath,
                    contentType = contentType,
                    fileName = fileName,
                    sosEventId = sosEventId,
                )
            }
        }
    }

    /**
     * Drains the queue. Returns the number of uploads that succeeded; entries that
     * fail keep their error and attempt count so the UI can show why.
     */
    suspend fun drainQueue(readBytes: suspend (String) -> ByteArray?): ApiResult<Int> {
        var uploaded = 0
        var lastFailure: ApiResult.Failure? = null

        val queue = _pending.value
        for (item in queue) {
            val bytes = readBytes(item.localPath)
            if (bytes == null) {
                // The local file is gone: keep the entry but record why.
                updateEntry(item) { it.copy(lastError = "The local file is no longer available.") }
                continue
            }
            when (val result = uploadEvidence(bytes, item.contentType, item.fileName, item.sosEventId)) {
                is ApiResult.Success -> {
                    uploaded++
                    _pending.update { current -> current.filterNot { it.localPath == item.localPath } }
                }
                is ApiResult.Failure -> {
                    lastFailure = result
                    updateEntry(item) {
                        it.copy(attempts = it.attempts + 1, lastError = result.error.message)
                    }
                    if (!result.error.retryable) {
                        // Not retryable (unsupported type / too large): drop it from
                        // the retry queue, the error is already recorded.
                        _pending.update { current -> current.filterNot { it.localPath == item.localPath } }
                    }
                }
            }
        }

        return when {
            lastFailure != null && uploaded == 0 -> lastFailure
            else -> ApiResult.Success(uploaded, 200)
        }
    }

    private fun updateEntry(item: PendingEvidence, transform: (PendingEvidence) -> PendingEvidence) {
        _pending.update { current ->
            current.map { existing ->
                if (existing.localPath == item.localPath) transform(existing) else existing
            }
        }
    }

    suspend fun deleteEvidence(evidenceId: String): ApiResult<Unit> =
        when (val result = apiClient.executeUnit { it.deleteEvidence(evidenceId) }) {
            is ApiResult.Success -> {
                _uploads.update { current -> current.filterNot { it.id == evidenceId } }
                result
            }
            is ApiResult.Failure -> result
        }

    /** Signed URL the UI can open; minted by the Worker, never hard-coded. */
    fun downloadUrl(evidence: EvidenceFileDto): String? = evidence.downloadUrl?.takeIf { it.isNotBlank() }
}
