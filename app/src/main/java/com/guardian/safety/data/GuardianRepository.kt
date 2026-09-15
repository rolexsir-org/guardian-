package com.guardian.safety.data

import android.content.Context
import android.location.Location
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.remote.CloudArtifactRepository
import com.guardian.safety.remote.CloudRepository
import com.guardian.safety.service.SecureStorageUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Single source of truth for app data.
 *
 * * Room (SQLCipher) is the offline source of truth; the UI always reads from it.
 * * Remote calls only run while the device is connected, and a successful remote
 *   write is what flips a local row to `SYNCED`. Nothing is marked synced (or
 *   successful) on the strength of a local write alone.
 * * Failures are returned to the caller; they are never swallowed into a
 *   fabricated success.
 */
class GuardianRepository(
    private val context: Context,
    private val guardianDao: GuardianDao,
    private val cloudRepository: CloudRepository,
    private val artifactRepository: CloudArtifactRepository,
) {

    companion object {
        private const val MAX_SYNC_ATTEMPTS = 5
        private const val RETRY_BASE_DELAY_MS = 1_000L
    }

    // ------------------------------------------------------------------ hazards

    fun getAllIncidents(): Flow<List<IncidentEntity>> = guardianDao.getAllIncidents()

    /**
     * Community safety events around a real device fix. Requires a genuine
     * latitude/longitude: the backend rejects the query without coordinates and
     * the app does not invent one.
     */
    suspend fun refreshNearbySafetyEvents(
        location: Location?,
        radiusMeters: Int = DEFAULT_RADIUS_METERS,
    ): ApiResult<Int> = withContext(Dispatchers.IO) {
        if (location == null) {
            return@withContext ApiResult.Failure(
                com.guardian.safety.remote.ApiError(
                    code = "location_unavailable",
                    message = "Turn on location to see safety events reported around you.",
                ),
            )
        }
        when (val result = cloudRepository.nearbySafetyEvents(
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = radiusMeters,
        )) {
            is ApiResult.Success -> {
                result.data.forEach { event ->
                    val existing = guardianDao.getIncidentByRemoteId(event.id)
                    guardianDao.insertIncident(
                        IncidentEntity(
                            id = existing?.id ?: 0L,
                            remoteId = event.id,
                            title = event.title,
                            category = event.category,
                            severity = event.severity,
                            description = event.description,
                            location = formatCoordinates(event.latitude, event.longitude),
                            latitude = event.latitude,
                            longitude = event.longitude,
                            timestamp = event.occurredAt,
                            upvotes = event.confirmations,
                            status = if (event.expiresAt > System.currentTimeMillis()) "Active" else "Resolved",
                            syncStatus = "SYNCED",
                            reportedBy = event.reportedByUserId,
                        ),
                    )
                }
                ApiResult.Success(result.data.size, result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    /** Reports a community safety event at the device's real location. */
    suspend fun reportSafetyEvent(
        title: String,
        category: String,
        severity: String,
        description: String,
        location: Location,
    ): ApiResult<IncidentEntity> = withContext(Dispatchers.IO) {
        when (
            val result = cloudRepository.reportSafetyEvent(
                kind = "HAZARD",
                title = title.take(120),
                category = category.take(40),
                severity = severity.uppercase(),
                description = description.take(2000),
                latitude = location.latitude,
                longitude = location.longitude,
                ttlMinutes = DEFAULT_TTL_MINUTES,
            )
        ) {
            is ApiResult.Success -> {
                val event = result.data
                val id = guardianDao.insertIncident(
                    IncidentEntity(
                        remoteId = event.id,
                        title = event.title,
                        category = event.category,
                        severity = event.severity,
                        description = event.description,
                        location = formatCoordinates(event.latitude, event.longitude),
                        latitude = event.latitude,
                        longitude = event.longitude,
                        timestamp = event.occurredAt,
                        upvotes = event.confirmations,
                        status = "Active",
                        syncStatus = "SYNCED",
                    ),
                )
                ApiResult.Success(
                    IncidentEntity(
                        id = id,
                        remoteId = event.id,
                        title = event.title,
                        category = event.category,
                        severity = event.severity,
                        description = event.description,
                        location = formatCoordinates(event.latitude, event.longitude),
                        latitude = event.latitude,
                        longitude = event.longitude,
                        timestamp = event.occurredAt,
                        upvotes = event.confirmations,
                        status = "Active",
                        syncStatus = "SYNCED",
                    ),
                    result.statusCode,
                )
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun confirmIncident(incidentId: Long): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val incident = guardianDao.getIncident(incidentId)
            ?: return@withContext ApiResult.Failure(
                com.guardian.safety.remote.ApiError("not_found", "That safety event no longer exists."),
            )
        val remoteId = incident.remoteId
            ?: return@withContext ApiResult.Failure(
                com.guardian.safety.remote.ApiError(
                    "not_synced",
                    "This safety event has not reached the server yet, so it cannot be confirmed.",
                ),
            )
        rawSafetyEventVotes(remoteId, confirm = true, incident = incident)
    }

    private suspend fun rawSafetyEventVotes(
        remoteId: String,
        confirm: Boolean,
        incident: IncidentEntity,
    ): ApiResult<Unit> {
        val result = if (confirm) {
            cloudRepository.confirmSafetyEvent(remoteId)
        } else {
            cloudRepository.withdrawSafetyEventVote(remoteId)
        }
        return when (result) {
            is ApiResult.Success -> {
                guardianDao.updateIncident(
                    incident.copy(upvotes = (incident.upvotes + if (confirm) 1 else -1).coerceAtLeast(0)),
                )
                ApiResult.Success(Unit, result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun deleteIncident(incidentId: Long) = withContext(Dispatchers.IO) {
        guardianDao.deleteIncident(incidentId)
    }

    suspend fun getAllIncidentsOnce(): List<IncidentEntity> = withContext(Dispatchers.IO) {
        guardianDao.getActiveIncidents()
    }

    /**
     * Marks a reported safety event as resolved locally.
     *
     * The backend exposes no delete for community events (they expire on their own),
     * so the app records the resolution locally and keeps the server copy until its
     * TTL ends; it never claims to have deleted anything remotely.
     */
    suspend fun resolveSafetyEvent(incident: IncidentEntity): ApiResult<IncidentEntity> =
        withContext(Dispatchers.IO) {
            if (incident.remoteId == null) {
                return@withContext ApiResult.Failure(
                    com.guardian.safety.remote.ApiError(
                        "not_synced",
                        "This safety event never reached the Guardian service, so only the local copy was closed.",
                    ),
                )
            }
            val resolved = incident.copy(status = "Resolved")
            guardianDao.updateIncident(resolved)
            ApiResult.Success(resolved, 200)
        }

    // ----------------------------------------------------------------- contacts

    fun getAllContacts(): Flow<List<ContactEntity>> = guardianDao.getAllContacts()

    /** Pulls emergency contacts from the server into the local cache. */
    suspend fun syncContacts(): ApiResult<Int> = withContext(Dispatchers.IO) {
        when (val result = cloudRepository.contacts()) {
            is ApiResult.Success -> {
                val remoteIds = result.data.map { it.id }.toSet()
                result.data.forEach { contact ->
                    val existing = guardianDao.getContactByRemoteId(contact.id)
                    guardianDao.insertContact(
                        ContactEntity(
                            id = existing?.id ?: 0L,
                            remoteId = contact.id,
                            name = contact.name,
                            phone = contact.phone,
                            relationship = contact.relationship,
                            isVerified = contact.isVerified,
                            priority = contact.priority,
                            syncStatus = "SYNCED",
                        ),
                    )
                }
                guardianDao.getAllContactsOnce()
                    .filter { it.remoteId != null && it.remoteId !in remoteIds && it.syncStatus == "SYNCED" }
                    .forEach { stale -> guardianDao.deleteContact(stale) }
                ApiResult.Success(result.data.size, result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    /**
     * Adds an emergency contact. The local row is written first so the contact is
     * usable offline; it becomes SYNCED only after the server confirms.
     */
    suspend fun addContact(
        name: String,
        phone: String,
        relationship: String,
        isVerified: Boolean,
        priority: Int = 0,
    ): ApiResult<ContactEntity> = withContext(Dispatchers.IO) {
        val localId = guardianDao.insertContact(
            ContactEntity(
                name = name,
                phone = phone,
                relationship = relationship,
                isVerified = isVerified,
                priority = priority,
                syncStatus = "PENDING",
            ),
        )
        when (
            val result = cloudRepository.addContact(
                name = name,
                phone = phone,
                relationship = relationship,
                isVerified = isVerified,
                priority = priority,
            )
        ) {
            is ApiResult.Success -> {
                val contact = result.data
                val entity = ContactEntity(
                    id = localId,
                    remoteId = contact.id,
                    name = contact.name,
                    phone = contact.phone,
                    relationship = contact.relationship,
                    isVerified = contact.isVerified,
                    priority = contact.priority,
                    syncStatus = "SYNCED",
                )
                guardianDao.insertContact(entity)
                ApiResult.Success(entity, result.statusCode)
            }
            is ApiResult.Failure -> {
                val pending = ContactEntity(
                    id = localId,
                    name = name,
                    phone = phone,
                    relationship = relationship,
                    isVerified = isVerified,
                    priority = priority,
                    syncStatus = "FAILED",
                    lastError = result.error.message,
                )
                guardianDao.insertContact(pending)
                result
            }
        }
    }

    suspend fun updateContact(contact: ContactEntity): ApiResult<ContactEntity> = withContext(Dispatchers.IO) {
        val remoteId = contact.remoteId
        if (remoteId == null) {
            guardianDao.insertContact(contact.copy(syncStatus = "PENDING", lastError = null))
            return@withContext ApiResult.Failure(
                com.guardian.safety.remote.ApiError(
                    "not_synced",
                    "This contact is saved on the device and will sync when the server is reachable.",
                ),
            )
        }
        when (
            val result = cloudRepository.updateContact(
                contactId = remoteId,
                name = contact.name,
                phone = contact.phone,
                relationship = contact.relationship,
                isVerified = contact.isVerified,
                priority = contact.priority,
            )
        ) {
            is ApiResult.Success -> {
                val updated = ContactEntity(
                    id = contact.id,
                    remoteId = remoteId,
                    name = contact.name,
                    phone = contact.phone,
                    relationship = contact.relationship,
                    isVerified = contact.isVerified,
                    priority = contact.priority,
                    syncStatus = "SYNCED",
                )
                guardianDao.insertContact(updated)
                ApiResult.Success(updated, result.statusCode)
            }
            is ApiResult.Failure -> {
                guardianDao.insertContact(contact.copy(syncStatus = "FAILED", lastError = result.error.message))
                result
            }
        }
    }

    suspend fun deleteContact(contact: ContactEntity): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val remoteId = contact.remoteId
        if (remoteId == null) {
            guardianDao.deleteContact(contact)
            return@withContext ApiResult.Success(Unit, 200)
        }
        when (val result = cloudRepository.deleteContact(remoteId)) {
            is ApiResult.Success -> {
                guardianDao.deleteContact(contact)
                ApiResult.Success(Unit, result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun getAllContactsOnce(): List<ContactEntity> = withContext(Dispatchers.IO) {
        guardianDao.getAllContactsOnce()
    }

    // ---------------------------------------------------------------- check-ins

    fun getAllCheckins(): Flow<List<CheckinEntity>> = guardianDao.getAllCheckins()

    suspend fun startCheckin(durationMinutes: Int, note: String): ApiResult<CheckinEntity> =
        withContext(Dispatchers.IO) {
            val groupId = guardianDao.getFamilyGroupOnce()?.remoteId
            when (val result = cloudRepository.startCheckin(durationMinutes, note, groupId)) {
                is ApiResult.Success -> {
                    val checkin = result.data
                    val entity = CheckinEntity(
                        remoteId = checkin.id,
                        groupRemoteId = checkin.groupId,
                        startTime = checkin.createdAt,
                        dueAt = checkin.dueAt,
                        durationMinutes = checkin.durationMinutes,
                        status = checkin.status,
                        note = checkin.note ?: note,
                        syncStatus = "SYNCED",
                    )
                    val id = guardianDao.insertCheckin(entity)
                    ApiResult.Success(entity.copy(id = id), result.statusCode)
                }
                is ApiResult.Failure -> {
                    // Saved locally for the user, clearly marked as not synced.
                    val local = CheckinEntity(
                        startTime = System.currentTimeMillis(),
                        dueAt = System.currentTimeMillis() + durationMinutes * 60_000L,
                        durationMinutes = durationMinutes,
                        status = "ACTIVE",
                        note = note,
                        syncStatus = "PENDING",
                    )
                    val id = guardianDao.insertCheckin(local)
                    val failure = result as ApiResult.Failure
                    if (failure.error.offline) {
                        ApiResult.Success(local.copy(id = id), 0)
                    } else {
                        failure
                    }
                }
            }
        }

    suspend fun completeCheckin(checkinId: Long, status: String): ApiResult<CheckinEntity> =
        withContext(Dispatchers.IO) {
            val checkin = guardianDao.getAllCheckins().first().firstOrNull { it.id == checkinId }
                ?: return@withContext ApiResult.Failure(
                    com.guardian.safety.remote.ApiError("not_found", "That check-in is no longer available."),
                )
            val remoteId = checkin.remoteId
            if (remoteId == null) {
                guardianDao.insertCheckin(checkin.copy(status = status, syncStatus = "PENDING"))
                return@withContext ApiResult.Failure(
                    com.guardian.safety.remote.ApiError(
                        "not_synced",
                        "This check-in never reached the server, so it was only updated on this device.",
                    ),
                )
            }
            when (val result = cloudRepository.completeCheckin(remoteId, status)) {
                is ApiResult.Success -> {
                    val updated = checkin.copy(status = result.data.status, syncStatus = "SYNCED")
                    guardianDao.insertCheckin(updated)
                    ApiResult.Success(updated, result.statusCode)
                }
                is ApiResult.Failure -> result
            }
        }

    // ------------------------------------------------------------------ medical

    fun getMedicalProfile(): Flow<MedicalProfileEntity?> = guardianDao.getMedicalProfile()

    suspend fun saveMedicalProfile(profile: MedicalProfileEntity) = withContext(Dispatchers.IO) {
        guardianDao.upsertMedicalProfile(profile)
    }

    // ------------------------------------------------------------------ SOS queue

    fun getAllSosQueue(): Flow<List<SosQueueEntity>> = guardianDao.getAllSosQueue()

    fun getPendingSosCount(): Flow<Int> = guardianDao.pendingSosCount()

    suspend fun getSosByClientEventId(clientEventId: String): SosQueueEntity? = withContext(Dispatchers.IO) {
        guardianDao.getSosByClientEventId(clientEventId)
    }

    /** Marks an emergency resolved on the server. Success is the server's own answer. */
    suspend fun resolveSos(sosId: String, note: String?): ApiResult<Unit> = withContext(Dispatchers.IO) {
        when (val result = cloudRepository.resolveSos(sosId, note)) {
            is ApiResult.Success -> ApiResult.Success(Unit, result.statusCode)
            is ApiResult.Failure -> result
        }
    }

    suspend fun acknowledgeSos(sosId: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        when (val result = cloudRepository.acknowledgeSos(sosId)) {
            is ApiResult.Success -> ApiResult.Success(Unit, result.statusCode)
            is ApiResult.Failure -> result
        }
    }

    suspend fun activeFamilySos(groupRemoteId: String) =
        withContext(Dispatchers.IO) { cloudRepository.activeFamilySos(groupRemoteId) }

    suspend fun getFamilyGroupOnce(): FamilyGroupEntity? = withContext(Dispatchers.IO) {
        guardianDao.getFamilyGroupOnce()
    }

    suspend fun getPendingSosQueue(): List<SosQueueEntity> = withContext(Dispatchers.IO) {
        guardianDao.getPendingSosQueue()
    }

    /**
     * Emergencies that have never been accepted by the server. Excludes rows the
     * user already cancelled so a cancelled alert is never re-sent.
     */
    suspend fun getUnsentSosQueue(): List<SosQueueEntity> = withContext(Dispatchers.IO) {
        guardianDao.getUnsentSosQueue()
    }

    /**
     * Marks an emergency cancelled on this device. The local row leaves the retry
     * queue immediately; resolving the server-side copy is a separate, reported
     * step (see [resolveSos]) because it needs a connection.
     */
    suspend fun markSosCancelled(sos: SosQueueEntity, note: String?) = withContext(Dispatchers.IO) {
        guardianDao.updateSosQueue(
            sos.copy(
                emergencyStatus = CANCELLED_STATUS,
                syncStatus = CANCELLED_STATUS,
                lastError = note,
                lastAttemptAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun insertSosQueue(sos: SosQueueEntity): Long = withContext(Dispatchers.IO) {
        guardianDao.insertSosQueue(sos)
    }

    suspend fun updateSosQueue(sos: SosQueueEntity) = withContext(Dispatchers.IO) {
        guardianDao.updateSosQueue(sos)
    }

    /**
     * Sends one queued emergency event.
     *
     * The local record is created before the network call, the same
     * `clientEventId` is reused on every attempt (server-side idempotency), and
     * the row is only marked `SYNCED` after the Worker accepted the event. Any
     * other outcome stays queued for a later retry.
     */
    suspend fun syncSosEvent(sos: SosQueueEntity): ApiResult<SosQueueEntity> = withContext(Dispatchers.IO) {
        val attempt = sos.copy(
            syncStatus = "SYNCING",
            retryCount = sos.retryCount + 1,
            lastAttemptAt = System.currentTimeMillis(),
        )
        guardianDao.updateSosQueue(attempt)

        when (
            val result = cloudRepository.createSos(
                clientEventId = sos.clientEventId,
                triggerSource = sos.triggerSource,
                latitude = sos.latitude,
                longitude = sos.longitude,
                accuracyM = sos.accuracyM,
                batteryLevel = sos.batteryLevel,
                networkStatus = sos.networkStatus,
                deviceInfo = sos.deviceInfo,
                occurredAt = sos.timestamp,
            )
        ) {
            is ApiResult.Success -> {
                val synced = attempt.copy(
                    remoteId = result.data.id,
                    syncStatus = "SYNCED",
                    emergencyStatus = result.data.status,
                    acknowledgedAt = result.data.acknowledgedAt,
                    lastError = null,
                )
                guardianDao.updateSosQueue(synced)
                ApiResult.Success(synced, result.statusCode)
            }
            is ApiResult.Failure -> {
                val failed = attempt.copy(
                    syncStatus = if (result.error.retryable) "PENDING" else "FAILED",
                    lastError = result.error.message,
                )
                guardianDao.updateSosQueue(failed)
                result
            }
        }
    }

    // ------------------------------------------------------------ location cache

    fun getLocationHistory(): Flow<List<LocationCacheEntity>> = guardianDao.getLocationHistory()

    suspend fun cacheLocation(
        location: Location,
        batteryLevel: Int?,
        source: String = "DEVICE",
    ): Long = withContext(Dispatchers.IO) {
        guardianDao.insertLocationCache(
            LocationCacheEntity(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy.takeIf { location.hasAccuracy() },
                speed = location.speed.takeIf { location.hasSpeed() },
                altitude = location.altitude.takeIf { location.hasAltitude() },
                timestamp = System.currentTimeMillis(),
                batteryLevel = batteryLevel,
                source = source,
            ),
        )
    }

    suspend fun latestLocation(): LocationCacheEntity? = withContext(Dispatchers.IO) {
        guardianDao.getLatestLocation()
    }

    suspend fun getUnsyncedLocations(): List<LocationCacheEntity> = withContext(Dispatchers.IO) {
        guardianDao.getUnsyncedLocations()
    }

    suspend fun markLocationSynced(id: Long) = withContext(Dispatchers.IO) {
        guardianDao.markLocationSynced(id)
    }

    /** True only when a real Worker URL is configured for this build. */
    fun isCloudConfigured(): Boolean = com.guardian.safety.remote.CloudConfig.configured

    /**
     * Applies local location retention and reports how many rows were removed.
     *
     * Synced fixes go after [LOCATION_RETENTION_MS]. Fixes that were never
     * accepted by the server survive for [UNSYNCED_RETENTION_MS] so a device that
     * has been offline for days can still send what it recorded.
     */
    suspend fun purgeOldLocations(): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        guardianDao.purgeOldLocations(
            retentionCutoff = now - LOCATION_RETENTION_MS,
            hardCutoff = now - UNSYNCED_RETENTION_MS,
        )
    }

    // -------------------------------------------------------------- audit trail

    fun getAllAuditLogs(): Flow<List<AuditLogEntity>> = guardianDao.getAllAuditLogs()

    suspend fun logAudit(triggerType: String, actionDetails: String, status: String, userId: String? = null) =
        withContext(Dispatchers.IO) {
            guardianDao.insertAuditLog(
                AuditLogEntity(
                    triggerType = triggerType,
                    actionDetails = actionDetails,
                    status = status,
                    userId = userId,
                ),
            )
        }

    // ------------------------------------------------------------------- family

    fun getFamilyGroup(): Flow<FamilyGroupEntity?> = guardianDao.getFamilyGroup()

    fun getFamilyGroups(): Flow<List<FamilyGroupEntity>> = guardianDao.getFamilyGroups()

    fun getAllFamilyMembers(): Flow<List<FamilyMemberEntity>> = guardianDao.getAllFamilyMembers()

    /** Refreshes every family group plus its members from the backend. */
    suspend fun syncFamily(): ApiResult<Int> = withContext(Dispatchers.IO) {
        when (val groups = cloudRepository.groups()) {
            is ApiResult.Success -> {
                guardianDao.clearFamilyGroups()
                var memberTotal = 0
                groups.data.forEach { group ->
                    guardianDao.insertFamilyGroup(
                        FamilyGroupEntity(
                            remoteId = group.id,
                            groupName = group.name,
                            ownerUserId = group.ownerUserId,
                            myRole = group.myRole,
                            memberCount = group.memberCount,
                            createdAt = group.createdAt,
                        ),
                    )
                    when (val members = cloudRepository.members(group.id)) {
                        is ApiResult.Success -> {
                            guardianDao.deleteMembersForGroup(group.id)
                            members.data.forEach { member ->
                                guardianDao.insertFamilyMember(
                                    FamilyMemberEntity(
                                        remoteMemberId = member.memberId,
                                        groupRemoteId = group.id,
                                        userId = member.userId,
                                        name = member.displayName,
                                        role = member.role,
                                        batteryLevel = member.batteryLevel,
                                        online = member.online,
                                        lastSeenAt = member.lastSeenAt,
                                        lastUpdated = member.lastSeenAt ?: 0L,
                                    ),
                                )
                            }
                            memberTotal += members.data.size
                        }
                        is ApiResult.Failure -> return@withContext members
                    }
                }
                ApiResult.Success(memberTotal, groups.statusCode)
            }
            is ApiResult.Failure -> groups
        }
    }

    suspend fun createFamilyGroup(name: String): ApiResult<FamilyGroupEntity> = withContext(Dispatchers.IO) {
        when (val result = cloudRepository.createGroup(name)) {
            is ApiResult.Success -> {
                val group = result.data
                val entity = FamilyGroupEntity(
                    remoteId = group.id,
                    groupName = group.name,
                    ownerUserId = group.ownerUserId,
                    myRole = group.myRole,
                    memberCount = group.memberCount,
                    createdAt = group.createdAt,
                )
                val id = guardianDao.insertFamilyGroup(entity)
                ApiResult.Success(entity.copy(groupId = id), result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun createInvite(
        groupRemoteId: String,
        role: String,
        expiresInMinutes: Int,
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        when (val result = cloudRepository.createInvite(groupRemoteId, role, expiresInMinutes)) {
            is ApiResult.Success -> ApiResult.Success(result.data.code, result.statusCode)
            is ApiResult.Failure -> result
        }
    }

    suspend fun joinFamilyGroup(inviteCode: String): ApiResult<FamilyGroupEntity> = withContext(Dispatchers.IO) {
        when (val result = cloudRepository.joinGroup(inviteCode)) {
            is ApiResult.Success -> {
                val group = result.data
                val entity = FamilyGroupEntity(
                    remoteId = group.id,
                    groupName = group.name,
                    ownerUserId = group.ownerUserId,
                    myRole = group.myRole,
                    memberCount = group.memberCount,
                    createdAt = group.createdAt,
                )
                val id = guardianDao.insertFamilyGroup(entity)
                ApiResult.Success(entity.copy(groupId = id), result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    suspend fun removeFamilyMember(groupRemoteId: String, memberId: String): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            when (val result = cloudRepository.removeMember(groupRemoteId, memberId)) {
                is ApiResult.Success -> {
                    val member = guardianDao.getMembersForGroup(groupRemoteId)
                        .firstOrNull { it.remoteMemberId == memberId }
                    if (member != null) guardianDao.deleteMembersForGroup(groupRemoteId)
                    result
                }
                is ApiResult.Failure -> result
            }
        }

    suspend fun clearFamilyData() = withContext(Dispatchers.IO) {
        guardianDao.clearFamilyGroups()
        guardianDao.clearFamilyMembers()
        guardianDao.clearFamilyMessages()
    }

    // -------------------------------------------------------- family messaging

    fun getAllFamilyMessages(): Flow<List<FamilyMessageEntity>> = guardianDao.getAllFamilyMessages()

    suspend fun syncFamilyMessages(groupRemoteId: String): ApiResult<Int> = withContext(Dispatchers.IO) {
        val localMessages = guardianDao.getAllFamilyMessages().first()
        val newestRemoteId = localMessages.mapNotNull { it.remoteId }.maxOrNull()
        val since = localMessages.firstOrNull { it.remoteId == newestRemoteId }?.timestamp
        when (val result = cloudRepository.messages(groupRemoteId, since)) {
            is ApiResult.Success -> {
                val group = guardianDao.getFamilyGroupByRemoteId(groupRemoteId)
                result.data.forEach { message ->
                    if (localMessages.none { it.remoteId == message.id }) {
                        guardianDao.insertFamilyMessage(
                            FamilyMessageEntity(
                                remoteId = message.id,
                                groupId = group?.groupId ?: 1L,
                                groupRemoteId = groupRemoteId,
                                senderUserId = message.senderUserId,
                                senderName = message.senderName ?: "",
                                text = message.body,
                                timestamp = message.createdAt,
                                isEmergencyBroadcast = message.isEmergency,
                                syncStatus = "SYNCED",
                            ),
                        )
                    }
                }
                ApiResult.Success(result.data.size, result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    /** Queues a family message locally, then attempts delivery. */
    suspend fun sendFamilyMessage(
        groupRemoteId: String,
        text: String,
        isEmergency: Boolean,
    ): ApiResult<FamilyMessageEntity> = withContext(Dispatchers.IO) {
        val group = guardianDao.getFamilyGroupByRemoteId(groupRemoteId)
        val localId = guardianDao.insertFamilyMessage(
            FamilyMessageEntity(
                groupId = group?.groupId ?: 1L,
                groupRemoteId = groupRemoteId,
                senderName = "You",
                text = text,
                isEmergencyBroadcast = isEmergency,
                syncStatus = "PENDING",
            ),
        )
        when (val result = cloudRepository.sendMessage(groupRemoteId, text, isEmergency)) {
            is ApiResult.Success -> {
                val message = result.data
                val entity = FamilyMessageEntity(
                    messageId = localId,
                    remoteId = message.id,
                    groupId = group?.groupId ?: 1L,
                    groupRemoteId = groupRemoteId,
                    senderUserId = message.senderUserId,
                    senderName = message.senderName ?: "You",
                    text = message.body,
                    timestamp = message.createdAt,
                    isEmergencyBroadcast = message.isEmergency,
                    syncStatus = "SYNCED",
                )
                guardianDao.insertFamilyMessage(entity)
                ApiResult.Success(entity, result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    // --------------------------------------------------------------- safe zones

    fun getAllSafeZones(): Flow<List<SafeZoneEntity>> = guardianDao.getAllSafeZones()

    suspend fun addSafeZone(zone: SafeZoneEntity): Long = withContext(Dispatchers.IO) {
        guardianDao.insertSafeZone(zone)
    }

    suspend fun deleteSafeZone(zone: SafeZoneEntity) = withContext(Dispatchers.IO) {
        guardianDao.deleteSafeZone(zone)
    }

    // ---------------------------------------------------------------- app usage

    fun getAllAppUsage(): Flow<List<AppUsageEntity>> = guardianDao.getAllAppUsage()

    suspend fun updateAppUsage(usage: AppUsageEntity) = withContext(Dispatchers.IO) {
        guardianDao.updateAppUsage(usage)
    }

    // ------------------------------------------------------------------ evidence

    fun getAllEvidence(): Flow<List<com.guardian.safety.remote.model.EvidenceFileDto>> =
        artifactRepository.evidenceFiles()

    suspend fun uploadEvidence(bytes: ByteArray, contentType: String, fileName: String, sosEventId: String?):
        ApiResult<com.guardian.safety.remote.model.EvidenceFileDto> =
        artifactRepository.uploadEvidence(bytes, contentType, fileName, sosEventId)

    suspend fun deleteEvidence(evidenceId: String): ApiResult<Unit> =
        artifactRepository.deleteEvidence(evidenceId)

    // ------------------------------------------------------- presence + location

    suspend fun sendPresence(status: String, batteryLevel: Int?) = withContext(Dispatchers.IO) {
        cloudRepository.sendPresence(status, batteryLevel)
    }

    suspend fun shareLocation(
        groupRemoteId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float?,
        batteryLevel: Int?,
        source: String,
    ): ApiResult<Unit> = withContext(Dispatchers.IO) {
        cloudRepository.shareLocation(
            groupId = groupRemoteId,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracy?.toDouble(),
            batteryLevel = batteryLevel,
            source = source,
        )
    }

    suspend fun groupLocations(groupRemoteId: String) =
        withContext(Dispatchers.IO) { cloudRepository.groupLocations(groupRemoteId) }

    // ------------------------------------------------------------------ helpers

    /** Retry delay for queue processing: linear backoff, capped, never infinite. */
    fun retryDelayMillis(attempt: Int): Long =
        (RETRY_BASE_DELAY_MS * attempt).coerceAtMost(RETRY_BASE_DELAY_MS * MAX_SYNC_ATTEMPTS)

    fun newClientEventId(): String = UUID.randomUUID().toString()

    private fun formatCoordinates(latitude: Double, longitude: Double): String =
        String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude)

    /** Wraps storage failures so the UI can explain that encryption is unavailable. */
    suspend fun <T> withSecureStorage(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block(), 200)
    } catch (error: SecureStorageUnavailableException) {
        ApiResult.Failure(
            com.guardian.safety.remote.ApiError(
                code = "secure_storage_unavailable",
                message = error.message ?: "Encrypted storage is unavailable on this device.",
            ),
        )
    }

    private companion object Constants {
        const val CANCELLED_STATUS = "CANCELLED"
        const val DEFAULT_RADIUS_METERS = 5_000
        const val DEFAULT_TTL_MINUTES = 720
        const val LOCATION_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        const val UNSYNCED_RETENTION_MS = 14L * 24 * 60 * 60 * 1_000
    }
}
