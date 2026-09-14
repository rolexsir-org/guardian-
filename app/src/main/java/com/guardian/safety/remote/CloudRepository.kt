package com.guardian.safety.remote

import com.guardian.safety.remote.model.CheckinDto
import com.guardian.safety.remote.model.CheckinListDto
import com.guardian.safety.remote.model.CheckinPayloadDto
import com.guardian.safety.remote.model.CheckinRequestDto
import com.guardian.safety.remote.model.CheckinStatusDto
import com.guardian.safety.remote.model.ContactDto
import com.guardian.safety.remote.model.ContactListDto
import com.guardian.safety.remote.model.ContactPayloadDto
import com.guardian.safety.remote.model.ContactRequestDto
import com.guardian.safety.remote.model.ContactUpdateDto
import com.guardian.safety.remote.model.EvidenceFileDto
import com.guardian.safety.remote.model.EvidenceListDto
import com.guardian.safety.remote.model.EvidencePayloadDto
import com.guardian.safety.remote.model.FamilyMessageDto
import com.guardian.safety.remote.model.FamilyMessageListDto
import com.guardian.safety.remote.model.FamilyMessagePayloadDto
import com.guardian.safety.remote.model.GroupCreateDto
import com.guardian.safety.remote.model.GroupDto
import com.guardian.safety.remote.model.GroupListDto
import com.guardian.safety.remote.model.GroupPayloadDto
import com.guardian.safety.remote.model.InviteCreateDto
import com.guardian.safety.remote.model.InviteDto
import com.guardian.safety.remote.model.InvitePayloadDto
import com.guardian.safety.remote.model.JoinGroupDto
import com.guardian.safety.remote.model.LocationShareDto
import com.guardian.safety.remote.model.LocationShareListDto
import com.guardian.safety.remote.model.LocationShareRequestDto
import com.guardian.safety.remote.model.MemberDto
import com.guardian.safety.remote.model.MemberListDto
import com.guardian.safety.remote.model.MessageRequestDto
import com.guardian.safety.remote.model.MemberRoleDto
import com.guardian.safety.remote.model.PresenceRequestDto
import com.guardian.safety.remote.model.ProfileUpdateDto
import com.guardian.safety.remote.model.SafetyEventCreateRequestDto
import com.guardian.safety.remote.model.SafetyEventDto
import com.guardian.safety.remote.model.SafetyEventListDto
import com.guardian.safety.remote.model.SafetyEventPayloadDto
import com.guardian.safety.remote.model.SosCreateRequestDto
import com.guardian.safety.remote.model.SosCreatePayloadDto
import com.guardian.safety.remote.model.SosEventDto
import com.guardian.safety.remote.model.SosEventListDto
import com.guardian.safety.remote.model.SosEventPayloadDto
import com.guardian.safety.remote.model.SosResolveDto
import com.guardian.safety.remote.model.UserDto
import com.guardian.safety.remote.model.MePayloadDto
import com.squareup.moshi.JsonAdapter

/**
 * Every cloud operation the app performs.
 *
 * Each method returns [ApiResult]; nothing throws and nothing silently degrades
 * to a fake success. Callers decide how to queue, retry or surface the outcome.
 */
class CloudRepository(private val apiClient: ApiClient) {

    // ------------------------------------------------------------------ models

    private val userAdapter: JsonAdapter<UserDto> = apiClient.adapter(UserDto::class.java)
    private val meAdapter: JsonAdapter<MePayloadDto> = apiClient.adapter(MePayloadDto::class.java)
    private val profileUpdateAdapter: JsonAdapter<ProfileUpdateDto> = apiClient.adapter(ProfileUpdateDto::class.java)
    private val presenceAdapter: JsonAdapter<PresenceRequestDto> = apiClient.adapter(PresenceRequestDto::class.java)

    private val contactAdapter: JsonAdapter<ContactDto> = apiClient.adapter(ContactDto::class.java)
    private val contactListAdapter: JsonAdapter<ContactListDto> = apiClient.adapter(ContactListDto::class.java)
    private val contactPayloadAdapter: JsonAdapter<ContactPayloadDto> =
        apiClient.adapter(ContactPayloadDto::class.java)
    private val contactRequestAdapter: JsonAdapter<ContactRequestDto> =
        apiClient.adapter(ContactRequestDto::class.java)
    private val contactUpdateAdapter: JsonAdapter<ContactUpdateDto> =
        apiClient.adapter(ContactUpdateDto::class.java)

