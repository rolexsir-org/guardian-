package com.guardian.safety.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.guardian.safety.service.SecureEncryptedPreferences
import com.guardian.safety.service.SecureStorageUnavailableException
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/**
 * SQLCipher-encrypted Room database.
 *
 * * The passphrase is 32 random bytes generated on the device and stored in the
 *   Android Keystore-backed encrypted preferences. There is no hard-coded key and
 *   no plaintext "fallback" database: if encrypted storage is unavailable the
 *   database refuses to open and the failure is surfaced in the UI.
 * * Encryption uses **SQLCipher for Android** (`net.zetetic:sqlcipher-android`),
 *   the supported successor to the end-of-life `android-database-sqlcipher`
 *   Community Edition. The legacy package cannot be aligned to 16 KB ELF pages,
 *   which Google Play requires for apps targeting Android 15+; SQLCipher for
 *   Android has supported 16 KB pages since 4.6.1.
 * * Write-ahead logging is enabled explicitly so a background sync can read while
 *   the UI writes.
 * * Migrations are real: no destructive fallback. Development builds that still
 *   use the pre-1.0 constant passphrase are re-keyed in place (data preserved);
 *   if re-keying is impossible the old file is kept as a timestamped backup
 *   instead of being deleted.
 */
@Database(
    entities = [
        IncidentEntity::class,
        ContactEntity::class,
        CheckinEntity::class,
        MedicalProfileEntity::class,
        SosQueueEntity::class,
        LocationCacheEntity::class,
        AuditLogEntity::class,
        FamilyGroupEntity::class,
        FamilyMemberEntity::class,
        SafeZoneEntity::class,
        AppUsageEntity::class,
        FamilyMessageEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class GuardianDatabase : RoomDatabase() {

    abstract fun guardianDao(): GuardianDao

    companion object {
        private const val DATABASE_NAME = "guardian_database"

        /**
         * Pre-1.0 development builds used a constant passphrase. It is only ever
         * used to re-key such a database, never to open the app database.
         */
        private const val LEGACY_PASSPHRASE = "guardian_secure_sqlcipher_master_key"

        @Volatile
        private var INSTANCE: GuardianDatabase? = null

        @Volatile
        var recoveryNotice: String? = null
            private set

        fun getDatabase(context: Context): GuardianDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: open(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Test seam: forget the singleton so the next call re-opens the database. */
        fun resetForTesting() {
            synchronized(this) { INSTANCE = null }
        }

        private fun open(appContext: Context): GuardianDatabase {
            // SQLCipher for Android requires the native library to be loaded
            // explicitly before any of its classes are used.
            System.loadLibrary("sqlcipher")
            val passphrase = DatabaseKeyProvider.passphrase(appContext)
            val databaseFile = appContext.getDatabasePath(DATABASE_NAME)

            var builder = databaseBuilder(appContext, passphrase)
            if (!databaseFile.exists()) {
                return builder.build()
            }

            val existing = builder.build()
            val openError = runCatching { existing.openHelper.writableDatabase }.exceptionOrNull()
            if (openError == null) return existing

            // The database exists but cannot be decrypted with the current key: it
            // is a pre-1.0 database. Re-key it in place so no data is lost.
            existing.close()
            if (rekeyLegacyDatabase(databaseFile, passphrase)) {
                builder = databaseBuilder(appContext, passphrase)
                val rekeyed = builder.build()
                val retryError = runCatching { rekeyed.openHelper.writableDatabase }.exceptionOrNull()
                if (retryError == null) {
                    recoveryNotice = "Local database re-encrypted with this device's key. Your data was preserved."
                    return rekeyed
                }
                rekeyed.close()
            }

            // Re-keying failed. Never delete the user's data: quarantine the old
            // files and start a fresh encrypted database.
            quarantineUnreadableDatabase(databaseFile)
            recoveryNotice =
                "The previous local database could not be decrypted on this device. A backup of it was kept in the app's files directory and a new encrypted database was created."
            return databaseBuilder(appContext, passphrase).build()
        }

        private fun databaseBuilder(appContext: Context, passphrase: ByteArray): Builder<GuardianDatabase> =
            Room.databaseBuilder(appContext, GuardianDatabase::class.java, DATABASE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase, null, true))
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7)

        /**
         * Re-keys a database that is still protected by the pre-1.0 constant
         * passphrase. Returns false (and leaves the file untouched) when the file
         * cannot be opened with the legacy key.
         */
        private fun rekeyLegacyDatabase(databaseFile: File, newPassphrase: ByteArray): Boolean {
            return try {
                val legacy = SQLiteDatabase.openOrCreateDatabase(
                    databaseFile,
                    LEGACY_PASSPHRASE,
                    null,
                    null,
                )
                try {
                    legacy.changePassword(newPassphrase)
                } finally {
                    legacy.close()
                }
                true
            } catch (error: Throwable) {
                android.util.Log.w("GuardianDatabase", "Legacy database re-key failed", error)
                false
            }
        }

        private fun quarantineUnreadableDatabase(databaseFile: File) {
            val stamp = System.currentTimeMillis()
            val directory = databaseFile.parentFile ?: return
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val source = File(directory, DATABASE_NAME + suffix)
                if (source.exists()) {
                    val target = File(directory, "$DATABASE_NAME.legacy-$stamp$suffix")
                    runCatching { source.renameTo(target) }
                        .onFailure { android.util.Log.w("GuardianDatabase", "Could not quarantine $source") }
                }
            }
        }

        /**
         * Version 5 → 6: server-backed family/contact/SOS columns, nullable
         * coordinates (no fake locations) and idempotency keys for emergencies.
         * Tables are rebuilt so the schema matches the entities exactly; existing
         * rows are copied across, nothing is dropped.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS incidents_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "remoteId TEXT, title TEXT NOT NULL, category TEXT NOT NULL, severity TEXT NOT NULL, " +
                        "description TEXT NOT NULL, location TEXT NOT NULL, latitude REAL, longitude REAL, " +
                        "timestamp INTEGER NOT NULL, upvotes INTEGER NOT NULL, status TEXT NOT NULL, " +
                        "syncStatus TEXT NOT NULL, lastError TEXT)",
                )
                db.execSQL(
                    "INSERT INTO incidents_new (id, title, category, severity, description, location, timestamp, upvotes, status, syncStatus) " +
                        "SELECT id, title, category, severity, description, location, timestamp, upvotes, status, 'PENDING' FROM incidents",
                )
                db.execSQL("DROP TABLE incidents")
                db.execSQL("ALTER TABLE incidents_new RENAME TO incidents")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_incidents_remoteId ON incidents (remoteId)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS contacts_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, remoteId TEXT, name TEXT NOT NULL, " +
                        "phone TEXT NOT NULL, relationship TEXT NOT NULL, isVerified INTEGER NOT NULL, " +
                        "priority INTEGER NOT NULL, syncStatus TEXT NOT NULL, lastError TEXT)",
                )
                db.execSQL(
                    "INSERT INTO contacts_new (id, name, phone, relationship, isVerified, priority, syncStatus) " +
                        "SELECT id, name, phone, relationship, isVerified, 0, 'PENDING' FROM contacts",
                )
                db.execSQL("DROP TABLE contacts")
                db.execSQL("ALTER TABLE contacts_new RENAME TO contacts")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_remoteId ON contacts (remoteId)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS checkins_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, remoteId TEXT, groupRemoteId TEXT, " +
                        "startTime INTEGER NOT NULL, dueAt INTEGER NOT NULL, durationMinutes INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, note TEXT NOT NULL, syncStatus TEXT NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO checkins_new (id, startTime, dueAt, durationMinutes, status, note, syncStatus) " +
                        "SELECT id, startTime, startTime + (durationMinutes * 60000), durationMinutes, status, note, 'PENDING' FROM checkins",
                )
                db.execSQL("DROP TABLE checkins")
                db.execSQL("ALTER TABLE checkins_new RENAME TO checkins")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sos_queue_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, clientEventId TEXT NOT NULL, remoteId TEXT, " +
                        "userId TEXT, timestamp INTEGER NOT NULL, latitude REAL, longitude REAL, accuracyM REAL, " +
                        "batteryLevel INTEGER, networkStatus TEXT NOT NULL, deviceInfo TEXT NOT NULL, " +
                        "triggerSource TEXT NOT NULL, emergencyStatus TEXT NOT NULL, syncStatus TEXT NOT NULL, " +
                        "retryCount INTEGER NOT NULL, lastAttemptAt INTEGER NOT NULL, lastError TEXT, acknowledgedAt INTEGER)",
                )
                db.execSQL(
                    "INSERT INTO sos_queue_new (id, clientEventId, timestamp, latitude, longitude, batteryLevel, " +
                        "networkStatus, deviceInfo, triggerSource, emergencyStatus, syncStatus, retryCount) " +
                        "SELECT id, 'legacy-' || id || '-' || timestamp, timestamp, latitude, longitude, batteryLevel, " +
                        "networkStatus, deviceInfo, triggerSource, emergencyStatus, 'PENDING', retryCount FROM sos_queue",
                )
                db.execSQL("DROP TABLE sos_queue")
                db.execSQL("ALTER TABLE sos_queue_new RENAME TO sos_queue")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sos_queue_clientEventId ON sos_queue (clientEventId)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS family_groups_new (" +
                        "groupId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, remoteId TEXT NOT NULL, groupName TEXT NOT NULL, " +
                        "ownerUserId TEXT NOT NULL, myRole TEXT NOT NULL, memberCount INTEGER NOT NULL, " +
                        "inviteCode TEXT, createdAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO family_groups_new (groupId, groupName, ownerUserId, createdAt) " +
                        "SELECT groupId, groupName, ownerId, createdAt FROM family_groups",
                )
                db.execSQL("DROP TABLE family_groups")
                db.execSQL("ALTER TABLE family_groups_new RENAME TO family_groups")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS family_members_new (" +
                        "memberId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, remoteMemberId TEXT NOT NULL, " +
                        "groupId INTEGER NOT NULL, groupRemoteId TEXT NOT NULL, userId TEXT NOT NULL, name TEXT NOT NULL, " +
                        "role TEXT NOT NULL, email TEXT NOT NULL, phone TEXT NOT NULL, batteryLevel INTEGER, " +
                        "online INTEGER NOT NULL, lastSeenAt INTEGER, latitude REAL, longitude REAL, " +
                        "lastUpdated INTEGER NOT NULL, isSharingLocation INTEGER NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO family_members_new (memberId, groupId, name, role, email, phone, batteryLevel, " +
                        "online, latitude, longitude, lastUpdated, isSharingLocation) " +
                        "SELECT memberId, groupId, name, role, email, phone, batteryLevel, " +
                        "CASE WHEN connectionStatus = 'Online' THEN 1 ELSE 0 END, latitude, longitude, lastUpdated, " +
                        "isSharingLocation FROM family_members",
                )
                db.execSQL("DROP TABLE family_members")
                db.execSQL("ALTER TABLE family_members_new RENAME TO family_members")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_family_members_userId ON family_members (userId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_family_members_remoteMemberId ON family_members (remoteMemberId)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS family_messages_new (" +
                        "messageId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, remoteId TEXT, groupId INTEGER NOT NULL, " +
                        "groupRemoteId TEXT NOT NULL, senderUserId TEXT NOT NULL, senderName TEXT NOT NULL, text TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, isEmergencyBroadcast INTEGER NOT NULL, readCount INTEGER NOT NULL, " +
                        "syncStatus TEXT NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO family_messages_new (messageId, groupId, senderName, text, timestamp, " +
                        "isEmergencyBroadcast, readCount, syncStatus) " +
                        "SELECT messageId, groupId, senderName, text, timestamp, isEmergencyBroadcast, readCount, 'SYNCED' " +
                        "FROM family_messages",
                )
                db.execSQL("DROP TABLE family_messages")
                db.execSQL("ALTER TABLE family_messages_new RENAME TO family_messages")
            }
        }

        /**
         * Version 6 → 7: keeps the identity of the Guardian member who reported a
         * community safety event, so the map can attribute a report instead of
         * showing an invented author.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE incidents ADD COLUMN reportedBy TEXT")
            }
        }
    }
}

/**
 * Derives the SQLCipher passphrase from a per-install random secret stored in
 * Keystore-backed encrypted preferences.
 */
object DatabaseKeyProvider {

    private const val PASSPHRASE_BYTES = 32

    fun passphrase(context: Context): ByteArray {
        val prefs = SecureEncryptedPreferences.getInstance(context)
        val stored = try {
            prefs.databasePassphrase()
        } catch (error: SecureStorageUnavailableException) {
            throw SecureStorageUnavailableException(
                "Guardian cannot open its encrypted database because secure storage is unavailable on this device.",
                error,
            )
        }
        if (stored != null) return stored

        val generated = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.saveDatabasePassphrase(generated)
        return generated
    }
}
