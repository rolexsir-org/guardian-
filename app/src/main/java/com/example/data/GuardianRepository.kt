package com.example.data

import kotlinx.coroutines.flow.Flow

class GuardianRepository(private val dao: GuardianDao) {
    val incidents: Flow<List<IncidentEntity>> = dao.getAllIncidents()
    val contacts: Flow<List<ContactEntity>> = dao.getAllContacts()
    val checkins: Flow<List<CheckinEntity>> = dao.getAllCheckins()
    val medicalProfile: Flow<MedicalProfileEntity?> = dao.getMedicalProfile()
    val sosQueue: Flow<List<SosQueueEntity>> = dao.getAllSosQueue()
    val locationHistory: Flow<List<LocationCacheEntity>> = dao.getLocationHistory()
    val auditLogs: Flow<List<AuditLogEntity>> = dao.getAllAuditLogs()
    val familyGroup: Flow<FamilyGroupEntity?> = dao.getFamilyGroup()
    val familyMembers: Flow<List<FamilyMemberEntity>> = dao.getAllFamilyMembers()
    val safeZones: Flow<List<SafeZoneEntity>> = dao.getAllSafeZones()
    val appUsage: Flow<List<AppUsageEntity>> = dao.getAllAppUsage()
    val familyMessages: Flow<List<FamilyMessageEntity>> = dao.getAllFamilyMessages()

    suspend fun insertIncident(incident: IncidentEntity) {
        dao.insertIncident(incident)
    }

    suspend fun updateIncident(incident: IncidentEntity) {
        dao.updateIncident(incident)
    }

    suspend fun insertContact(contact: ContactEntity) {
        dao.insertContact(contact)
    }

    suspend fun updateContact(contact: ContactEntity) {
        dao.updateContact(contact)
    }

    suspend fun deleteContact(contact: ContactEntity) {
        dao.deleteContact(contact)
    }

    suspend fun insertCheckin(checkin: CheckinEntity): Long {
        return dao.insertCheckin(checkin)
    }

    suspend fun updateCheckin(checkin: CheckinEntity) {
        dao.updateCheckin(checkin)
    }

    suspend fun upsertMedicalProfile(profile: MedicalProfileEntity) {
        dao.upsertMedicalProfile(profile)
    }

    suspend fun insertSosQueue(sos: SosQueueEntity): Long {
        return dao.insertSosQueue(sos)
    }

    suspend fun getPendingSosQueue(): List<SosQueueEntity> {
        return dao.getPendingSosQueue()
    }

    suspend fun updateSosQueue(sos: SosQueueEntity) {
        dao.updateSosQueue(sos)
    }

    suspend fun insertLocationCache(location: LocationCacheEntity): Long {
        return dao.insertLocationCache(location)
    }

    suspend fun purgeOldLocations(cutoff: Long) {
        dao.purgeOldLocations(cutoff)
    }

    suspend fun insertAuditLog(auditLog: AuditLogEntity): Long {
        return dao.insertAuditLog(auditLog)
    }

    suspend fun insertFamilyGroup(group: FamilyGroupEntity): Long {
        return dao.insertFamilyGroup(group)
    }

    suspend fun insertFamilyMember(member: FamilyMemberEntity): Long {
        return dao.insertFamilyMember(member)
    }

    suspend fun updateFamilyMember(member: FamilyMemberEntity) {
        dao.updateFamilyMember(member)
    }

    suspend fun insertSafeZone(zone: SafeZoneEntity): Long {
        return dao.insertSafeZone(zone)
    }

    suspend fun deleteSafeZone(zone: SafeZoneEntity) {
        dao.deleteSafeZone(zone)
    }

    suspend fun insertAppUsage(usage: AppUsageEntity): Long {
        return dao.insertAppUsage(usage)
    }

    suspend fun updateAppUsage(usage: AppUsageEntity) {
        dao.updateAppUsage(usage)
    }

    suspend fun insertFamilyMessage(message: FamilyMessageEntity): Long {
        return dao.insertFamilyMessage(message)
    }
}
