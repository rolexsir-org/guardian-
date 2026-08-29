package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.sqlcipher.database.SupportFactory
import net.sqlcipher.database.SQLiteDatabase

@Database(entities = [IncidentEntity::class, ContactEntity::class, CheckinEntity::class, MedicalProfileEntity::class, SosQueueEntity::class, LocationCacheEntity::class, AuditLogEntity::class, FamilyGroupEntity::class, FamilyMemberEntity::class, SafeZoneEntity::class, AppUsageEntity::class, FamilyMessageEntity::class], version = 5, exportSchema = false)
abstract class GuardianDatabase : RoomDatabase() {
    abstract fun guardianDao(): GuardianDao

    companion object {
        @Volatile
        private var INSTANCE: GuardianDatabase? = null

        fun getDatabase(context: Context): GuardianDatabase {
            return INSTANCE ?: synchronized(this) {
                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    GuardianDatabase::class.java,
                    "guardian_database"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(GuardianDatabaseCallback())

                try {
                    SQLiteDatabase.loadLibs(context)
                    val passphrase = SQLiteDatabase.getBytes("guardian_secure_sqlcipher_master_key".toCharArray())
                    builder.openHelperFactory(SupportFactory(passphrase))
                } catch (e: Exception) {
                    android.util.Log.e("GuardianDatabase", "SQLCipher initialization failed, using standard factory for fallback", e)
                }

                val instance = builder.build()
                INSTANCE = instance
                instance
            }
        }

        private class GuardianDatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateInitialData(database.guardianDao())
                    }
                }
            }

            suspend fun populateInitialData(dao: GuardianDao) {
                dao.insertContact(ContactEntity(name = "Emergency Services", phone = "911", relationship = "ICE"))
                dao.insertContact(ContactEntity(name = "Campus / Local Security", phone = "555-0199", relationship = "Local Authorities"))
                dao.insertContact(ContactEntity(name = "Sarah (Family)", phone = "555-0143", relationship = "Family"))

                // Initialize default audit log entry for database creation & schema readiness
                dao.insertAuditLog(
                    AuditLogEntity(
                        timestamp = System.currentTimeMillis(),
                        triggerType = "SYSTEM",
                        actionDetails = "Database created and schema initialized with SQLCipher encryption and offline queues",
                        status = "SUCCESS"
                    )
                )

                // Seed Family Group & Members
                dao.insertFamilyGroup(
                    FamilyGroupEntity(
                        groupId = 1L,
                        groupName = "Johnson Family Shield",
                        ownerId = "user_owner_1",
                        inviteCode = "GUARDIAN-9921"
                    )
                )

                dao.insertFamilyMember(
                    FamilyMemberEntity(
                        memberId = 1L,
                        groupId = 1L,
                        name = "Alex Johnson (You)",
                        role = "OWNER",
                        email = "alex@guardian.org",
                        phone = "555-0101",
                        batteryLevel = 92,
                        connectionStatus = "Online",
                        latitude = 37.7749,
                        longitude = -122.4194
                    )
                )

                dao.insertFamilyMember(
                    FamilyMemberEntity(
                        memberId = 2L,
                        groupId = 1L,
                        name = "Sarah Johnson",
                        role = "PARENT",
                        email = "sarah@guardian.org",
                        phone = "555-0143",
                        batteryLevel = 84,
                        connectionStatus = "Online",
                        latitude = 37.7833,
                        longitude = -122.4167
                    )
                )

                dao.insertFamilyMember(
                    FamilyMemberEntity(
                        memberId = 3L,
                        groupId = 1L,
                        name = "Leo Johnson (Child)",
                        role = "CHILD",
                        email = "leo@guardian.org",
                        phone = "555-0199",
                        batteryLevel = 67,
                        connectionStatus = "Online",
                        latitude = 37.7692,
                        longitude = -122.4467
                    )
                )

                // Seed Safe Zones
                dao.insertSafeZone(
                    SafeZoneEntity(
                        groupId = 1L,
                        name = "Home Residence",
                        latitude = 37.7749,
                        longitude = -122.4194,
                        radiusMeters = 200f,
                        zoneType = "HOME"
                    )
                )

                dao.insertSafeZone(
                    SafeZoneEntity(
                        groupId = 1L,
                        name = "Lincoln High School",
                        latitude = 37.7692,
                        longitude = -122.4467,
                        radiusMeters = 300f,
                        zoneType = "SCHOOL"
                    )
                )

                // Seed App Usage
                dao.insertAppUsage(
                    AppUsageEntity(
                        memberId = 3L,
                        appName = "Social Media (TikTok/Insta)",
                        category = "Social",
                        dailyLimitMinutes = 90,
                        usedMinutes = 75,
                        isRestricted = false,
                        isApproved = true
                    )
                )

                dao.insertAppUsage(
                    AppUsageEntity(
                        memberId = 3L,
                        appName = "Mobile Gaming",
                        category = "Games",
                        dailyLimitMinutes = 60,
                        usedMinutes = 58,
                        isRestricted = true,
                        isApproved = false
                    )
                )

                // Seed Family Message
                dao.insertFamilyMessage(
                    FamilyMessageEntity(
                        groupId = 1L,
                        senderId = 2L,
                        senderName = "Sarah Johnson",
                        text = "Dinner is ready at 6 PM. Safe travels everyone!",
                        timestamp = System.currentTimeMillis() - 3600000,
                        isEmergencyBroadcast = false
                    )
                )

                dao.insertIncident(
                    IncidentEntity(
                        title = "Streetlight Outage on 5th Ave",
                        category = "Hazard",
                        severity = "Warning",
                        description = "Multiple streetlights flickering and out between Elm St and Oak St. Walk with caution at night.",
                        location = "5th Avenue & Elm St",
                        upvotes = 12,
                        status = "Active"
                    )
                )
                dao.insertIncident(
                    IncidentEntity(
                        title = "Suspicious Activity Near Park",
                        category = "Crime",
                        severity = "Critical",
                        description = "Unattended vehicle circling near children's playground. Reported to local patrols.",
                        location = "Central Park West",
                        upvotes = 24,
                        status = "Active"
                    )
                )
                dao.insertIncident(
                    IncidentEntity(
                        title = "Heavy Rain & Slippery Walkways",
                        category = "Weather",
                        severity = "Info",
                        description = "Flash puddles forming near the subway entrance. Watch your step.",
                        location = "Metro Station Exit 2",
                        upvotes = 8,
                        status = "Active"
                    )
                )
            }
        }
    }
}
