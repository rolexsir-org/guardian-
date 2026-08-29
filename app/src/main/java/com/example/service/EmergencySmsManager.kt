package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import android.util.Log
import java.util.Date

object EmergencySmsManager {
    fun sendEmergencySms(
        context: Context,
        phoneNumber: String,
        userName: String,
        latitude: Double,
        longitude: Double,
        batteryLevel: Int,
        timestamp: Long
    ) {
        val mapsLink = "https://maps.google.com/?q=$latitude,$longitude"
        val message = "🚨 EMERGENCY SOS 🚨\nUser: $userName needs immediate assistance!\nLocation: $mapsLink\nBattery: $batteryLevel%\nTime: ${Date(timestamp)}"

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val smsManager = context.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
                Log.i("EmergencySmsManager", "Emergency SMS sent directly to $phoneNumber")
                return
            } catch (e: Exception) {
                Log.e("EmergencySmsManager", "Failed direct SMS send, launching default messaging app", e)
            }
        } else {
            Log.w("EmergencySmsManager", "SEND_SMS permission not granted, opening SMS app with preloaded draft")
        }

        // Fallback: Open device default messaging app with pre-populated phone and message
        launchSmsIntent(context, phoneNumber, message)
    }

    fun launchSmsIntent(context: Context, phoneNumber: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${Uri.encode(phoneNumber)}")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i("EmergencySmsManager", "Opened messaging app for $phoneNumber")
        } catch (e: Exception) {
            Log.e("EmergencySmsManager", "Failed to launch SMS intent", e)
        }
    }

    fun shareEmergencyLocationSheet(
        context: Context,
        userName: String,
        latitude: Double,
        longitude: Double,
        batteryLevel: Int
    ) {
        try {
            val mapsLink = "https://maps.google.com/?q=$latitude,$longitude"
            val shareText = "🚨 GUARDIAN EMERGENCY LOCATION SHARE 🚨\nUser: $userName\nLive Coordinates: $latitude, $longitude\nGoogle Maps: $mapsLink\nBattery Level: $batteryLevel%"
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val shareIntent = Intent.createChooser(sendIntent, "Share Emergency Location via...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(shareIntent)
        } catch (e: Exception) {
            Log.e("EmergencySmsManager", "Failed to launch share sheet", e)
        }
    }
}

