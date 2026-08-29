package com.example.service

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class HardwareAlertManager(private val context: Context) : TextToSpeech.OnInitListener {
    private val TAG = "HardwareAlertManager"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private var cameraId: String? = null
    private var isFlashlightOn = false

    private var toneGenerator: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        try {
            cameraId = cameraManager?.cameraIdList?.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera ID for flashlight", e)
        }
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                Log.i(TAG, "TextToSpeech initialized successfully")
            }
        }
    }

    fun toggleFlashlight(): Boolean {
        return try {
            val id = cameraId ?: return false
            isFlashlightOn = !isFlashlightOn
            cameraManager?.setTorchMode(id, isFlashlightOn)
            Log.i(TAG, "Flashlight toggled: $isFlashlightOn")
            isFlashlightOn
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling flashlight", e)
            false
        }
    }

    fun setFlashlight(enabled: Boolean) {
        try {
            val id = cameraId ?: return
            isFlashlightOn = enabled
            cameraManager?.setTorchMode(id, enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting flashlight state", e)
        }
    }

    fun playSirenSound() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 3000)
            Log.i(TAG, "Siren alarm tone played")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play siren sound", e)
        }
    }

    fun stopSirenSound() {
        try {
            toneGenerator?.stopTone()
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop siren sound", e)
        }
    }

    fun speakSafetyAdvice(text: String) {
        if (isTtsReady && tts != null) {
            try {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "GUARDIAN_TTS_ID")
                Log.i(TAG, "Spoke safety advice via TTS")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to speak text", e)
            }
        }
    }

    fun release() {
        setFlashlight(false)
        stopSirenSound()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        }
    }
}
