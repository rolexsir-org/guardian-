package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardianDao {
    @Query("SELECT * FROM incidents ORDER BY timestamp DESC")
    fun getAllIncidents(): Flow<List<IncidentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: IncidentEntity)

    @Update
    suspend fun updateIncident(incident: IncidentEntity)

    @Query("SELECT * FROM contacts")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Update
    suspend fun updateContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Query("SELECT * FROM checkins ORDER BY startTime DESC")
    fun getAllCheckins(): Flow<List<CheckinEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckin(checkin: CheckinEntity): Long

    @Update
    suspend fun updateCheckin(checkin: CheckinEntity)

    @Query("SELECT * FROM medical_profile WHERE id = 1")
    fun getMedicalProfile(): Flow<MedicalProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMedicalProfile(profile: MedicalProfileEntity)

    // SOS Queue
    @Query("SELECT * FROM sos_queue ORDER BY timestamp DESC")
    fun getAllSosQueue(): Flow<List<SosQueueEntity>>

    @Query("SELECT * FROM sos_queue WHERE syncStatus = 'PENDING'")
    suspend fun getPendingSosQueue(): List<SosQueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSosQueue(sos: SosQueueEntity): Long

    @Update
    suspend fun updateSosQueue(sos: SosQueueEntity)

    // Location Cache
    @Query("SELECT * FROM location_cache ORDER BY timestamp DESC LIMIT 100")
    fun getLocationHistory(): Flow<List<LocationCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationCache(location: LocationCacheEntity): Long

    @Query("DELETE FROM location_cache WHERE timestamp < :cutoffTimestamp")
    suspend fun purgeOldLocations(cutoffTimestamp: Long)

    // Audit Logs
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllAuditLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(auditLog: AuditLogEntity): Long

    // Family Groups
    @Query("SELECT * FROM family_groups LIMIT 1")
    fun getFamilyGroup(): Flow<FamilyGroupEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFamilyGroup(group: FamilyGroupEntity): Long

    // Family Members
    @Query("SELECT * FROM family_members")
    fun getAllFamilyMembers(): Flow<List<FamilyMemberEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFamilyMember(member: FamilyMemberEntity): Long

    @Update
    suspend fun updateFamilyMember(member: FamilyMemberEntity)

    // Safe Zones
    @Query("SELECT * FROM safe_zones")
    fun getAllSafeZones(): Flow<List<SafeZoneEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSafeZone(zone: SafeZoneEntity): Long

    @Delete
    suspend fun deleteSafeZone(zone: SafeZoneEntity)

    // App Usage & Controls
    @Query("SELECT * FROM app_usage")
    fun getAllAppUsage(): Flow<List<AppUsageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppUsage(usage: AppUsageEntity): Long

    @Update
    suspend fun updateAppUsage(usage: AppUsageEntity)

    // Family Messages & Broadcasts
    @Query("SELECT * FROM family_messages ORDER BY timestamp DESC")
    fun getAllFamilyMessages(): Flow<List<FamilyMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFamilyMessage(message: FamilyMessageEntity): Long
}

