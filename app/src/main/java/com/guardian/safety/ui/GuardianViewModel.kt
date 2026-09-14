package com.guardian.safety.ui

import android.app.Application
import android.content.Context
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.guardian.safety.AppContainer
import com.guardian.safety.data.AuditLogEntity
import com.guardian.safety.data.CheckinEntity
import com.guardian.safety.data.ContactEntity
import com.guardian.safety.data.FamilyGroupEntity
import com.guardian.safety.data.FamilyMemberEntity
import com.guardian.safety.data.FamilyMessageEntity
import com.guardian.safety.data.IncidentEntity
import com.guardian.safety.data.MedicalProfileEntity
import com.guardian.safety.data.SafeZoneEntity
import com.guardian.safety.data.SosQueueEntity
import com.guardian.safety.remote.ApiError
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.remote.model.SosEventDto
import com.guardian.safety.service.EmergencyActionResult
import com.guardian.safety.service.EmergencySmsManager
import com.guardian.safety.service.LocationFailure
import com.guardian.safety.service.LocationService
import com.guardian.safety.service.SmsDelivery
import com.guardian.safety.service.SosStage
import com.guardian.safety.service.SosStatus
import com.guardian.safety.service.SessionState
import com.guardian.safety.service.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the UI needs to know about the current location, honestly. */
sealed interface LocationUiState {
    object Unknown : LocationUiState
    object Locating : LocationUiState

    data class Available(
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Float?,
        val updatedAt: Long,
    ) : LocationUiState

    data class Unavailable(val reason: LocationFailure, val message: String) : LocationUiState
}

/** Result of an operation the user asked for. */
sealed interface DataState {
    object Idle : DataState
    object Loading : DataState

    data class Success(val message: String) : DataState

    /** [offline] tells the UI whether a retry needs a connection. */
    data class Failure(val message: String, val offline: Boolean = false, val retryable: Boolean = false) :
        DataState
}

/** A community safety event as shown on the map and in the incident list. */
data class SafetyEventItem(
    val id: String,
    val title: String,
    val category: String,
    val severity: String,
    val description: String,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long,
    val upvotes: Int,
    val localId: Long? = null,
)

/**
 * The single view model behind every screen.
 *
 * Data rules:
 *  * Room is the offline source of truth; the flows below are fed from the local
 *    database and are refreshed from the backend only when there is a connection.
 *  * Nothing is reported as synced, sent or successful until the server (or Android
 *    itself, for calls and SMS) confirmed it.
 *  * The location shown anywhere in the app is a real device fix, or the reason
 *    there is none — never a placeholder coordinate.
 */
class GuardianViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val repository = container.repository
    private val sessionManager = container.sessionManager
    private val sosManager = container.sosEmergencyManager
    private val locationService = container.locationService
    private val preferences = container.securePreferences
    private val scope get() = viewModelScope

    // ------------------------------------------------------------------- session

    val sessionState: StateFlow<SessionState> = sessionManager.state

    private val _authState = MutableStateFlow<DataState>(DataState.Idle)
    val authState: StateFlow<DataState> = _authState.asStateFlow()

    /** Sign-in/sign-up result. Tokens are stored encrypted by the session manager. */
    fun signIn(email: String, password: String) {
        if (!container.isCloudConfigured) {
            _authState.value = DataState.Failure(
                container.configurationError ?: "This build has no Guardian service configured.",
            )
            return
        }
        _authState.value = DataState.Loading
        scope.launch {
            val result = sessionManager.signIn(email.trim(), password)
            _authState.value = when (result) {
                is ApiResult.Success -> {
                    startSignedInWork()
                    DataState.Success("Signed in as ${result.data.email}.")
                }
                is ApiResult.Failure -> DataState.Failure(
                    message = result.error.message,
                    offline = result.error.offline,
                    retryable = result.error.retryable,
                )
            }
        }
    }

    fun signUp(email: String, password: String, displayName: String, phone: String?) {
        if (!container.isCloudConfigured) {
            _authState.value = DataState.Failure(
                container.configurationError ?: "This build has no Guardian service configured.",
            )
            return
        }
        _authState.value = DataState.Loading
        scope.launch {
            val result = sessionManager.signUp(email.trim(), password, displayName.trim(), phone?.trim())
            _authState.value = when (result) {
                is ApiResult.Success -> {
                    startSignedInWork()
                    DataState.Success("Account created for ${result.data.email}.")
                }
                is ApiResult.Failure -> DataState.Failure(
                    message = result.error.message,
                    offline = result.error.offline,
                    retryable = result.error.retryable,
                )
            }
        }
    }

    /**
     * Signs out. The server is asked to revoke the session; the local credentials
     * are always cleared, and the UI says so when revocation could not be reached
     * so the user knows the session is still live on the server until it expires.
     */
    fun signOut() {
        scope.launch {
            _authState.value = DataState.Loading
            val result = sessionManager.signOut()
            stopSignedInWork()
            _authState.value = when (result) {
                is ApiResult.Success -> DataState.Success("Signed out.")
                is ApiResult.Failure -> DataState.Failure(
                    message = "Signed out on this device. The session could not be revoked on the server: " +
                        result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun clearDataMessage() {
        _dataState.value = DataState.Idle
    }

    fun clearAuthMessage() {
        _authState.value = DataState.Idle
    }

    // ------------------------------------------------------------- local records

    val incidents: StateFlow<List<IncidentEntity>> = repository.getAllIncidents()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val contacts: StateFlow<List<ContactEntity>> = repository.getAllContacts()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val checkins: StateFlow<List<CheckinEntity>> = repository.getAllCheckins()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val medicalProfile: StateFlow<MedicalProfileEntity?> = repository.getMedicalProfile()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val auditLogs: StateFlow<List<AuditLogEntity>> = repository.getAllAuditLogs()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val familyGroup: StateFlow<FamilyGroupEntity?> = repository.getFamilyGroup()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val familyGroups: StateFlow<List<FamilyGroupEntity>> = repository.getFamilyGroups()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val familyMembers: StateFlow<List<FamilyMemberEntity>> = repository.getAllFamilyMembers()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val safeZones: StateFlow<List<SafeZoneEntity>> = repository.getAllSafeZones()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val familyMessages: StateFlow<List<FamilyMessageEntity>> = repository.getAllFamilyMessages()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val appUsage: StateFlow<List<com.guardian.safety.data.AppUsageEntity>> =
        repository.getAllAppUsage().stateIn(scope, SharingStarted.Eagerly, emptyList())

    val evidenceFiles = container.artifactRepository.files
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pendingEvidenceCount: StateFlow<Int> = container.artifactRepository.pending
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Community safety events from the backend, cached locally for offline use. */
    val safetyEvents: StateFlow<List<SafetyEventItem>> = incidents.map { rows ->
        rows.map { row ->
            SafetyEventItem(
                id = row.remoteId ?: "local-${row.id}",
                title = row.title,
                category = row.category,
                severity = row.severity,
                description = row.description,
                latitude = row.latitude,
                longitude = row.longitude,
                timestamp = row.timestamp,
                upvotes = row.upvotes,
                localId = row.id,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * SOS alerts visible to the community/nearby responders, straight from the
     * server. Empty until the server answers; never seeded locally.
     */
    val activeSosEvents: StateFlow<List<SosEventDto>> = container.realtimeClient.activeSos

    val realtimeState = container.realtimeClient.state

    // ----------------------------------------------------------------- location

    private val _locationState = MutableStateFlow<LocationUiState>(LocationUiState.Unknown)
    val locationState: StateFlow<LocationUiState> = _locationState.asStateFlow()

    private val _currentLatitude = MutableStateFlow<Double?>(null)
    val currentLatitude: StateFlow<Double?> = _currentLatitude.asStateFlow()

    private val _currentLongitude = MutableStateFlow<Double?>(null)
    val currentLongitude: StateFlow<Double?> = _currentLongitude.asStateFlow()

    /** True when at least one system location provider is switched on. */
    var providersEnabled: Boolean = false
        private set

    val hasLocationPermission: Boolean
        get() = locationService.hasLocationPermission()

    fun locationFailureMessage(): String? = (_locationState.value as? LocationUiState.Unavailable)?.message

    /**
     * Requests one fresh fix. Reports permission denial, disabled providers, a
     * timeout or a missing provider instead of falling back to a default point.
     */
    fun refreshLocation() {
        if (!locationService.hasLocationPermission()) {
            _locationState.value = LocationUiState.Unavailable(
                reason = LocationFailure.PermissionDenied,
                message = "Location permission is not granted, so Guardian cannot show where you are.",
            )
            return
        }
        providersEnabled = locationService.areProvidersEnabled()
        if (!providersEnabled) {
            _locationState.value = LocationUiState.Unavailable(
                reason = LocationFailure.ProvidersDisabled,
                message = "Location services are switched off. Turn them on to share your position.",
            )
            return
        }

        _locationState.value = LocationUiState.Locating
        scope.launch {
            val (location, failure) = locationService.currentLocation()
            if (location != null) {
                val fix = location
                _currentLatitude.value = fix.latitude
                _currentLongitude.value = fix.longitude
                _locationState.value = LocationUiState.Available(
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    accuracyM = fix.accuracy.takeIf { fix.hasAccuracy() },
                    updatedAt = System.currentTimeMillis(),
                )
                captureLocationForOfflineUse(fix)
            } else {
                _locationState.value = LocationUiState.Unavailable(
                    reason = failure ?: LocationFailure.Unavailable,
                    message = failureMessage(failure ?: LocationFailure.Unavailable),
                )
            }
        }
    }

    private fun failureMessage(failure: LocationFailure): String = when (failure) {
        LocationFailure.PermissionDenied -> "Location permission is not granted."
        LocationFailure.ProvidersDisabled -> "Location services are switched off."
        LocationFailure.Timeout -> "No location fix arrived in time. Move somewhere with a clearer view of the sky and try again."
        LocationFailure.Unavailable -> "This device could not produce a location fix."
    }

    private suspend fun captureLocationForOfflineUse(location: Location) {
        runCatching { repository.cacheLocation(location, batteryLevel()) }
            .onFailure { Log.w(TAG, "Could not cache the location fix locally") }
        container.userPresenceService.markActive(location)
    }

    // --------------------------------------------------------------------- SOS

    val sosStatus: StateFlow<SosStatus?> = sosManager.status

    val sosActive: StateFlow<SosStatus?> = sosManager.status

    private val _lastSosResult = MutableStateFlow<SosStatus?>(null)
    val lastSosResult: StateFlow<SosStatus?> = _lastSosResult.asStateFlow()

    val pendingSosCount: StateFlow<Int> = repository.getPendingSosCount()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val smsDeliveries: StateFlow<Map<String, SmsDelivery>> = EmergencySmsManager.deliveries

    /**
     * Triggers an emergency. Device actions happen first and the cloud upload is
     * idempotent; see [com.guardian.safety.service.SosEmergencyManager].
     */
    fun triggerSos(triggerSource: String = "BUTTON", onResult: ((SosStatus) -> Unit)? = null) {
        if (_sosInFlight) return
        _sosInFlight = true
        scope.launch {
            try {
                val fix = locationService.currentLocation(timeoutMs = SOS_LOCATION_TIMEOUT_MS).first
                val status = sosManager.trigger(
                    triggerSource = triggerSource,
                    location = fix,
                    batteryLevel = battery(),
                    networkStatus = networkStatus(),
                    deviceInfo = deviceLabel(),
                    contactPhoneNumbers = repository.getAllContactsOnce()
                        .map { it.phone }
                        .filter { it.isNotBlank() },
                )
                _lastSosResult.value = status
                _dataState.value = when {
                    status.problems.isEmpty() -> DataState.Success(status.summary())
                    else -> DataState.Failure(
                        message = status.problems.first(),
                        offline = status.cloudError != null,
                    )
                }
                onResult?.invoke(status)
            } finally {
                _sosInFlight = false
            }
        }
    }

    private var _sosInFlight = false

    /** Cancels an alert triggered by mistake. Reports when the server copy remains. */
    fun cancelSos() {
        val active = sosManager.status.value ?: return
        scope.launch {
            val result = sosManager.cancel(active.clientEventId)
            sosManager.clearStatus()
            _dataState.value = when (result) {
                is ApiResult.Success -> DataState.Success("Alert cancelled.")
                is ApiResult.Failure -> DataState.Failure(
                    message = "Cancelled on this device. ${result.error.message}",
                    offline = result.error.offline,
                )
            }
        }
    }

    fun restoreActiveEmergency() {
        scope.launch { sosManager.restoreActive() }
    }

    fun retryPendingSos() {
        scope.launch {
            _dataState.value = DataState.Loading
            val outcome = sosManager.trySyncQueue()
            _dataState.value = when {
                outcome.uploaded > 0 -> DataState.Success("Synced ${outcome.uploaded} emergency record(s).")
                outcome.attempted == 0 -> DataState.Success("Nothing is waiting to sync.")
                else -> DataState.Failure(
                    message = outcome.problems.firstOrNull()
                        ?: "Guardian could not reach the server. Records stay queued and will be sent automatically.",
                    offline = true
                )
            }
        }
    }

    // ------------------------------------------------------------- permissions

    fun onNotificationPermissionResult(granted: Boolean) {
        _dataState.value = if (granted) {
            DataState.Success("Emergency notifications are enabled.")
        } else {
            DataState.Failure("Notifications are off, so emergency alerts may not reach you.")
        }
    }

    /** Faces an explicit failure the user asked about (permission denied, etc.). */
    fun reportActionProblem(message: String) {
        _dataState.value = DataState.Failure(message)
    }

    // ---------------------------------------------------------------- self check

    private val _safetySelfCheck = MutableStateFlow<List<String>>(emptyList())
    val safetySelfCheck: StateFlow<List<String>> = _safetySelfCheck.asStateFlow()

    /** Real device checks: no invented "all clear". */
    fun runSafetySelfCheck() {
        scope.launch {
            val checks = mutableListOf<String>()
            checks += if (locationService.hasLocationPermission()) {
                "Location permission: granted"
            } else {
                "Location permission: not granted — your location cannot be shared"
            }
            val providers = locationService.areProvidersEnabled()
            checks += if (providers) {
                "Location services: on"
            } else {
                "Location services: off — turn them on to be found"
            }
            val contacts = repository.getAllContactsOnce()
            checks += if (contacts.isEmpty()) {
                "Emergency contacts: none saved — add at least one"
            } else {
                "Emergency contacts: ${contacts.size} saved"
            }
            checks += if (com.guardian.safety.service.EmergencyCallManager.canPlaceCalls(getApplication())) {
                "Calling: the app may place emergency calls"
            } else {
                "Calling: the dialler will open instead of calling directly"
            }
            checks += if (com.guardian.safety.service.EmergencySmsManager.canSendSms(getApplication())) {
                "Alert messages: the app may send SMS directly"
            } else {
                "Alert messages: your messaging app will need one confirmation tap"
            }
            checks += if (container.isCloudConfigured) {
                "Guardian service: configured${if (sessionManager.currentSession() != null) " and signed in" else " — sign in to share alerts"}"
            } else {
                "Guardian service: not configured on this build (${container.configurationError ?: "no URL"})"
            }
            _safetySelfCheck.value = checks
        }
    }

    // --------------------------------------------------------------- operations

    private val _dataState = MutableStateFlow<DataState>(DataState.Idle)
    val dataState: StateFlow<DataState> = _dataState.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    val isCloudConfigured: Boolean = container.isCloudConfigured
    val cloudConfigurationError: String? = container.configurationError
    val secureStorageError: String? = container.secureStorageError
    val databaseRecoveryNotice: String? = container.databaseRecoveryNotice

    /** Refreshes every server-backed collection, keeping local data on failure. */
    fun refreshAll() {
        scope.launch {
            if (!container.isCloudConfigured) {
                _dataState.value = DataState.Failure(
                    container.configurationError ?: "This build has no Guardian service configured.",
                )
                return@launch
            }
            if (sessionManager.currentSession() == null) {
                _dataState.value = DataState.Failure("Sign in to sync with the Guardian service.")
                return@launch
            }
            _isBusy.value = true
            val failures = mutableListOf<String>()
            val session = sessionManager.currentSession()

            collect("contacts", repository.syncContacts()) { failures += it }
            collect("family", repository.syncFamily()) { failures += it }
            collect("safety events", repository.syncFamilyMessages(remoteGroupId())) { failures += it }

            val fix = locationService.currentLocation(timeoutMs = REFRESH_LOCATION_TIMEOUT_MS).first
            if (fix != null) {
                collect("nearby safety events", repository.refreshNearbySafetyEvents(fix)) { failures += it }
                val group = repository.getFamilyGroupOnce()
                if (group != null) {
                    collect("location sharing", repository.shareLocation(
                        groupRemoteId = group.remoteId,
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        accuracy = fix.accuracy.takeIf { fix.hasAccuracy() },
                        batteryLevel = battery(),
                        source = "MANUAL",
                    )) { failures += it }
                }
            }

            collect("presence", repository.sendPresence("ONLINE", battery())) { failures += it }
            collect("emergency queue", sosManager.trySyncQueue().let {
                if (it.attempted > 0 && it.uploaded == 0) ApiResult.Failure(
                    ApiError("sync_pending", "Emergency records are still waiting to sync.", offline = true, retryable = true),
                ) else ApiResult.Success(Unit, 200)
            }) { failures += it }

            _isBusy.value = false
            _dataState.value = when {
                failures.isEmpty() -> DataState.Success(
                    "Synced${session?.displayName?.let { " for $it" } ?: ""} at ${clockNow()}.",
                )
                else -> DataState.Failure(failures.first(), offline = true)
            }
        }
    }

    private fun <T> collect(label: String, result: ApiResult<T>, onFailure: (String) -> Unit) {
        if (result is ApiResult.Failure) {
            Log.w(TAG, "Refresh step '$label' failed: ${result.error.code}")
            onFailure(result.error.message)
        }
    }

    // --------------------------------------------------------------- safety data

    fun reportSafetyEvent(title: String, category: String, severity: String, description: String) {
        scope.launch {
            val fix = locationService.currentLocation(timeoutMs = REPORT_LOCATION_TIMEOUT_MS).first
            if (fix == null) {
                _dataState.value = DataState.Failure(
                    "Guardian needs your current location to file a report. Turn on location and try again.",
                )
                return@launch
            }
            _dataState.value = DataState.Loading
            when (val result = repository.reportSafetyEvent(title, category, severity, description, fix)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Report shared with the community.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    message = if (result.error.offline) {
                        "You are offline, so the report was saved on this device and will be shared when you reconnect."
                    } else {
                        result.error.message
                    },
                    offline = result.error.offline,
                    retryable = result.error.retryable,
                )
            }
        }
    }

    fun confirmSafetyEvent(eventId: String) {
        scope.launch {
            val localId = eventId.removePrefix("local-").toLongOrNull()
            val incident = localId?.let { id -> incidents.value.firstOrNull { it.id == id } }
            if (incident == null) {
                _dataState.value = DataState.Failure("That report is no longer available.")
                return@launch
            }
            when (val result = repository.confirmIncident(incident.id)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Confirmation recorded.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    message = if (result.error.offline) {
                        "You are offline. Your confirmation will be sent when the connection returns."
                    } else {
                        result.error.message
                    },
                    offline = result.error.offline,
                )
            }
        }
    }

    fun resolveSafetyEvent(incident: IncidentEntity) {
        scope.launch {
            when (val result = repository.resolveSafetyEvent(incident)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Marked as resolved on this device.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message)
            }
        }
    }

    fun deleteIncident(incident: IncidentEntity) {
        scope.launch { repository.deleteIncident(incident.id) }
    }

    // ------------------------------------------------------------------ contacts

    fun addContact(name: String, phone: String, relationship: String, isPrimary: Boolean) {
        scope.launch {
            _dataState.value = DataState.Loading
            when (val result = repository.addContact(name, phone, relationship, isPrimary)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Contact saved and synced.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    message = if (result.error.offline) {
                        "Saved on this device. It will be synced when you reconnect."
                    } else {
                        result.error.message
                    },
                    offline = result.error.offline,
                )
            }
        }
    }

    fun updateContact(contact: ContactEntity) {
        scope.launch {
            when (val result = repository.updateContact(contact)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Contact updated.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message, result.error.offline)
            }
        }
    }

    fun deleteContact(contact: ContactEntity) {
        scope.launch {
            when (val result = repository.deleteContact(contact)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Contact removed.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message, result.error.offline)
            }
        }
    }

    // ----------------------------------------------------------------- check-ins

    fun startCheckin(durationMinutes: Int, note: String) {
        scope.launch {
            when (val result = repository.startCheckin(durationMinutes, note)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Check-in started.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    message = if (result.error.offline) {
                        "You are offline: this check-in is stored locally and will be started when you reconnect."
                    } else {
                        result.error.message
                    },
                    offline = result.error.offline,
                )
            }
        }
    }

    fun completeCheckin(checkinId: Long, status: String) {
        scope.launch {
            when (val result = repository.completeCheckin(checkinId, status)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Check-in $status.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message, result.error.offline)
            }
        }
    }

    // -------------------------------------------------------------------- family

    fun createFamilyGroup(name: String) {
        scope.launch {
            when (val result = repository.createFamilyGroup(name)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Family created.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message, result.error.offline)
            }
        }
    }

    fun joinFamilyGroup(inviteCode: String) {
        scope.launch {
            when (val result = repository.joinFamilyGroup(inviteCode)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Joined the family group.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message, result.error.offline)
            }
        }
    }

    /**
     * Creates a join code. The plaintext code is only ever available from the
     * server response — it is stored hashed server-side, so it cannot be read back.
     */
    fun createInvite(role: String, onCode: (String) -> Unit) {
        scope.launch {
            val group = repository.getFamilyGroupOnce()
            if (group == null) {
                _dataState.value = DataState.Failure("Create or join a family group first.")
                return@launch
            }
            when (val result = repository.createInvite(group.remoteId, role)) {
                is ApiResult.Success -> {
                    onCode(result.data.code)
                    _dataState.value = DataState.Success("Invite created. It is shown once.")
                }
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message, result.error.offline)
            }
        }
    }

    fun removeFamilyMember(memberId: String) {
        scope.launch {
            val group = repository.getFamilyGroupOnce() ?: return@launch
            when (val result = repository.removeFamilyMember(group.remoteId, memberId)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Member removed.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message, result.error.offline)
            }
        }
    }

    fun sendFamilyMessage(text: String, isEmergency: Boolean = false) {
        val group = familyGroup.value
        if (group == null) {
            _dataState.value = DataState.Failure("Join a family group before sending messages.")
            return
        }
        if (preferences.isStudyModeEnabled() && !isEmergency) {
            _dataState.value = DataState.Failure(
                "Study mode is on: only emergency messages are sent from this device right now.",
            )
            return
        }
        val session = sessionManager.currentSession()
        if (session == null) {
            _dataState.value = DataState.Failure("Sign in to send messages.")
            return
        }
        scope.launch {
            when (val result = repository.sendFamilyMessage(
                groupRemoteId = group.remoteId,
                senderUserId = session.userId,
                senderName = session.displayName,
                text = text,
                isEmergency = isEmergency,
            )) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Message sent.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    message = if (result.error.offline) {
                        "Offline: the message is saved on this device and will be sent when you reconnect."
                    } else {
                        result.error.message
                    },
                    offline = result.error.offline,
                )
            }
        }
    }

    fun shareCurrentLocationWithFamily() {
        scope.launch {
            val group = repository.getFamilyGroupOnce()
            if (group == null) {
                _dataState.value = DataState.Failure("Join a family group to share your location.")
                return@launch
            }
            val fix = locationService.currentLocation().first
            if (fix == null) {
                _dataState.value = DataState.Failure(
                    locationFailureMessage() ?: "No location fix is available yet.",
                )
                return@launch
            }
            when (val result = repository.shareLocation(
                groupRemoteId = group.remoteId,
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracy = fix.accuracy.takeIf { fix.hasAccuracy() },
                batteryLevel = battery(),
                source = "MANUAL",
            )) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Location shared with your family.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    message = if (result.error.offline) {
                        "You are offline, so your family cannot see this update yet."
                    } else {
                        result.error.message
                    },
                    offline = result.error.offline,
                )
            }
        }
    }

    fun refreshGroupLocations() {
        scope.launch {
            val group = repository.getFamilyGroupOnce() ?: return@launch
            repository.groupLocations(group.remoteId)
        }
    }

    // ------------------------------------------------------------- safety events

    fun refreshSafetyEvents() {
        scope.launch {
            val fix = locationService.currentLocation(timeoutMs = REFRESH_LOCATION_TIMEOUT_MS).first
            if (fix == null) {
                _dataState.value = DataState.Failure(
                    locationFailureMessage() ?: "Guardian needs a location fix to find events around you.",
                )
                return@launch
            }
            when (val result = repository.refreshNearbySafetyEvents(fix)) {
                is ApiResult.Success -> _dataState.value = DataState.Success(
                    if (result.data == 0) "No safety events have been reported around you." else "Updated ${result.data} event(s).",
                )
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message, result.error.offline)
            }
        }
    }

    fun subscribeToIncidents(category: String, subscribed: Boolean) {
        preferences.setIncidentSubscribed(category, subscribed)
    }

    fun isSubscribedToIncidents(category: String): Boolean = preferences.isIncidentSubscribed(category)

    // ------------------------------------------------------------------- medical

    fun saveMedicalProfile(profile: MedicalProfileEntity) {
        scope.launch {
            repository.saveMedicalProfile(profile)
            _dataState.value = DataState.Success("Medical details saved on this device.")
        }
    }

    // ------------------------------------------------------------------- safety

    fun setEmergencyContactDeliveryEnabled(enabled: Boolean) {
        preferences.setEmergencyDeliveryEnabled(enabled)
    }

    fun isEmergencyDeliveryEnabled(): Boolean = preferences.isEmergencyDeliveryEnabled()

    // ------------------------------------------------------------------ settings

    val isLiveGpsEnabled: StateFlow<Boolean> = preferences.isLiveGpsEnabledFlow()
    val isVoiceActivationEnabled: StateFlow<Boolean> = preferences.isVoiceActivationEnabledFlow()
    val isFallDetectionEnabled: StateFlow<Boolean> = preferences.isFallDetectionEnabledFlow()
    val isShakeGestureEnabled: StateFlow<Boolean> = preferences.isShakeGestureEnabledFlow()
    val isBackgroundMonitoringEnabled: StateFlow<Boolean> = preferences.isBackgroundMonitoringEnabledFlow()
    val isIncognitoModeEnabled: StateFlow<Boolean> = preferences.isIncognitoModeEnabledFlow()
    val isBiometricLockEnabled: StateFlow<Boolean> = preferences.isBiometricLockEnabledFlow()
    val isStudyModeEnabled: StateFlow<Boolean> = preferences.isStudyModeEnabledFlow()
    val isBedtimeScheduleEnabled: StateFlow<Boolean> = preferences.isBedtimeScheduleEnabledFlow()

    fun toggleLiveGps(enabled: Boolean) = updatePreference("live location") {
        preferences.setLiveGpsEnabled(enabled)
    }

    fun toggleVoiceActivation(enabled: Boolean, context: Context) = updatePreference("voice activation") {
        preferences.setVoiceActivationEnabled(enabled)
    }

    fun toggleFallDetection(enabled: Boolean) = updatePreference("fall detection") {
        preferences.setFallDetectionEnabled(enabled)
    }

    fun toggleShakeGesture(enabled: Boolean) = updatePreference("shake gesture") {
        preferences.setShakeGestureEnabled(enabled)
    }

    fun toggleBackgroundMonitoring(enabled: Boolean) = updatePreference("background monitoring") {
        preferences.setBackgroundMonitoringEnabled(enabled)
    }

    fun toggleIncognitoMode(enabled: Boolean) = updatePreference("incognito mode") {
        preferences.setIncognitoModeEnabled(enabled)
    }

    fun toggleBiometricLock(enabled: Boolean) = updatePreference("biometric lock") {
        preferences.setBiometricLockEnabled(enabled)
    }

    fun toggleStudyMode(enabled: Boolean) = updatePreference("study mode") {
        preferences.setStudyModeEnabled(enabled)
    }

    fun toggleBedtimeSchedule(enabled: Boolean) = updatePreference("bedtime schedule") {
        preferences.setBedtimeScheduleEnabled(enabled)
    }

    private fun updatePreference(label: String, block: () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { _dataState.value = DataState.Success("$label updated.") }
                .onFailure {
                    _dataState.value = DataState.Failure(
                        "Could not save the $label setting: encrypted storage is unavailable.",
                    )
                }
        }
    }

    // -------------------------------------------------------------------- system

    val isVoiceRecordingAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun battery(): Int? = battery0(getApplication())

    fun networkStatus(): String = if (isOnline()) "ONLINE" else "OFFLINE"

    fun isOnline(): Boolean {
        val manager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun deviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)

    private fun clockNow(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    // ------------------------------------------------------------------ lifecycle

    private var signedInJob: Job? = null

    private fun startSignedInWork() {
        val session = sessionManager.currentSession() ?: return
        container.userPresenceService.start()
        container.realtimeClient.subscribe(groupRealtimePath())
        refreshAll()
        Log.i(TAG, "Session active for user ${'$'}{session.userId.take(8)}")
    }

    private fun stopSignedInWork() {
        signedInJob?.cancel()
        signedInJob = null
        container.userPresenceService.stop()
        container.realtimeClient.close()
    }

    private fun groupRealtimePath(): String {
        val group = familyGroup.value
        return if (group != null) {
            "v1/realtime/family/${group.remoteId}"
        } else {
            "v1/realtime/nearby"
        }
    }

    private fun remoteGroupId(): String = familyGroup.value?.remoteId ?: ""

    init {
        refreshLocation()
        runSafetySelfCheck()
        scope.launch {
            // Restore the session once; the session manager validates the real
            // token expiry and refreshes when possible.
            val restored = sessionManager.restore()
            if (restored is SessionState.SignedIn) {
                startSignedInWork()
            } else if (restored is SessionState.SignedOut && restored.message != null) {
                _authState.value = DataState.Failure(restored.message)
            }
        }
        scope.launch {
            // Emergency banner after a process restart.
            sosManager.restoreActive()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSignedInWork()
    }

    private companion object {
        const val TAG = "GuardianViewModel"
        const val SOS_LOCATION_TIMEOUT_MS = 4_000L
        const val REFRESH_LOCATION_TIMEOUT_MS = 8_000L
        const val REPORT_LOCATION_TIMEOUT_MS = 6_000L

        fun battery0(context: Context): Int? {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return level.takeIf { it in 0..100 }
        }
    }
}

/** Convenience for the screens that only need the fix value. */
val LocationUiState.fix: Pair<Double, Double>?
    get() = (this as? LocationUiState.Available)?.let { it.latitude to it.longitude }
