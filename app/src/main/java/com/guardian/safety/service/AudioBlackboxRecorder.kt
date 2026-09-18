package com.guardian.safety.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Why recording could not start. The caller must show this, never silence it. */
sealed interface RecordingStart {
    data class Started(val file: File) : RecordingStart
    data object PermissionDenied : RecordingStart
    data class Failed(val message: String) : RecordingStart
}

/**
 * Rolling local audio buffer used as emergency evidence.
 *
 * Behaviour is deliberately simple and verifiable:
 * * Audio is recorded to the app's private files directory in fixed segments. Only
 *   the most recent [maxSegments] are kept, so the buffer is bounded at roughly
 *   `maxSegments * SEGMENT_MS` and cannot fill the device. Guardian Pro raises
 *   that bound; see [applyProEntitlement].
 * * Nothing is uploaded automatically. [latestSegments] hands real files to the
 *   evidence queue and the user is told what was attached.
 * * Failures are reported: no microphone permission, no recorder, or a recorder
 *   error all surface as a [RecordingStart]/state change instead of the UI
 *   implying that audio is being captured.
 * * Recording stops when the feature is switched off, and [release] must be called
 *   when the process is going away so the microphone is not held.
 */
class AudioBlackboxRecorder(private val context: Context) {

    /**
     * How many one-minute segments to keep. Raised while Guardian Pro is active
     * and lowered again when it is not; the value is always driven by the real
     * entitlement, never assumed. Free users still get a full buffer — Pro only
     * makes it longer.
     */
    @Volatile
    var maxSegments: Int = MAX_SEGMENTS
        private set

    /** Applies the retention that matches the current entitlement. */
    fun applyProEntitlement(pro: Boolean) {
        maxSegments = com.guardian.safety.billing.ProFeatures.audioBufferMinutes(pro)
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts a new segment. Returns exactly one outcome; never throws. */
    fun start(): RecordingStart {
        if (_isRecording.value) {
            val existing = currentFile ?: return RecordingStart.Failed("Recorder state is inconsistent; restart the feature.")
            return RecordingStart.Started(existing)
        }
        if (!hasMicrophonePermission()) {
            _lastError.value = "Microphone permission is not granted, so no audio is being recorded."
            return RecordingStart.PermissionDenied
        }
        val directory = bufferDirectory()
            ?: return RecordingStart.Failed("Guardian could not create its private audio directory.").also {
                _lastError.value = it.message
            }

        val target = File(directory, "blackbox-${System.currentTimeMillis()}.m4a")
        val created = try {
            newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                setMaxDuration(SEGMENT_MS)
                setOutputFile(target.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) rotate()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Audio recorder error what=$what extra=$extra")
                    _lastError.value = "Audio recording stopped unexpectedly (error $what)."
                    stop()
                }
                prepare()
                start()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not start audio recording", error)
            runCatching { target.delete() }
            _lastError.value = "Audio recording could not be started (${error.javaClass.simpleName})."
            return RecordingStart.Failed(_lastError.value!!)
        }

        recorder = created
        currentFile = target
        _isRecording.value = true
        _lastError.value = null
        prune(directory)
        return RecordingStart.Started(target)
    }

    /** Stops recording and releases the microphone. */
    fun stop() {
        val active = recorder
        recorder = null
        _isRecording.value = false
        if (active == null) return
        try {
            active.stop()
        } catch (error: Exception) {
            // Nothing was captured: drop the empty file rather than presenting it
            // as evidence.
            Log.w(TAG, "Recorder stopped with no audio captured", error)
            currentFile?.takeIf { it.length() == 0L }?.let { runCatching { it.delete() } }
        } finally {
            runCatching { active.release() }
            currentFile = null
        }
    }

    /** Releases everything. Must be called when the feature is disabled or the app goes away. */
    fun release() {
        stop()
        _lastError.value = null
    }

    /** Ends the current segment and immediately starts the next one. */
    fun rotate() {
        if (!_isRecording.value) return
        stop()
        start()
    }

    /** The buffered segments, oldest first. Only files with real audio are listed. */
    fun latestSegments(limit: Int = maxSegments): List<File> =
        bufferDirectory()
            ?.listFiles { file -> file.isFile && file.name.startsWith("blackbox-") && file.length() > 0L }
            ?.sortedBy { it.name }
            ?.takeLast(limit)
            ?: emptyList()

    private fun bufferDirectory(): File? {
        val directory = File(context.filesDir, BUFFER_DIR)
        return if (directory.exists() || directory.mkdirs()) directory else null
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    /** Keeps only the newest segments so the buffer stays bounded. */
    private fun prune(directory: File) {
        val files = directory.listFiles { file -> file.isFile && file.name.startsWith("blackbox-") }
            ?.sortedBy { it.name }
            ?: return
        if (files.size <= maxSegments) return
        files.take(files.size - maxSegments).forEach { stale ->
            runCatching { stale.delete() }
                .onFailure { Log.w(TAG, "Could not delete the old audio segment ${stale.name}") }
        }
    }

    companion object {
        private const val TAG = "AudioBlackboxRecorder"
        private const val BUFFER_DIR = "evidence/audio-blackbox"

        /** One minute per segment, five segments kept: a five minute rolling buffer. */
        const val SEGMENT_MS = 60_000
        const val MAX_SEGMENTS = 5

        private const val AUDIO_BIT_RATE = 64_000
        private const val AUDIO_SAMPLE_RATE = 16_000
    }
}
