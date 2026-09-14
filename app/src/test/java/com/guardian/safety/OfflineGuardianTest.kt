package com.guardian.safety

import android.content.Context
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.guardian.safety.data.GuardianDao
import com.guardian.safety.data.GuardianDatabase
import com.guardian.safety.data.GuardianRepository
import com.guardian.safety.data.IncidentEntity
import com.guardian.safety.data.LocationCacheEntity
import com.guardian.safety.data.SosQueueEntity
import com.guardian.safety.remote.ApiClient
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.remote.AuthRepository
import com.guardian.safety.remote.CloudArtifactRepository
import com.guardian.safety.remote.CloudRepository
import com.guardian.safety.service.SecureEncryptedPreferences
import com.guardian.safety.service.SessionManager
import com.guardian.safety.service.SosEmergencyManager
import com.guardian.safety.service.TokenManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

/**
 * Offline behaviour of the data layer.
 *
 * These tests run on a build without a configured backend, which is exactly the
 * offline case that used to produce false "synced" states.
 *
 * The important guarantees under test: a health/safety record is written locally
 * first, it is never reported as synced while the backend is unreachable, and the
 * emergency queue keeps its idempotency key across retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineGuardianTest {

    private lateinit var context: Context
    private lateinit var database: GuardianDatabase
    private lateinit var dao: GuardianDao
    private lateinit var repository: GuardianRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var sosManager: SosEmergencyManager

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        SecureEncryptedPreferences.resetForTesting()
        database = Room.inMemoryDatabaseBuilder(context, GuardianDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.guardianDao()
        tokenManager = TokenManager(context)
        val apiClient = ApiClient(tokenManager)
        repository = GuardianRepository(
            context = context,
            guardianDao = dao,
            cloudRepository = CloudRepository(apiClient),
            artifactRepository = CloudArtifactRepository(apiClient),
        )
        sosManager = SosEmergencyManager(
            context = context,
            repository = repository,
            sessionManager = SessionManager(
                context,
                tokenManager,
                AuthRepository(apiClient, SecureEncryptedPreferences.getInstance(context)),
            ),
        )
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
        tokenManager.clearToken()
        SecureEncryptedPreferences.resetForTesting()
    }

    @Test
    fun sosQueueKeepsItsIdempotencyKeyAndIsNeverMarkedSyncedWhileOffline() = runBlocking {
        val clientEventId = UUID.randomUUID().toString()
        repository.insertSosQueue(
            SosQueueEntity(
                clientEventId = clientEventId,
                latitude = 34.0522,
                longitude = -118.2437,
                batteryLevel = 80,
                triggerSource = "BUTTON",
                syncStatus = "PENDING",
            ),
        )

        val pending = repository.getPendingSosQueue()
        assertEquals(1, pending.size)
        assertEquals("PENDING", pending[0].syncStatus)

        // With no backend configured every attempt fails, so nothing may flip to SYNCED.
        val synced = sosManager.trySyncQueue()
        assertEquals(0, synced)

        val afterAttempt = repository.getPendingSosQueue()
        assertEquals(1, afterAttempt.size)
        assertNotEquals("SYNCED", afterAttempt[0].syncStatus)
        assertEquals(clientEventId, afterAttempt[0].clientEventId)
        assertNotNull(afterAttempt[0].lastError)
    }

    @Test
    fun retryingAnEmergencyReusesTheSameClientEventId() = runBlocking {
        val clientEventId = UUID.randomUUID().toString()
        repository.insertSosQueue(
            SosQueueEntity(clientEventId = clientEventId, triggerSource = "SHAKE"),
        )
        repeat(3) {
            val record = repository.getSosByClientEventId(clientEventId)!!
            repository.syncSosEvent(record)
        }
        val record = repository.getSosByClientEventId(clientEventId)!!
        assertEquals(clientEventId, record.clientEventId)
        assertTrue(record.retryCount >= 3)
        assertNotEquals("SYNCED", record.syncStatus)
    }

    @Test
    fun emergencyContactsAreSavedLocallyAndFlaggedWhenTheyCannotSync() = runBlocking {
        val result = repository.addContact(
            name = "Nearest relative",
            phone = "+15550100",
            relationship = "Family",
            isVerified = false,
        )
        assertTrue(result is ApiResult.Failure)

        val stored = repository.getAllContactsOnce()
        assertEquals(1, stored.size)
        assertEquals("Nearest relative", stored[0].name)
        assertNotEquals("SYNCED", stored[0].syncStatus)
        assertNotNull(stored[0].lastError)
    }

    @Test
    fun safetyEventReportWithoutABackendIsReportedAsFailureNotSuccess() = runBlocking {
        val location = Location("test-provider").apply {
            latitude = 28.6139
            longitude = 77.2090
        }
        val result = repository.reportSafetyEvent(
            title = "Flooded road",
            category = "Weather",
            severity = "WARNING",
            description = "Water above the kerb on the main road.",
            location = location,
        )
        assertTrue(result is ApiResult.Failure)
        assertTrue(dao.getAllIncidents().first().isEmpty())
    }

    @Test
    fun locationsAreCachedAndOnlyFlaggedSyncedOnServerAcceptance() = runBlocking {
        val location = Location("test-provider").apply {
            latitude = 28.6139
            longitude = 77.2090
            accuracy = 5f
        }
        val id = repository.cacheLocation(location, batteryLevel = 55, source = "DEVICE")
        assertTrue(id > 0)

        val unsynced = repository.getUnsyncedLocations()
        assertEquals(1, unsynced.size)
        assertFalse(unsynced[0].synced)

        repository.markLocationSynced(id)
        assertTrue(repository.getUnsyncedLocations().isEmpty())
    }

    @Test
    fun localSafetyEventsRemainVisibleWhenTheBackendIsUnavailable() = runBlocking {
        dao.insertIncident(
            IncidentEntity(
                title = "Local note",
                category = "Hazard",
                severity = "Info",
                description = "Recorded while offline.",
                location = "28.61390, 77.20900",
                latitude = 28.6139,
                longitude = 77.2090,
                syncStatus = "PENDING",
            ),
        )
        val incidents = repository.getAllIncidents().first()
        assertEquals(1, incidents.size)
        assertEquals("PENDING", incidents[0].syncStatus)
    }

    @Test
    fun oldLocationsArePurgedButRecentOnesAreKept() = runBlocking {
        dao.insertLocationCache(
            LocationCacheEntity(
                latitude = 28.6139,
                longitude = 77.2090,
                timestamp = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1_000,
            ),
        )
        repository.cacheLocation(
            Location("test-provider").apply {
                latitude = 28.6139
                longitude = 77.2090
            },
            batteryLevel = null,
        )
        repository.purgeOldLocations()

        val remaining = repository.getLocationHistory().first()
        assertEquals(1, remaining.size)
    }
}
