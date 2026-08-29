package com.example.service

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class UserPresenceService(private val currentUserId: Long = 1L) {
    private var connectedListener: ValueEventListener? = null

    fun startPresenceMonitoring() {
        try {
            val database = FirebaseDatabase.getInstance()
            val connectedRef = database.getReference(".info/connected")
            val statusRef = database.getReference("contact_status").child(currentUserId.toString())

            connectedListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    if (connected) {
                        // When disconnected (app closed or network lost), automatically set status to false (offline)
                        statusRef.onDisconnect().setValue(false)
                        // Set current status to true (online) while connected
                        statusRef.setValue(true)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // Ignore on cancel
                }
            }
            connectedRef.addValueEventListener(connectedListener!!)
        } catch (e: Exception) {
            // Handle offline/initialization failure gracefully
        }
    }

    fun stopPresenceMonitoring() {
        try {
            val database = FirebaseDatabase.getInstance()
            val connectedRef = database.getReference(".info/connected")
            val statusRef = database.getReference("contact_status").child(currentUserId.toString())
            connectedListener?.let {
                connectedRef.removeEventListener(it)
            }
            // Explicitly set offline when service stops normally
            statusRef.setValue(false)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