    private val groupListAdapter: JsonAdapter<GroupListDto> = apiClient.adapter(GroupListDto::class.java)
    private val groupPayloadAdapter: JsonAdapter<GroupPayloadDto> = apiClient.adapter(GroupPayloadDto::class.java)
    private val groupCreateAdapter: JsonAdapter<GroupCreateDto> = apiClient.adapter(GroupCreateDto::class.java)
    private val inviteAdapter: JsonAdapter<InviteDto> = apiClient.adapter(InviteDto::class.java)
    private val invitePayloadAdapter: JsonAdapter<InvitePayloadDto> = apiClient.adapter(InvitePayloadDto::class.java)
    private val inviteCreateAdapter: JsonAdapter<InviteCreateDto> = apiClient.adapter(InviteCreateDto::class.java)
    private val joinAdapter: JsonAdapter<JoinGroupDto> = apiClient.adapter(JoinGroupDto::class.java)
    private val memberListAdapter: JsonAdapter<MemberListDto> = apiClient.adapter(MemberListDto::class.java)
    private val messageListAdapter: JsonAdapter<FamilyMessageListDto> =
        apiClient.adapter(FamilyMessageListDto::class.java)
    private val messagePayloadAdapter: JsonAdapter<FamilyMessagePayloadDto> =
        apiClient.adapter(FamilyMessagePayloadDto::class.java)
    private val messageRequestAdapter: JsonAdapter<MessageRequestDto> =
        apiClient.adapter(MessageRequestDto::class.java)
    private val locationListAdapter: JsonAdapter<LocationShareListDto> =
        apiClient.adapter(LocationShareListDto::class.java)
    private val locationRequestAdapter: JsonAdapter<LocationShareRequestDto> =
        apiClient.adapter(LocationShareRequestDto::class.java)
    private val checkinListAdapter: JsonAdapter<CheckinListDto> = apiClient.adapter(CheckinListDto::class.java)
    private val checkinPayloadAdapter: JsonAdapter<CheckinPayloadDto> =
        apiClient.adapter(CheckinPayloadDto::class.java)
    private val checkinRequestAdapter: JsonAdapter<CheckinRequestDto> =
        apiClient.adapter(CheckinRequestDto::class.java)
    private val checkinStatusAdapter: JsonAdapter<CheckinStatusDto> =
        apiClient.adapter(CheckinStatusDto::class.java)
    private val memberRoleAdapter: JsonAdapter<MemberRoleDto> = apiClient.adapter(MemberRoleDto::class.java)

    private val sosCreateAdapter: JsonAdapter<SosCreateRequestDto> =
        apiClient.adapter(SosCreateRequestDto::class.java)
    private val sosCreatePayloadAdapter: JsonAdapter<SosCreatePayloadDto> =
        apiClient.adapter(SosCreatePayloadDto::class.java)
    private val sosEventAdapter: JsonAdapter<SosEventDto> = apiClient.adapter(SosEventDto::class.java)
    private val sosEventListAdapter: JsonAdapter<SosEventListDto> = apiClient.adapter(SosEventListDto::class.java)
    private val sosEventPayloadAdapter: JsonAdapter<SosEventPayloadDto> =
        apiClient.adapter(SosEventPayloadDto::class.java)
    private val sosResolveAdapter: JsonAdapter<SosResolveDto> = apiClient.adapter(SosResolveDto::class.java)

    private val safetyEventAdapter: JsonAdapter<SafetyEventDto> = apiClient.adapter(SafetyEventDto::class.java)
    private val safetyEventListAdapter: JsonAdapter<SafetyEventListDto> =
        apiClient.adapter(SafetyEventListDto::class.java)
    private val safetyEventPayloadAdapter: JsonAdapter<SafetyEventPayloadDto> =
        apiClient.adapter(SafetyEventPayloadDto::class.java)
    private val safetyEventCreateAdapter: JsonAdapter<SafetyEventCreateRequestDto> =
        apiClient.adapter(SafetyEventCreateRequestDto::class.java)

