package com.example.service

import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.util.HapticUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class VoiceEmergencyManager(
    private val context: Context,
    private val onEmergencyDetected: (phrase: String, classification: String, advice: String) -> Unit
) {
    private val TAG = "VoiceEmergencyManager"
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.Main)

    private val triggerPhrases = listOf(
        "help me",
        "emergency",
        "call for help",
        "i'm in danger",
        "sos",
        "someone is following me",
        "save me"
    )

    fun startListening() {
        if (isListening) return
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "Speech recognition not available on this device")
                return
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        isListening = false
                        // Auto restart listening after short delay for continuous background protection
                        android.os.Handler(context.mainLooper).postDelayed({
                            startListening()
                        }, 3000)
                    }
                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val spokenText = matches[0].lowercase(Locale.getDefault())
                            checkTrigger(spokenText)
                        }
                        // Restart listener
                        startListening()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val spokenText = matches[0].lowercase(Locale.getDefault())
                            checkTrigger(spokenText)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognizer", e)
            isListening = false
        }
    }

    private fun checkTrigger(spokenText: String) {
        val matched = triggerPhrases.any { phrase -> spokenText.contains(phrase) }
        if (matched) {
            HapticUtils.triggerHaptic(context, isHeavy = true)
            scope.launch {
                val classification = classifyEmergency(spokenText)
                val advice = getTacticalAdvice(classification)
                onEmergencyDetected(spokenText, classification, advice)
            }
        }
    }

    private fun getTacticalAdvice(classification: String): String {
        return when (classification) {
            "Medical Emergency" -> "Keep victim calm and still. Ensure open airway. Call 911 immediately."
            "Fire Hazard" -> "Stay low under smoke. Move to nearest emergency exit. Do not use elevators."
            "Road Accident" -> "Turn on hazard lights. Move out of traffic if safe. Call emergency services."
            "Natural Disaster" -> "Take shelter under sturdy cover. Stay away from windows and power lines."
            else -> "Move to a well-lit public area. Stay alert and share your live location with guardians."
        }
    }

    private suspend fun classifyEmergency(text: String): String = withContext(Dispatchers.IO) {
        val lower = text.lowercase()
        when {
            lower.contains("bleed") || lower.contains("hurt") || lower.contains("pain") || lower.contains("medical") || lower.contains("heart") -> "Medical Emergency"
            lower.contains("fire") || lower.contains("smoke") || lower.contains("burn") -> "Fire Hazard"
            lower.contains("crash") || lower.contains("accident") || lower.contains("hit") -> "Road Accident"
            lower.contains("earthquake") || lower.contains("flood") || lower.contains("storm") -> "Natural Disaster"
            else -> "Personal Safety Threat"
        }
    }

    fun stopListening() {
        try {
            isListening = false
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognizer", e)
        }
    }
}
