package com.example.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import android.util.Log

object EmergencyCallManager {
    fun dialOrCallEmergency(context: Context, phoneNumber: String) {
        try {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val intent = if (hasPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i("EmergencyCallManager", "Initiated emergency call to $phoneNumber (Direct Call: $hasPermission)")
        } catch (e: Exception) {
            Log.e("EmergencyCallManager", "Failed to initiate emergency call", e)
            // Fallback to dialer
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
            } catch (ex: Exception) {
                Log.e("EmergencyCallManager", "Failed fallback dialer", ex)
            }
        }
    }
}