    private val evidenceListAdapter: JsonAdapter<EvidenceListDto> = apiClient.adapter(EvidenceListDto::class.java)
    private val evidencePayloadAdapter: JsonAdapter<EvidencePayloadDto> =
        apiClient.adapter(EvidencePayloadDto::class.java)

    // ----------------------------------------------------------------- profile

    suspend fun profile(): ApiResult<UserDto> = apiClient.execute(userAdapter) { it.profile() }
        .map { it.data.user }

    suspend fun updateProfile(displayName: String?, locale: String?): ApiResult<UserDto> {
        val body = ProfileUpdateDto(displayName = displayName, locale = locale)
        return apiClient.execute(meAdapter) { it.updateProfile(apiClient.toJsonBody(profileUpdateAdapter, body)) }
            .map { it.data.user }
    }

    suspend fun sendPresence(status: String, batteryLevel: Int?): ApiResult<Unit> {
        val body = PresenceRequestDto(status = status, batteryLevel = batteryLevel)
        return apiClient.executeUnit { it.recordPresence(apiClient.toJsonBody(presenceAdapter, body)) }
    }

    // ---------------------------------------------------------------- contacts

    suspend fun contacts(): ApiResult<List<ContactDto>> =
        apiClient.execute(contactListAdapter) { it.emergencyContacts() }
            .map { it.data.contacts }

    suspend fun addContact(
        name: String,
        phone: String,
        relationship: String,
        isVerified: Boolean,
        priority: Int = 0,
    ): ApiResult<ContactDto> {
        val body = ContactRequestDto(
            name = name,
            phone = phone,
            relationship = relationship,
            isVerified = isVerified,
            priority = priority,
        )
        return apiClient.execute(contactPayloadAdapter) {
            it.addEmergencyContact(apiClient.toJsonBody(contactRequestAdapter, body))
        }.map { it.data.contact }
    }

    suspend fun updateContact(
        contactId: String,
        name: String?,
        phone: String?,
        relationship: String?,
        isVerified: Boolean?,
        priority: Int?,
    ): ApiResult<ContactDto> {
        val body = ContactUpdateDto(
            name = name,
            phone = phone,
            relationship = relationship,
            isVerified = isVerified,
            priority = priority,
        )
        return apiClient.execute(contactPayloadAdapter) {
            it.updateEmergencyContact(contactId, apiClient.toJsonBody(contactUpdateAdapter, body))
        }.map { it.data.contact }
    }

    suspend fun deleteContact(contactId: String): ApiResult<Unit> =
        apiClient.executeUnit { it.deleteEmergencyContact(contactId) }

    // ------------------------------------------------------------------ family

    suspend fun groups(): ApiResult<List<GroupDto>> =
        apiClient.execute(groupListAdapter) { it.groups() }.map { it.data.groups }

    suspend fun createGroup(name: String): ApiResult<GroupDto> {
        val body = GroupCreateDto(name = name)
        return apiClient.execute(groupPayloadAdapter) { it.createGroup(apiClient.toJsonBody(groupCreateAdapter, body)) }
            .map { it.data.group ?: GroupDto() }
    }

    suspend fun createInvite(groupId: String, role: String, expiresInMinutes: Int = 60): ApiResult<InviteDto> {
        val body = InviteCreateDto(role = role, expiresInMinutes = expiresInMinutes)
        return apiClient.execute(invitePayloadAdapter) {
            it.createInvite(groupId, apiClient.toJsonBody(inviteCreateAdapter, body))
        }.map { it.data.invite }
    }

    suspend fun joinGroup(inviteCode: String): ApiResult<GroupDto> {
        val body = JoinGroupDto(inviteCode = inviteCode.trim().uppercase())
        return apiClient.execute(groupPayloadAdapter) { it.joinGroup(apiClient.toJsonBody(joinAdapter, body)) }
            .map { it.data.group ?: GroupDto() }
    }

    suspend fun members(groupId: String): ApiResult<List<MemberDto>> =
        apiClient.execute(memberListAdapter) { it.members(groupId) }.map { it.data.members }

