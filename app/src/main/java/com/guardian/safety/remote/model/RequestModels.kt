package com.guardian.safety.remote.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Request payloads accepted by the Guardian Worker. */

@JsonClass(generateAdapter = true)
data class RegisterRequestDto(
    val email: String,
    val password: String,
    val displayName: String,
    val phone: String? = null,
    val locale: String? = null,
    val deviceId: String? = null,
    val deviceLabel: String? = null,
    val appVersion: String? = null,
    val platform: String = "android",
)

@JsonClass(generateAdapter = true)
data class LoginRequestDto(
    val email: String,
    val password: String,
    val deviceId: String? = null,
    val deviceLabel: String? = null,
    val appVersion: String? = null,
    val platform: String = "android",
)

@JsonClass(generateAdapter = true)
data class RefreshRequestDto(
    val refreshToken: String,
    val deviceId: String? = null,
    val deviceLabel: String? = null,
    val appVersion: String? = null,
    val platform: String = "android",
)

@JsonClass(generateAdapter = true)
data class ProfileUpdateDto(
    val displayName: String? = null,
    val locale: String? = null,
)

@JsonClass(generateAdapter = true)
data class PresenceRequestDto(
    val status: String,
    val batteryLevel: Int? = null,
)

@JsonClass(generateAdapter = true)
data class ContactRequestDto(
    val name: String,
    val phone: String,
    val relationship: String,
    val isVerified: Boolean,
    val priority: Int = 0,
)

@JsonClass(generateAdapter = true)
data class ContactUpdateDto(
    val name: String? = null,
    val phone: String? = null,
    val relationship: String? = null,
    val isVerified: Boolean? = null,
    val priority: Int? = null,
)

@JsonClass(generateAdapter = true)
data class GroupCreateDto(val name: String)

@JsonClass(generateAdapter = true)
data class InviteCreateDto(
    val role: String,
    val expiresInMinutes: Int = 60,
)

@JsonClass(generateAdapter = true)
data class JoinGroupDto(val inviteCode: String)

@JsonClass(generateAdapter = true)
data class MemberRoleDto(val role: String)

@JsonClass(generateAdapter = true)
data class MessageRequestDto(
    val body: String,
    val isEmergency: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class LocationShareRequestDto(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double? = null,
    val batteryLevel: Int? = null,
    val source: String,
    val ttlMinutes: Int? = null,
)

@JsonClass(generateAdapter = true)
data class CheckinRequestDto(
    val durationMinutes: Int,
    val note: String? = null,
    val groupId: String? = null,
)

@JsonClass(generateAdapter = true)
data class CheckinStatusDto(val status: String)

@JsonClass(generateAdapter = true)
data class SosCreateRequestDto(
    val clientEventId: String,
    val triggerSource: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val batteryLevel: Int? = null,
    val networkStatus: String? = null,
    val deviceInfo: String? = null,
    val occurredAt: Long,
)

@JsonClass(generateAdapter = true)
data class SosResolveDto(val note: String? = null)

@JsonClass(generateAdapter = true)
data class SafetyEventCreateRequestDto(
    val kind: String,
    val title: String,
    val category: String,
    val severity: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val ttlMinutes: Int,
)

/** Realtime frame sent by the client (see cloudflare/src/realtime/protocol.ts). */
@JsonClass(generateAdapter = true)
data class RealtimeClientFrame(
    val type: String,
    @Json(name = "since") val since: Long? = null,
)
