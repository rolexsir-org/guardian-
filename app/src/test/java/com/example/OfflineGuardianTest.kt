package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineGuardianTest {
    private lateinit var database: GuardianDatabase
    private lateinit var dao: GuardianDao
    private lateinit var repository: GuardianRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, GuardianDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.guardianDao()
        repository = GuardianRepository(dao)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    @Test
    fun testOfflineSosQueuePersistenceAndSyncState() = runBlocking {
        val sos = SosQueueEntity(
            userId = 1L,
            latitude = 37.7749,
            longitude = -122.4194,
            batteryLevel = 80,
            triggerSource = "BUTTON",
            syncStatus = "PENDING"
        )
        val id = repository.insertSosQueue(sos)
        assertTrue(id > 0)

        val pending = repository.getPendingSosQueue()
        assertEquals(1, pending.size)
        assertEquals("PENDING", pending[0].syncStatus)

        val updated = pending[0].copy(syncStatus = "SYNCED")
        repository.updateSosQueue(updated)

        val pendingAfter = repository.getPendingSosQueue()
        assertTrue(pendingAfter.isEmpty())
    }

    @Test
    fun testLocationCachePersistence() = runBlocking {
        val location = LocationCacheEntity(
            latitude = 34.0522,
            longitude = -118.2437,
            accuracy = 3.5f,
            speed = 1.2f,
            altitude = 50.0,
            batteryLevel = 95,
            synced = false
        )
        val id = repository.insertLocationCache(location)
        assertTrue(id > 0)

        val history = repository.locationHistory.first()
        assertEquals(1, history.size)
        assertEquals(34.0522, history[0].latitude, 0.0001)
    }

    @Test
    fun testMedicalProfileUpsert() = runBlocking {
        val profile = MedicalProfileEntity(
            id = 1L,
            name = "John Doe",
            bloodGroup = "O+",
            allergies = "Penicillin",
            medicalConditions = "Asthma"
        )
        repository.upsertMedicalProfile(profile)

        val fetched = repository.medicalProfile.first()
        assertNotNull(fetched)
        assertEquals("John Doe", fetched?.name)
        assertEquals("O+", fetched?.bloodGroup)
    }

    @Test
    fun testSecureEncryptedPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.service.SecureEncryptedPreferences.getInstance(context)
        prefs.saveAuthToken("secret_token_123")
        prefs.saveUserPin("5678")

        assertEquals("secret_token_123", prefs.getAuthToken())
        assertEquals("5678", prefs.getUserPin())
    }

    @Test
    fun testAuditLoggingSystem() = runBlocking {
        val auditLog = AuditLogEntity(
            triggerType = "PIN",
            actionDetails = "Emergency triggered via PIN override 9999",
            status = "TRIGGERED"
        )
        val id = repository.insertAuditLog(auditLog)
        assertTrue(id > 0)

        val logs = repository.auditLogs.first()
        assertEquals(1, logs.size)
        assertEquals("PIN", logs[0].triggerType)
        assertEquals("TRIGGERED", logs[0].status)
    }
}