    suspend fun updateMemberRole(groupId: String, memberId: String, role: String): ApiResult<Unit> {
        val body = MemberRoleDto(role = role)
        return apiClient.executeUnit {
            it.updateMember(groupId, memberId, apiClient.toJsonBody(memberRoleAdapter, body))
        }
    }

    suspend fun removeMember(groupId: String, memberId: String): ApiResult<Unit> =
        apiClient.executeUnit { it.removeMember(groupId, memberId) }

    suspend fun messages(groupId: String, since: Long? = null): ApiResult<List<FamilyMessageDto>> =
        apiClient.execute(messageListAdapter) { it.messages(groupId, since) }.map { it.data.messages }

    suspend fun sendMessage(groupId: String, text: String, isEmergency: Boolean): ApiResult<FamilyMessageDto> {
        val body = MessageRequestDto(body = text, isEmergency = isEmergency)
        return apiClient.execute(messagePayloadAdapter) {
            it.sendMessage(groupId, apiClient.toJsonBody(messageRequestAdapter, body))
        }.map { it.data.message }
    }

    suspend fun groupLocations(groupId: String): ApiResult<List<LocationShareDto>> =
        apiClient.execute(locationListAdapter) { it.groupLocations(groupId) }.map { it.data.locations }

    suspend fun shareLocation(
        groupId: String,
        latitude: Double,
        longitude: Double,
        accuracyM: Double?,
        batteryLevel: Int?,
        source: String,
        ttlMinutes: Int? = null,
    ): ApiResult<Unit> {
        val body = LocationShareRequestDto(
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            batteryLevel = batteryLevel,
            source = source,
            ttlMinutes = ttlMinutes,
        )
        return apiClient.executeUnit {
            it.shareLocation(groupId, apiClient.toJsonBody(locationRequestAdapter, body))
        }
    }

    // ---------------------------------------------------------------- check-ins

    suspend fun checkins(): ApiResult<List<CheckinDto>> =
        apiClient.execute(checkinListAdapter) { it.checkins() }.map { it.data.checkins }

    suspend fun startCheckin(durationMinutes: Int, note: String?, groupId: String?): ApiResult<CheckinDto> {
        val body = CheckinRequestDto(durationMinutes = durationMinutes, note = note, groupId = groupId)
        return apiClient.execute(checkinPayloadAdapter) {
            it.startCheckin(apiClient.toJsonBody(checkinRequestAdapter, body))
        }.map { it.data.checkin }
    }

    suspend fun completeCheckin(checkinId: String, status: String): ApiResult<CheckinDto> {
        val body = CheckinStatusDto(status = status)
        return apiClient.execute(checkinPayloadAdapter) {
            it.completeCheckin(checkinId, apiClient.toJsonBody(checkinStatusAdapter, body))
        }.map { it.data.checkin }
    }

    // --------------------------------------------------------------------- SOS

    /**
     * Creates (or replays) an emergency event. The client event id makes the
     * call idempotent, so a retry after a dropped connection cannot create a
     * second SOS.
     */
    suspend fun createSos(
        clientEventId: String,
        triggerSource: String,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Double?,
        batteryLevel: Int?,
        networkStatus: String?,
        deviceInfo: String?,
        occurredAt: Long,
    ): ApiResult<SosEventDto> {
        val body = SosCreateRequestDto(
            clientEventId = clientEventId,
            triggerSource = triggerSource,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            batteryLevel = batteryLevel,
            networkStatus = networkStatus,
            deviceInfo = deviceInfo,
            occurredAt = occurredAt,
        )
        return apiClient.execute(sosCreatePayloadAdapter) {
            it.createSos(apiClient.toJsonBody(sosCreateAdapter, body))
        }.map { it.data.event }
    }

    suspend fun sosEvents(): ApiResult<List<SosEventDto>> =
        apiClient.execute(sosEventListAdapter) { it.sosHistory() }.map { it.data.events }

    suspend fun sosEvent(sosId: String): ApiResult<SosEventDto?> =
        apiClient.execute(sosEventPayloadAdapter) { it.sosEvent(sosId) }.map { it.data.event }

