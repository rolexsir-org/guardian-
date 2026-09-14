package com.guardian.safety.remote.model

import com.squareup.moshi.JsonClass

/**
 * Wire models for the Guardian Cloudflare Worker.
 *
 * Every field is optional-with-default so a missing or additional server field
 * can never crash deserialisation. These classes are the only place where the
 * HTTP payload shape is declared; UI code works with domain models.
 */

@JsonClass(generateAdapter = true)
data class ApiErrorDto(
    val code: String = "unknown_error",
    val message: String = "",
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val emailVerified: Boolean = false,
    val phone: String? = null,
    val locale: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class SessionDto(
    val sessionId: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val accessExpiresAt: Long = 0L,
    val refreshExpiresAt: Long = 0L,
    val tokenType: String = "Bearer",
)

@JsonClass(generateAdapter = true)
data class AuthPayloadDto(
    val user: UserDto = UserDto(),
    val session: SessionDto = SessionDto(),
)

@JsonClass(generateAdapter = true)
data class MeSessionDto(
    val id: String = "",
)

@JsonClass(generateAdapter = true)
data class MePayloadDto(
    val user: UserDto = UserDto(),
    val session: MeSessionDto? = null,
)

@JsonClass(generateAdapter = true)
data class ContactDto(
    val id: String = "",
    val name: String = "",
    val phone: String = "",
    val relationship: String = "",
    val isVerified: Boolean = false,
    val priority: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class ContactPayloadDto(
    val contact: ContactDto = ContactDto(),
)

@JsonClass(generateAdapter = true)
data class ContactListDto(
    val contacts: List<ContactDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class GroupDto(
    val id: String = "",
    val name: String = "",
    val myRole: String = "MEMBER",
    val ownerUserId: String = "",
    val memberCount: Int = 1,
    val createdAt: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class GroupPayloadDto(
    val group: GroupDto? = null,
)

@JsonClass(generateAdapter = true)
data class GroupListDto(
    val groups: List<GroupDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class MemberDto(
    val memberId: String = "",
    val userId: String = "",
    val role: String = "MEMBER",
    val displayName: String = "",
    val joinedAt: Long = 0L,
    val online: Boolean = false,
    val lastSeenAt: Long? = null,
    val batteryLevel: Int? = null,
)

@JsonClass(generateAdapter = true)
data class MemberListDto(
    val members: List<MemberDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class InviteDto(
    val code: String = "",
    val role: String = "MEMBER",
    val expiresAt: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class InvitePayloadDto(
    val invite: InviteDto = InviteDto(),
)

@JsonClass(generateAdapter = true)
data class FamilyMessageDto(
    val id: String = "",
    val groupId: String = "",
    val senderUserId: String = "",
    val senderName: String = "",
    val body: String = "",
    val isEmergency: Boolean = false,
    val createdAt: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class FamilyMessageListDto(
    val messages: List<FamilyMessageDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class FamilyMessagePayloadDto(
    val message: FamilyMessageDto = FamilyMessageDto(),
)

@JsonClass(generateAdapter = true)
data class LocationShareDto(
    val userId: String = "",
    val displayName: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyM: Double? = null,
    val batteryLevel: Int? = null,
    val source: String = "MANUAL",
    val recordedAt: Long = 0L,
    val expiresAt: Long = 0L,
    val stale: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class LocationShareListDto(
    val locations: List<LocationShareDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class CheckinDto(
    val id: String = "",
    val groupId: String? = null,
    val durationMinutes: Int = 0,
    val status: String = "ACTIVE",
    val note: String = "",
    val startedAt: Long = 0L,
    val dueAt: Long = 0L,
    val completedAt: Long? = null,
)

@JsonClass(generateAdapter = true)
data class CheckinPayloadDto(
    val checkin: CheckinDto = CheckinDto(),
)

@JsonClass(generateAdapter = true)
data class CheckinListDto(
    val checkins: List<CheckinDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SosEventDto(
    val id: String = "",
    val userId: String = "",
    val clientEventId: String = "",
    val triggerSource: String = "BUTTON",
    val status: String = "ACTIVE",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Double? = null,
    val batteryLevel: Int? = null,
    val networkStatus: String? = null,
    val deviceInfo: String? = null,
    val occurredAt: Long = 0L,
    val receivedAt: Long = 0L,
    val acknowledgedAt: Long? = null,
    val resolvedAt: Long? = null,
    val resolutionNote: String? = null,
)

@JsonClass(generateAdapter = true)
data class SosCreatePayloadDto(
    val event: SosEventDto = SosEventDto(),
    val duplicate: Boolean = false,
    val acknowledgedAt: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class SosEventPayloadDto(
    val event: SosEventDto? = null,
)

@JsonClass(generateAdapter = true)
data class SosEventListDto(
    val events: List<SosEventDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SafetyEventDto(
    val id: String = "",
    val kind: String = "HAZARD",
    val title: String = "",
    val category: String = "",
    val severity: String = "INFO",
    val description: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val occurredAt: Long = 0L,
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val status: String = "ACTIVE",
    val confirmations: Int = 0,
    val reportedByUserId: String? = null,
    val distanceMeters: Int? = null,
)

@JsonClass(generateAdapter = true)
data class SafetyEventPayloadDto(
    val event: SafetyEventDto = SafetyEventDto(),
)

@JsonClass(generateAdapter = true)
data class SafetyEventListDto(
    val events: List<SafetyEventDto> = emptyList(),
    val radiusMeters: Int = 0,
)

@JsonClass(generateAdapter = true)
data class EvidenceFileDto(
    val id: String = "",
    val sosEventId: String? = null,
    val contentType: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val fileName: String? = null,
)

@JsonClass(generateAdapter = true)
data class EvidencePayloadDto(
    val file: EvidenceFileDto = EvidenceFileDto(),
)

@JsonClass(generateAdapter = true)
data class EvidenceListDto(
    val files: List<EvidenceFileDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ServerSessionDto(
    val id: String = "",
    val deviceId: String? = null,
    val deviceLabel: String? = null,
    val createdAt: Long = 0L,
    val lastUsedAt: Long = 0L,
    val accessExpiresAt: Long = 0L,
    val refreshExpiresAt: Long = 0L,
    val current: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ServerSessionListDto(
    val sessions: List<ServerSessionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class RevokedSessionsDto(
    val revokedSessions: Int = 0,
)
