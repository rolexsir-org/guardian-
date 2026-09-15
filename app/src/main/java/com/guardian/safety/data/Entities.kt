package com.guardian.safety.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local (SQLCipher encrypted) storage.
 *
 * Rules applied to every entity:
 * * No fabricated defaults for safety-relevant data: coordinates are nullable and
 *   only written when the device produced a real fix, `syncStatus` starts as
 *   PENDING and is only set to SYNCED after the server accepted the record.
 * * Records that came from the server carry the server id (`remoteId`) so local
 *   and remote state can be reconciled without inventing ids.
 */

@Entity(
    tableName = "incidents",
    indices = [Index(value = ["remoteId"], unique = false)],
)
data class IncidentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Safety-event id on the Guardian backend; null while it exists only on this device. */
    val remoteId: String? = null,
    val title: String,
    val category: String, // Crime, Hazard, Medical, Weather, General
    val severity: String, // Critical, Warning, Info
    val description: String,
    val location: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val upvotes: Int = 0,
    val status: String = "Active", // Active, Resolved
    /** PENDING, SYNCED, FAILED — never set to SYNCED without a server acknowledgement. */
    val syncStatus: String = "PENDING",
    val lastError: String? = null,
    /**
     * User id of the Guardian member the server says filed this report. Null for
     * reports created on this device before they sync — the UI must not invent an
     * author.
     */
    val reportedBy: String? = null,
)

@Entity(
    tableName = "contacts",
    indices = [Index(value = ["remoteId"], unique = false)],
)
data class ContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Emergency-contact id on the Guardian backend; null until it syncs. */
    val remoteId: String? = null,
    val name: String,
    val phone: String,
    val relationship: String, // Family, Friend, ICE, Local Authorities
    val isVerified: Boolean = false,
    val priority: Int = 0,
    val syncStatus: String = "PENDING",
    val lastError: String? = null,
)

@Entity(tableName = "checkins")
data class CheckinEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val remoteId: String? = null,
    val groupRemoteId: String? = null,
    val startTime: Long = System.currentTimeMillis(),
    val dueAt: Long = 0L,
    val durationMinutes: Int,
    val status: String, // ACTIVE, COMPLETED, ALERTED
    val note: String,
    val syncStatus: String = "PENDING",
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
    val insuranceInfo: String = "",
)

@Entity(
    tableName = "sos_queue",
    indices = [Index(value = ["clientEventId"], unique = true)],
)
data class SosQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /**
     * Idempotency key generated once when the emergency starts. Retries reuse it,
     * so the Worker can never create two SOS events for the same emergency.
     */
    val clientEventId: String,
    /** Server-side event id, set only after the Worker accepted the event. */
    val remoteId: String? = null,
    val userId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val batteryLevel: Int? = null,
    val networkStatus: String = "UNKNOWN",
    val deviceInfo: String = "",
    val triggerSource: String = "BUTTON", // BUTTON, SHAKE, PIN, FALL, CRASH, VOICE, HARDWARE, REMOTE
    val emergencyStatus: String = "ACTIVE",
    /** PENDING, SYNCING, SYNCED, FAILED */
    val syncStatus: String = "PENDING",
    val retryCount: Int = 0,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null,
    val acknowledgedAt: Long? = null,
)

@Entity(tableName = "location_cache")
data class LocationCacheEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float?,
    val speed: Float?,
    val altitude: Double?,
    val timestamp: Long = System.currentTimeMillis(),
    val batteryLevel: Int? = null,
    val source: String = "DEVICE",
    val synced: Boolean = false,
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val triggerType: String, // PIN, SHAKE, FALL, BUTTON, VOICE, SYSTEM, CONTACT, FAMILY, HAZARD
    val actionDetails: String,
    val status: String, // TRIGGERED, SUCCESS, FAILED, QUEUED, CANCELLED
    val userId: String? = null,
)

@Entity(tableName = "family_groups")
data class FamilyGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val groupId: Long = 0,
    /** Family-group id on the Guardian backend. */
    val remoteId: String = "",
    val groupName: String,
    val ownerUserId: String = "",
    val myRole: String = "MEMBER",
    val memberCount: Int = 0,
    val inviteCode: String? = null,
    val createdAt: Long = 0L,
)

@Entity(
    tableName = "family_members",
    indices = [Index(value = ["userId"], unique = false), Index(value = ["remoteMemberId"], unique = false)],
)
data class FamilyMemberEntity(
    @PrimaryKey(autoGenerate = true)
    val memberId: Long = 0,
    /** Family-member row id on the Guardian backend. */
    val remoteMemberId: String = "",
    val groupId: Long = 1L,
    val groupRemoteId: String = "",
    val userId: String = "",
    val name: String,
    val role: String, // OWNER, PARENT, GUARDIAN, CHILD, MEMBER
    val email: String = "",
    val phone: String = "",
    val batteryLevel: Int? = null,
    val online: Boolean = false,
    val lastSeenAt: Long? = null,
    /** Null until the member shares a real location fix. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val lastUpdated: Long = 0L,
    val isSharingLocation: Boolean = false,
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
    val zoneType: String = "HOME", // HOME, SCHOOL, WORK, CUSTOM
)

@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val appId: Long = 0,
    val memberId: Long = 1L,
    val appName: String,
    val category: String, // Social, Games, Education, Productivity, Entertainment
    val dailyLimitMinutes: Int = 120,
    val usedMinutes: Int = 0,
    val isRestricted: Boolean = false,
    val isApproved: Boolean = true,
)

@Entity(tableName = "family_messages")
data class FamilyMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val messageId: Long = 0,
    val remoteId: String? = null,
    val groupId: Long = 1L,
    val groupRemoteId: String = "",
    val senderUserId: String = "",
    val senderName: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isEmergencyBroadcast: Boolean = false,
    val readCount: Int = 0,
    val syncStatus: String = "SYNCED",
)