    suspend fun acknowledgeSos(sosId: String): ApiResult<SosEventDto?> =
        apiClient.execute(sosEventPayloadAdapter) { it.acknowledgeSos(sosId) }.map { it.data.event }

    suspend fun resolveSos(sosId: String, note: String?): ApiResult<SosEventDto?> {
        val body = SosResolveDto(note = note)
        return apiClient.execute(sosEventPayloadAdapter) {
            it.resolveSos(sosId, apiClient.toJsonBody(sosResolveAdapter, body))
        }.map { it.data.event }
    }

    suspend fun activeFamilySos(groupId: String): ApiResult<List<SosEventDto>> =
        apiClient.execute(sosEventListAdapter) { it.activeSos(groupId) }.map { it.data.events }

    // ----------------------------------------------------------- safety events

    suspend fun nearbySafetyEvents(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        kind: String? = null,
    ): ApiResult<List<SafetyEventDto>> =
        apiClient.execute(safetyEventListAdapter) { it.safetyEvents(latitude, longitude, radiusMeters, kind) }
            .map { it.data.events }

    suspend fun reportSafetyEvent(
        kind: String,
        title: String,
        category: String,
        severity: String,
        description: String,
        latitude: Double,
        longitude: Double,
        ttlMinutes: Int,
    ): ApiResult<SafetyEventDto> {
        val body = SafetyEventCreateRequestDto(
            kind = kind,
            title = title,
            category = category,
            severity = severity,
            description = description,
            latitude = latitude,
            longitude = longitude,
            ttlMinutes = ttlMinutes,
        )
        return apiClient.execute(safetyEventPayloadAdapter) {
            it.reportSafetyEvent(apiClient.toJsonBody(safetyEventCreateAdapter, body))
        }.map { it.data.event }
    }

    suspend fun confirmSafetyEvent(eventId: String): ApiResult<Unit> =
        apiClient.executeUnit { it.confirmSafetyEvent(eventId) }

    suspend fun withdrawSafetyEventVote(eventId: String): ApiResult<Unit> =
        apiClient.executeUnit { it.withdrawSafetyEventVote(eventId) }

    // ---------------------------------------------------------------- evidence

    suspend fun evidenceFiles(): ApiResult<List<EvidenceFileDto>> =
        apiClient.execute(evidenceListAdapter) { it.evidenceFiles() }.map { it.data.files }

    /**
     * Uploads evidence bytes to private R2 storage.
     *
     * Size and MIME type are validated locally against the same allowlist the
     * Worker enforces, so an unsupported file fails fast with a clear message
     * instead of being rejected mid-upload.
     */
    suspend fun uploadEvidence(
        bytes: ByteArray,
        contentType: String,
        fileName: String,
        sosEventId: String?,
    ): ApiResult<EvidenceFileDto> {
        val normalizedType = contentType.substringBefore(';').trim().lowercase()
        if (normalizedType !in CloudConfig.evidenceMimeTypes) {
            return ApiResult.Failure(
                ApiError(
                    code = "unsupported_media_type",
                    message = "Only images, audio, PDF and text evidence can be uploaded.",
                ),
            )
        }
        if (bytes.isEmpty()) {
            return ApiResult.Failure(ApiError(code = "empty_file", message = "The selected file is empty."))
        }
        if (bytes.size.toLong() > CloudConfig.evidenceMaxBytes) {
            return ApiResult.Failure(
                ApiError(
                    code = "payload_too_large",
                    message = "Evidence files must be smaller than ${CloudConfig.evidenceMaxBytes / (1024 * 1024)} MB.",
                ),
            )
        }
        return apiClient.execute(evidencePayloadAdapter) {
            it.uploadEvidence(
                body = apiClient.rawBody(bytes, normalizedType),
                contentType = normalizedType,
                fileName = fileName.take(96),
                sosEventId = sosEventId,
            )
        }.map { it.data.file }
    }

    suspend fun deleteEvidence(evidenceId: String): ApiResult<Unit> =
        apiClient.executeUnit { it.deleteEvidence(evidenceId) }

    private inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
        is ApiResult.Success -> ApiResult.Success(transform(data), statusCode)
        is ApiResult.Failure -> this
    }
}
