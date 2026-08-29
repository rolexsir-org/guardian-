package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val category: String, // Crime, Hazard, Medical, Weather, General
    val severity: String, // Critical, Warning, Info
    val description: String,
    val location: String,
    val timestamp: Long = System.currentTimeMillis(),
    val upvotes: Int = 1,
    val status: String = "Active" // Active, Resolved
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String,
    val relationship: String, // Family, Friend, ICE, Local Authorities
    val isVerified: Boolean = true
)

@Entity(tableName = "checkins")
data class CheckinEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val durationMinutes: Int,
    val status: String, // Active, Completed, Alerted
    val note: String
)

@Entity(tableName = "medical_profile")
data class MedicalProfileEntity(
    @PrimaryKey
    val id: Long = 1L,
    val name: String = "",
    val bloodGroup: String = "",
    val allergies: String = "",
    val medicalConditions: String = "",
    val medications: String = "",
    val emergencyNotes: String = "",
    val doctorContact: String = "",
    val insuranceInfo: String = ""
)

@Entity(tableName = "sos_queue")
data class SosQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userId: Long = 1L,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val batteryLevel: Int = 100,
    val networkStatus: String = "UNKNOWN",
    val deviceInfo: String = "",
    val triggerSource: String = "BUTTON", // BUTTON, SHAKE, PIN, FALL
    val emergencyStatus: String = "ACTIVE",
    val syncStatus: String = "PENDING", // PENDING, SYNCING, SYNCED, FAILED, EXPIRED
    val retryCount: Int = 0
)

@Entity(tableName = "location_cache")
data class LocationCacheEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val altitude: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val batteryLevel: Int,
    val synced: Boolean = false
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val triggerType: String, // PIN, SHAKE, FALL, BUTTON, VOICE
    val actionDetails: String,
    val status: String, // TRIGGERED, SUCCESS, FAILED
    val userId: Long = 1L
)

@Entity(tableName = "family_groups")
data class FamilyGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val groupId: Long = 0,
    val groupName: String,
    val ownerId: String,
    val inviteCode: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "family_members")
data class FamilyMemberEntity(
    @PrimaryKey(autoGenerate = true)
    val memberId: Long = 0,
    val groupId: Long = 1L,
    val name: String,
    val role: String, // OWNER, PARENT, GUARDIAN, CHILD, MEMBER
    val email: String,
    val phone: String,
    val batteryLevel: Int = 100,
    val connectionStatus: String = "Online", // Online, Offline, Low Signal
    val latitude: Double = 37.7749,
    val longitude: Double = -122.4194,
    val lastUpdated: Long = System.currentTimeMillis(),
    val isSharingLocation: Boolean = true
)

@Entity(tableName = "safe_zones")
data class SafeZoneEntity(
    @PrimaryKey(autoGenerate = true)
    val zoneId: Long = 0,
    val groupId: Long = 1L,
    val name: String, // Home, School, Work, Custom
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 150f,
    val zoneType: String = "HOME" // HOME, SCHOOL, WORK, CUSTOM
)

@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val appId: Long = 0,
    val memberId: Long = 1L,
    val appName: String,
    val category: String, // Social, Games, Education, Productivity, Entertainment
    val dailyLimitMinutes: Int = 120,
    val usedMinutes: Int = 45,
    val isRestricted: Boolean = false,
    val isApproved: Boolean = true
)

@Entity(tableName = "family_messages")
data class FamilyMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val messageId: Long = 0,
    val groupId: Long = 1L,
    val senderId: Long = 1L,
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isEmergencyBroadcast: Boolean = false,
    val readCount: Int = 0
)



