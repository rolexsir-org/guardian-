package com.guardian.safety.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.guardian.safety.util.HapticUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Hands-free emergency trigger.
 *
 * Uses the platform [SpeechRecognizer] to listen for emergency phrases, entirely on
 * device. Failure handling is explicit:
 * * if recognition is unavailable or the microphone cannot be used, the listener
 *   reports it once and stops — it never spins in a restart loop that drains the
 *   battery while appearing to protect the user;
 * * restarts use a bounded backoff and give up after [MAX_CONSECUTIVE_ERRORS].
 */
class VoiceEmergencyManager(
    private val context: Context,
    private val onEmergencyDetected: (phrase: String, classification: String, advice: String) -> Unit,
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var consecutiveErrors = 0
    private var stopped = false
    private val handler = Handler(context.mainLooper)

    private val restartRunnable = Runnable { if (!stopped) startListening() }

    private val triggerPhrases = listOf(
        "help me",
        "emergency",
        "call for help",
        "i'm in danger",
        "i am in danger",
        "sos",
        "someone is following me",
        "save me",
    )

    /** Starts listening. [onUnavailable] is called when the microphone cannot be used. */
    fun startListening(onUnavailable: (String) -> Unit = {}) {
        if (isListening || stopped) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onUnavailable("Speech recognition is not available on this device.")
            return
        }
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        consecutiveErrors = 0
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        consecutiveErrors++
                        val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && consecutiveErrors > 1
                        if (fatal) {
                            this@VoiceEmergencyManager.stopListening()
                            onUnavailable(describeError(error))
                            return
                        }
                        if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                            this@VoiceEmergencyManager.stopListening()
                            onUnavailable(
                                "Voice detection stopped after repeated errors (${describeError(error)}).",
                            )
                            return
                        }
                        scheduleRestart()
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        consecutiveErrors = 0
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.lowercase(Locale.getDefault())
                            ?.let(::checkTrigger)
                        scheduleRestart()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.lowercase(Locale.getDefault())
                            ?.let(::checkTrigger)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer?.startListening(intent)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start speech recognition", error)
            isListening = false
            onUnavailable("Voice detection could not be started (${error.javaClass.simpleName}).")
        }
    }

    private fun scheduleRestart() {
        if (stopped) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, restartDelayMs())
    }

    private fun restartDelayMs(): Long = (BASE_RESTART_DELAY_MS * consecutiveErrors.coerceAtLeast(1))
        .coerceAtMost(MAX_RESTART_DELAY_MS)

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is not granted."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognised."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "Speech recognition could not reach its service."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
        else -> "Speech recognition error $error."
    }

    private fun checkTrigger(spokenText: String) {
        if (triggerPhrases.none { spokenText.contains(it) }) return
        HapticUtils.triggerHaptic(context, isHeavy = true)
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            val classification = classifyEmergency(spokenText)
            onEmergencyDetected(spokenText, classification, adviceFor(classification))
        }
    }

    /** Human-readable guidance for what was heard. Uses the real local emergency number. */
    private fun adviceFor(classification: String): String {
        val emergencyNumber = EmergencyNumbers.primary(context)
        return when (classification) {
            "Medical emergency" ->
                "Keep the person calm and still, keep their airway clear, and call $emergencyNumber."
            "Fire or smoke" ->
                "Stay low under smoke, leave by the nearest exit, and call $emergencyNumber. Do not use lifts."
            "Road accident" ->
                "Move out of traffic if it is safe, turn on hazard lights, and call $emergencyNumber."
            "Natural disaster" ->
                "Take cover away from windows and power lines, and follow local evacuation instructions."
            else ->
                "Move to a well-lit public place, stay alert, and Guardian is alerting your guardians."
        }
    }

    private suspend fun classifyEmergency(text: String): String = withContext(Dispatchers.IO) {
        val lower = text.lowercase()
        when {
            listOf("bleed", "hurt", "pain", "medical", "heart", "breath").any(lower::contains) ->
                "Medical emergency"
            listOf("fire", "smoke", "burn").any(lower::contains) -> "Fire or smoke"
            listOf("crash", "accident", "hit", "collision").any(lower::contains) -> "Road accident"
            listOf("earthquake", "flood", "storm", "cyclone").any(lower::contains) -> "Natural disaster"
            else -> "Personal safety threat"
        }
    }

    fun stopListening() {
        stopped = true
        handler.removeCallbacks(restartRunnable)
        try {
            isListening = false
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (error: Exception) {
            Log.e(TAG, "Error while stopping speech recognition", error)
        } finally {
            speechRecognizer = null
        }
    }

    private companion object {
        const val TAG = "VoiceEmergencyManager"
        const val MAX_CONSECUTIVE_ERRORS = 5
        const val BASE_RESTART_DELAY_MS = 2_000L
        const val MAX_RESTART_DELAY_MS = 15_000L
    }
}
