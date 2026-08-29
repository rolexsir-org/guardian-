package com.example.data

data class FirebaseHazard(
    val id: String = "",
    val title: String = "",
    val category: String = "Hazard", // Crime, Hazard, Weather, Medical, SOS
    val severity: String = "Warning", // Critical, Warning, Info
    val description: String = "",
    val latitude: Double = 37.7749,
    val longitude: Double = -122.4194,
    val timestamp: Long = System.currentTimeMillis(),
    val upvotes: Int = 1,
    val reportedBy: String = "Community Guardian"
)
