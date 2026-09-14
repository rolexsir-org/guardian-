package com.guardian.safety.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardianDao {

    // ---------------------------------------------------------------- incidents

    @Query("SELECT * FROM incidents ORDER BY timestamp DESC")
    fun getAllIncidents(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE id = :id")
    suspend fun getIncident(id: Long): IncidentEntity?

    @Query("SELECT * FROM incidents WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getIncidentByRemoteId(remoteId: String): IncidentEntity?

    @Query("SELECT * FROM incidents WHERE status = 'Active'")
    suspend fun getActiveIncidents(): List<IncidentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: IncidentEntity): Long

    @Update
    suspend fun updateIncident(incident: IncidentEntity)

    @Query("DELETE FROM incidents WHERE id = :id")
    suspend fun deleteIncident(id: Long)

    // ----------------------------------------------------------------- contacts

    @Query("SELECT * FROM contacts ORDER BY priority DESC, id ASC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE syncStatus != 'SYNCED'")
    suspend fun getUnsyncedContacts(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getContactByRemoteId(remoteId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity): Long

    @Update
    suspend fun updateContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE remoteId = :remoteId")
    suspend fun deleteContactByRemoteId(remoteId: String)

    @Query("SELECT * FROM contacts")
    suspend fun getAllContactsOnce(): List<ContactEntity>

    // ----------------------------------------------------------------- checkins

    @Query("SELECT * FROM checkins ORDER BY startTime DESC")
    fun getAllCheckins(): Flow<List<CheckinEntity>>

    @Query("SELECT * FROM checkins WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getCheckinByRemoteId(remoteId: String): CheckinEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckin(checkin: CheckinEntity): Long

    @Update
    suspend fun updateCheckin(checkin: CheckinEntity)

    // ------------------------------------------------------------ medical profile

    @Query("SELECT * FROM medical_profile WHERE id = 1")
    fun getMedicalProfile(): Flow<MedicalProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedicalProfile(profile: MedicalProfileEntity)

    // ---------------------------------------------------------------- SOS queue

    @Query("SELECT * FROM sos_queue ORDER BY timestamp DESC")
    fun getAllSosQueue(): Flow<List<SosQueueEntity>>

    /** Everything the server has not accepted yet, old failures included. */
    @Query("SELECT * FROM sos_queue WHERE syncStatus IN ('PENDING', 'SYNCING', 'FAILED') ORDER BY timestamp ASC")
    suspend fun getPendingSosQueue(): List<SosQueueEntity>

    @Query("SELECT * FROM sos_queue WHERE syncStatus = 'PENDING' ORDER BY timestamp ASC")
    suspend fun getUnsentSosQueue(): List<SosQueueEntity>

    @Query("SELECT * FROM sos_queue WHERE clientEventId = :clientEventId LIMIT 1")
    suspend fun getSosByClientEventId(clientEventId: String): SosQueueEntity?

    @Query("SELECT COUNT(*) FROM sos_queue WHERE syncStatus != 'SYNCED'")
    fun pendingSosCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSosQueue(sos: SosQueueEntity): Long

    @Update
    suspend fun updateSosQueue(sos: SosQueueEntity)

    // ----------------------------------------------------------- location cache

    @Query("SELECT * FROM location_cache ORDER BY timestamp DESC LIMIT 100")
    fun getLocationHistory(): Flow<List<LocationCacheEntity>>

    @Query("SELECT * FROM location_cache ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocation(): LocationCacheEntity?

    @Query("SELECT * FROM location_cache WHERE synced = 0 ORDER BY timestamp ASC LIMIT 50")
    suspend fun getUnsyncedLocations(): List<LocationCacheEntity>

    @Query("UPDATE location_cache SET synced = 1 WHERE id = :id")
    suspend fun markLocationSynced(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationCache(location: LocationCacheEntity): Long

    @Query("DELETE FROM location_cache WHERE timestamp < :cutoffTimestamp")
    suspend fun purgeOldLocations(cutoffTimestamp: Long)

    // -------------------------------------------------------------- audit logs

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT 200")
    fun getAllAuditLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(auditLog: AuditLogEntity): Long

    // ------------------------------------------------------------ family groups

    @Query("SELECT * FROM family_groups ORDER BY createdAt ASC LIMIT 1")
    fun getFamilyGroup(): Flow<FamilyGroupEntity?>

    @Query("SELECT * FROM family_groups ORDER BY createdAt ASC LIMIT 1")
    suspend fun getFamilyGroupOnce(): FamilyGroupEntity?

    @Query("SELECT * FROM family_groups ORDER BY createdAt ASC")
    fun getFamilyGroups(): Flow<List<FamilyGroupEntity>>

    @Query("SELECT * FROM family_groups WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getFamilyGroupByRemoteId(remoteId: String): FamilyGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFamilyGroup(group: FamilyGroupEntity): Long

    @Query("DELETE FROM family_groups")
    suspend fun clearFamilyGroups()

    // ----------------------------------------------------------- family members

    @Query("SELECT * FROM family_members ORDER BY name ASC")
    fun getAllFamilyMembers(): Flow<List<FamilyMemberEntity>>

    @Query("SELECT * FROM family_members WHERE groupRemoteId = :groupId")
    suspend fun getMembersForGroup(groupId: String): List<FamilyMemberEntity>

    @Query("SELECT * FROM family_members WHERE userId = :userId LIMIT 1")
    suspend fun getMemberByUserId(userId: String): FamilyMemberEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFamilyMember(member: FamilyMemberEntity): Long

    @Update
    suspend fun updateFamilyMember(member: FamilyMemberEntity)

    @Query("DELETE FROM family_members WHERE groupRemoteId = :groupId")
    suspend fun deleteMembersForGroup(groupId: String)

    @Query("DELETE FROM family_members")
    suspend fun clearFamilyMembers()

    // --------------------------------------------------------------- safe zones

    @Query("SELECT * FROM safe_zones")
    fun getAllSafeZones(): Flow<List<SafeZoneEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSafeZone(zone: SafeZoneEntity): Long

    @Delete
    suspend fun deleteSafeZone(zone: SafeZoneEntity)

    // -------------------------------------------------------------- app usage

    @Query("SELECT * FROM app_usage")
    fun getAllAppUsage(): Flow<List<AppUsageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppUsage(usage: AppUsageEntity): Long

    @Update
    suspend fun updateAppUsage(usage: AppUsageEntity)

    // --------------------------------------------------------- family messages

    @Query("SELECT * FROM family_messages ORDER BY timestamp ASC")
    fun getAllFamilyMessages(): Flow<List<FamilyMessageEntity>>

    @Query("SELECT * FROM family_messages WHERE syncStatus != 'SYNCED'")
    suspend fun getUnsyncedFamilyMessages(): List<FamilyMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFamilyMessage(message: FamilyMessageEntity): Long

    @Query("DELETE FROM family_messages")
    suspend fun clearFamilyMessages()
}
