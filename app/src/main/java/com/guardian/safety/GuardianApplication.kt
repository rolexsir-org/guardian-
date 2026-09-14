package com.guardian.safety

import android.app.Application
import android.content.Context
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
import com.guardian.safety.remote.RealtimeClient
import com.guardian.safety.service.LocationService
import com.guardian.safety.service.LocationSyncCoordinator
import com.guardian.safety.service.SecureEncryptedPreferences
import com.guardian.safety.service.SessionManager
import com.guardian.safety.service.SosEmergencyManager
import com.guardian.safety.service.TokenManager
import com.guardian.safety.service.UserPresenceService
import com.guardian.safety.worker.CleanupWorker
import com.guardian.safety.worker.RiskAreaGeofenceWorker
import com.guardian.safety.worker.SosSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import java.util.concurrent.TimeUnit

/**
 * Application entry point and dependency container.
 *
 * Everything the app needs is constructed here exactly once and injected into the
 * ViewModel: encrypted preferences, the token store, the SQLCipher database, the
 * authenticated Guardian API client, the emergency managers and the background
 * workers. There is no hidden global state, no bootstrap credential and no
 * fallback backend.
 *
 * If the cloud backend is not configured for this build the app still starts: the
 * container reports why, screens surface it, and everything that works without a
 * network (emergency call, emergency SMS, local records, local check-ins) keeps
 * working.
 */
class GuardianApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Long-lived dependencies, created once per process. */
class AppContainer(val application: Application) {

    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val securePreferences: SecureEncryptedPreferences =
        SecureEncryptedPreferences.getInstance(application)

    val tokenManager: TokenManager = TokenManager(application)

    val database: GuardianDatabase = GuardianDatabase.getInstance(application)

    /**
     * The authenticated HTTP client. When the server rejects a refresh token the
     * session manager is told, so the UI can return to the sign-in screen instead
     * of pretending the user is still signed in.
     */
    val apiClient: ApiClient = ApiClient(tokenManager) { reason ->
        sessionManager.onSessionInvalidated(reason)
    }

    val authRepository: AuthRepository = AuthRepository(apiClient, securePreferences)

    val sessionManager: SessionManager = SessionManager(
        context = application,
        tokenManager = tokenManager,
        authRepository = authRepository,
        securePrefs = securePreferences,
    )

    val cloudRepository: CloudRepository = CloudRepository(apiClient)

    val artifactRepository: CloudArtifactRepository = CloudArtifactRepository(apiClient)

    val repository: GuardianRepository = GuardianRepository(
        context = application,
        guardianDao = database.guardianDao(),
        cloudRepository = cloudRepository,
        artifactRepository = artifactRepository,
    )

    val sosEmergencyManager: SosEmergencyManager = SosEmergencyManager(
        context = application,
        repository = repository,
        sessionManager = sessionManager,
    )

    val locationService: LocationService = LocationService(application)

    val locationSyncCoordinator: LocationSyncCoordinator = LocationSyncCoordinator(repository)

    val userPresenceService: UserPresenceService = UserPresenceService(
        repository = repository,
        scope = appScope,
    )

    val realtimeClient: RealtimeClient = RealtimeClient(
        tokenManager = tokenManager,
        apiClient = apiClient,
        scope = appScope,
    )

    /** True when this build points at a real Guardian deployment. */
    val isCloudConfigured: Boolean = CloudConfig.configured

    /** Human readable explanation when [isCloudConfigured] is false. */
    val configurationError: String? = CloudConfig.configurationError

    /** Non-null when encrypted storage could not be opened on this device. */
    val secureStorageError: String? = SecureEncryptedPreferences.storageError.value

    val databaseRecoveryNotice: String? = GuardianDatabase.recoveryNotice

    init {
        if (isCloudConfigured) scheduleBackgroundWork()
    }

    /**
     * Registers periodic work that keeps local data in step with the server.
     *
     * Work is only scheduled for configured builds: a build with no backend URL has
     * nothing to talk to and must not enqueue jobs that can only fail. Emergency
     * syncing is also scheduled on demand (see [SosEmergencyManager.trigger]), so a
     * lost connection never depends on the periodic window.
     */
    fun scheduleBackgroundWork() {
        if (!isCloudConfigured) return
        val workManager = WorkManager.getInstance(application)
        val networkRequired = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        workManager.enqueueUniquePeriodicWork(
            SosSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SosSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkRequired)
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            RiskAreaGeofenceWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RiskAreaGeofenceWorker>(30, TimeUnit.MINUTES)
                .setConstraints(networkRequired)
                .build(),
        )

        workManager.enqueueUniquePeriodicWork(
            CleanupWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    companion object {
        /**
         * Returns the process-wide container. Falls back to a fresh instance when
         * the application class was replaced (for example in tests), so callers
         * never receive null.
         */
        fun from(context: Context): AppContainer {
            val appContext = context.applicationContext as Application
            val app = appContext as? GuardianApplication
            val existing = app?.let { runCatching { it.container }.getOrNull() }
            return existing ?: AppContainer(appContext)
        }
    }
}
