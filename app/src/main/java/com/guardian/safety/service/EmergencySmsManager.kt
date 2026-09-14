package com.guardian.safety.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Date

/** Per-recipient SMS outcome, kept so the UI can show what really happened. */
data class SmsDelivery(
    val phoneNumber: String,
    val status: SmsStatus,
    val detail: String,
)

enum class SmsStatus { QUEUED, SENT, DELIVERED, FAILED, COMPOSE_OPENED, PERMISSION_REQUIRED }

/**
 * Emergency text messages.
 *
 * * The message body is only ever built from real values: coordinates are included
 *   only when the device actually produced a fix.
 * * When `SEND_SMS` is not granted the app opens the SMS composer with the draft
 *   prefilled and reports [SmsStatus.COMPOSE_OPENED] — it never claims the message
 *   was sent.
 * * Delivery results come back through [SmsDeliveryExtras] pending intents, so a
 *   message rejected by the network is reported as FAILED.
 */
object EmergencySmsManager {

    private const val TAG = "EmergencySmsManager"
    private const val EXTRA_PHONE = "com.guardian.safety.extra.SMS_PHONE"

    /** Delivery results for messages this process sent, keyed by phone number. */
    private val _deliveries = MutableStateFlow<Map<String, SmsDelivery>>(emptyMap())
    val deliveries: StateFlow<Map<String, SmsDelivery>> = _deliveries.asStateFlow()

    private var resultReceiver: BroadcastReceiver? = null

    /**
     * Sends the emergency message to [phoneNumber].
     *
     * @param latitude / [longitude] must be the device's real fix, or null when
     *   location is unavailable — no coordinates are ever invented.
     */
    fun sendEmergencySms(
        context: Context,
        phoneNumber: String,
        userName: String,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Float?,
        batteryLevel: Int?,
        timestamp: Long,
        locationAvailable: Boolean = latitude != null && longitude != null,
    ): SmsDelivery {
        val normalized = phoneNumber.trim()
        if (normalized.isBlank()) {
            return SmsDelivery("", SmsStatus.FAILED, "No phone number available for this contact.")
        }
        val message = buildEmergencyMessage(
            userName = userName,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            batteryLevel = batteryLevel,
            timestamp = timestamp,
            locationAvailable = locationAvailable,
        )

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val opened = openComposer(context, normalized, message)
            return SmsDelivery(
                phoneNumber = normalized,
                status = if (opened) SmsStatus.COMPOSE_OPENED else SmsStatus.FAILED,
                detail = if (opened) {
                    "SMS permission is not granted — the message is open in your messaging app and still needs to be sent."
                } else {
                    "SMS permission is not granted and no messaging app is available."
                },
            )
        }

