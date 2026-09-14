package com.guardian.safety

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.guardian.safety.data.GuardianDatabase
import com.guardian.safety.data.GuardianRepository
import com.guardian.safety.remote.ApiClient
import com.guardian.safety.remote.AuthRepository
import com.guardian.safety.remote.CloudArtifactRepository
import com.guardian.safety.remote.CloudConfig
import com.guardian.safety.remote.CloudRepository
import com.guardian.safety.service.LocationService
import com.guardian.safety.service.LocationSyncCoordinator
import com.guardian.safety.service.SecureEncryptedPreferences
import com.guardian.safety.service.SessionManager
import com.guardian.safety.service.SosEmergencyManager
import com.guardian.safety.service.TokenManager
import com.guardian.safety.service.UserPresenceService
import com.guardian.safety.worker.RiskAreaGeofenceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Process-wide dependency graph.
 *
 * The app has no third-party realtime database, no bootstrap credentials and no hidden fallback backend:
 * every dependency here is either local (encrypted storage, Room/SQLCipher) or the
 * single Guardian Worker configured through [CloudConfig].
 */
class AppContainer(val application: Application) {

    val applicationScope = CoroutineScope(SupervisorJob())

    val securePreferences: SecureEncryptedPreferences = SecureEncryptedPreferences.getInstance(application)

    val tokenManager: TokenManager = TokenManager(securePreferences)

    val database: GuardianDatabase by lazy { GuardianDatabase.getDatabase(application) }

    val apiClient: ApiClient by lazy {
        ApiClient(tokenManager) {
            // The server rejected our credentials: reflect it in the session state.
            applicationScope.launch { sessionManager.onSessionInvalidated(null) }
        }
    }

    val authRepository: AuthRepository by lazy { AuthRepository(apiClient, securePreferences) }

    val cloudRepository: CloudRepository by lazy { CloudRepository(apiClient) }

    val artifactRepository: CloudArtifactRepository by lazy { CloudArtifactRepository(apiClient) }

    val repository: GuardianRepository by lazy {
        GuardianRepository(
            context = application,
            guardianDao = database.guardianDao(),
            cloudRepository = cloudRepository,
            artifactRepository = artifactRepository,
        )
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(application, tokenManager, authRepository, securePreferences)
    }

    val sosEmergencyManager: SosEmergencyManager by lazy {
        SosEmergencyManager(application, repository, sessionManager)
    }

    val locationService: LocationService by lazy { LocationService(application) }

    val locationSyncCoordinator: LocationSyncCoordinator by lazy {
        LocationSyncCoordinator(repository)
    }

    val userPresenceService: UserPresenceService by lazy {
        UserPresenceService(repository, applicationScope)
    }

    /** True when this build has a real Worker URL; the UI must say so when it does not. */
    val isCloudConfigured: Boolean get() = CloudConfig.configured

    val configurationError: String? get() = CloudConfig.configurationError

    /** Only scheduled when a backend is configured and the user is signed in. */
    fun scheduleBackgroundWork() {
        if (!isCloudConfigured) {
            Log.i(TAG, "Background workers not scheduled: ${CloudConfig.configurationError}")
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val riskAreaWork = PeriodicWorkRequestBuilder<RiskAreaGeofenceWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            RiskAreaGeofenceWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            riskAreaWork,
        )
    }

    private companion object {
        const val TAG = "GuardianApplication"
    }
}

class GuardianApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // A failing encrypted store or database must not crash the emergency UI:
        // the failure is recorded and surfaced to the user instead.
        runCatching { container.database }
            .onFailure { error ->
                Log.e(TAG, "Encrypted database could not be opened", error)
            }
        container.scheduleBackgroundWork()
    }

    companion object {
        private const val TAG = "GuardianApplication"

        fun containerFrom(context: Context): AppContainer? =
            (context.applicationContext as? GuardianApplication)?.container
    }
}
