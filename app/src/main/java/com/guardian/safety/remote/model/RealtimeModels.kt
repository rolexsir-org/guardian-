package com.guardian.safety.remote.model

import com.squareup.moshi.JsonClass

/**
 * Frames received from the SafetyHub Durable Object
 * (see cloudflare/src/realtime/protocol.ts).
 */

@JsonClass(generateAdapter = true)
data class RealtimePresenceDto(
    val userId: String = "",
    val status: String = "OFFLINE",
    val lastSeenAt: Long = 0L,
    val batteryLevel: Int? = null,
)

@JsonClass(generateAdapter = true)
data class PresenceEventDataDto(
    val userId: String = "",
    val status: String = "OFFLINE",
    val batteryLevel: Int? = null,
    val lastSeenAt: Long? = null,
    val joinedGroup: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class RealtimeEventDto(
    val kind: String = "",
    val at: Long = 0L,
    val data: com.squareup.moshi.JsonObject? = null,
)

@JsonClass(generateAdapter = true)
data class RealtimeFrameDto(
    val type: String = "",
    val scope: String? = null,
    val seq: Long = 0L,
    val serverTime: Long = 0L,
    val presence: List<RealtimePresenceDto> = emptyList(),
    val activeSos: List<SosEventDto> = emptyList(),
    val event: RealtimeEventDto? = null,
    val code: String? = null,
)