        return try {
            registerResultReceiver(context)
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: return SmsDelivery(
                    phoneNumber = normalized,
                    status = SmsStatus.FAILED,
                    detail = "This device has no SMS capability.",
                )
            val parts = smsManager.divideMessage(message)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveryIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                sentIntents.add(
                    resultPendingIntent(context, normalized, index, SENT_ACTION, SENT_REQUEST_CODE),
                )
                deliveryIntents.add(
                    resultPendingIntent(context, normalized, index, DELIVERED_ACTION, DELIVERED_REQUEST_CODE),
                )
            }
            smsManager.sendMultipartTextMessage(normalized, null, parts, sentIntents, deliveryIntents)
            val queued = SmsDelivery(
                phoneNumber = normalized,
                status = SmsStatus.QUEUED,
                detail = "Emergency message handed to the network for $normalized.",
            )
            record(queued)
            queued
        } catch (error: SecurityException) {
            Log.w(TAG, "SEND_SMS rejected by the platform", error)
            val opened = openComposer(context, normalized, message)
            SmsDelivery(
                phoneNumber = normalized,
                status = if (opened) SmsStatus.COMPOSE_OPENED else SmsStatus.PERMISSION_REQUIRED,
                detail = "SMS permission was rejected. The message is in your messaging app and still needs sending.",
            )
        } catch (error: Exception) {
            Log.e(TAG, "Emergency SMS failed", error)
            SmsDelivery(
                phoneNumber = normalized,
                status = SmsStatus.FAILED,
                detail = "The emergency message could not be sent (${error.javaClass.simpleName}).",
            )
        }
    }

    fun buildEmergencyMessage(
        userName: String,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Float?,
        batteryLevel: Int?,
        timestamp: Long,
        locationAvailable: Boolean,
    ): String = buildString {
        appendLine("GUARDIAN EMERGENCY SOS")
        appendLine("$userName needs immediate help.")
        if (locationAvailable && latitude != null && longitude != null) {
            appendLine("Location: https://maps.google.com/?q=$latitude,$longitude")
            if (accuracyM != null) appendLine("Accuracy: ±${accuracyM.toInt()} m")
        } else {
            appendLine("Location: unavailable at the time this alert was sent.")
        }
        if (batteryLevel != null) append("Battery: $batteryLevel% — ")
        appendLine("Time: ${Date(timestamp)}")
        append("This is an automated emergency alert from the Guardian app.")
    }

    /** Share sheet used when the user chooses to broadcast their live location. */
    fun shareEmergencyLocationSheet(
        context: Context,
        userName: String,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Float?,
        batteryLevel: Int?,
    ): EmergencyActionResult {
        if (latitude == null || longitude == null) {
            return EmergencyActionResult.Failed(
                "A location fix is not available yet, so there is nothing accurate to share.",
            )
        }
        val body = buildString {
            appendLine("GUARDIAN EMERGENCY LOCATION")
            appendLine("$userName")
            appendLine("Coordinates: $latitude, $longitude")
            appendLine("Map: https://maps.google.com/?q=$latitude,$longitude")
            if (accuracyM != null) appendLine("Accuracy: ±${accuracyM.toInt()} m")
            if (batteryLevel != null) appendLine("Battery: $batteryLevel%")
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(sendIntent, "Share emergency location").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            EmergencyActionResult.Dispatched("Share sheet opened with your current location.")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to launch share sheet", error)
            EmergencyActionResult.Failed("No app is available to share your location.", error.javaClass.simpleName)
        }
    }

    private fun openComposer(context: Context, phoneNumber: String, message: String): Boolean = try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(phoneNumber)}")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (error: Exception) {
        Log.e(TAG, "Failed to open the messaging app", error)
        false
    }

    private fun resultPendingIntent(
        context: Context,
        phoneNumber: String,
        part: Int,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
            .putExtra(EXTRA_PHONE, phoneNumber)
            .putExtra(EXTRA_PART, part)
        return PendingIntent.getBroadcast(
            context,
            requestCode + phoneNumber.hashCode() + part,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun registerResultReceiver(context: Context) {
        if (resultReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val phone = intent.getStringExtra(EXTRA_PHONE) ?: return
                when (intent.action) {
                    SENT_ACTION -> {
                        if (resultCode == android.app.Activity.RESULT_OK) {
                            record(SmsDelivery(phone, SmsStatus.SENT, "Emergency message sent to $phone."))
                        } else {
                            record(
                                SmsDelivery(
                                    phone,
                                    SmsStatus.FAILED,
                                    "The network rejected the emergency message to $phone (code $resultCode).",
                                ),
                            )
                        }
                    }
                    DELIVERED_ACTION -> {
                        if (resultCode == android.app.Activity.RESULT_OK) {
                            record(SmsDelivery(phone, SmsStatus.DELIVERED, "Emergency message delivered to $phone."))
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(SENT_ACTION)
            addAction(DELIVERED_ACTION)
        }
        // Package-scoped receiver: it only ever sees our own pending-intent results.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        resultReceiver = receiver
    }

    private fun record(delivery: SmsDelivery) {
        _deliveries.update { current -> current + (delivery.phoneNumber to delivery) }
    }

    const val SENT_ACTION = "com.guardian.safety.SMS_SENT"
    const val DELIVERED_ACTION = "com.guardian.safety.SMS_DELIVERED"
    private const val SENT_REQUEST_CODE = 21_000
    private const val DELIVERED_REQUEST_CODE = 22_000
    private const val EXTRA_PART = "com.guardian.safety.extra.SMS_PART"
}
