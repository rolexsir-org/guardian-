package com.guardian.safety.ui

import android.app.Application
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.guardian.safety.AppContainer
import com.guardian.safety.data.AppUsageEntity
import com.guardian.safety.data.AuditLogEntity
import com.guardian.safety.data.CheckinEntity
import com.guardian.safety.data.ContactEntity
import com.guardian.safety.data.FamilyGroupEntity
import com.guardian.safety.data.FamilyMemberEntity
import com.guardian.safety.data.FamilyMessageEntity
import com.guardian.safety.data.IncidentEntity
import com.guardian.safety.data.MedicalProfileEntity
import com.guardian.safety.data.SafeZoneEntity
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.remote.RealtimeClient
import com.guardian.safety.remote.RealtimeState
import com.guardian.safety.remote.RealtimeUpdate
import com.guardian.safety.remote.model.EvidenceFileDto
import com.guardian.safety.remote.model.SosEventDto
import com.guardian.safety.service.EmergencyActionResult
import com.guardian.safety.service.LocationFailure
import com.guardian.safety.service.SessionState
import com.guardian.safety.service.SmsDelivery
import com.guardian.safety.service.SosActiveState
import com.guardian.safety.service.SosTriggerResult
import com.guardian.safety.service.VoiceEmergencyManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Result of an operation the user initiated, so failures are never silent. */
sealed interface DataState {
    data object Idle : DataState
    data object Loading : DataState
    data class Success(val message: String) : DataState
    data class Failure(val message: String, val offline: Boolean = false) : DataState
}

/** Community safety event as shown in the hazards map. */
data class SafetyEventUi(
    val id: String,
    val title: String,
    val category: String,
    val severity: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val upvotes: Int,
    val reportedBy: String,
)

/** Location state: real provider fix, or the reason there is none. */
sealed interface LocationUiState {
    data object Idle : LocationUiState
    data object Locating : LocationUiState
    data class Available(val latitude: Double, val longitude: Double, val accuracyM: Float?, val at: Long) :
        LocationUiState

    data class Unavailable(val failure: LocationFailure) : LocationUiState
}

/** Live family presence, sourced from the SafetyHub websocket. */
data class FamilyPresence(
    val userId: String,
    val status: String,
    val batteryLevel: Int?,
    val lastSeenAt: Long,
)

/**
 * ViewModel for the whole app.
 *
 * Everything it exposes is either real device state (location, sensors, permissions),
 * real local state (Room) or the server's own answers. The previous implementation
 * seeded fake hazards, fabricated coordinates, invented family invite codes and
 * identity, and reported SOS success without any backend — none of that exists here.
 */
class GuardianViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val repository = container.repository
    private val sessionManager = container.sessionManager
    private val sosManager = container.sosEmergencyManager
    private val locationService = container.locationService
    private val realtimeClient = RealtimeClient(container.tokenManager, container.apiClient, viewModelScope)

    // --------------------------------------------------------------- session

    val sessionState: StateFlow<SessionState> = sessionManager.state

    private val _authState = MutableStateFlow<DataState>(DataState.Idle)
    val authState: StateFlow<DataState> = _authState.asStateFlow()

    val currentUserId: StateFlow<String?> = sessionState.map { state ->
        (state as? SessionState.SignedIn)?.session?.userId
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------ device + UI

    private val _dataState = MutableStateFlow<DataState>(DataState.Idle)
    val dataState: StateFlow<DataState> = _dataState.asStateFlow()

    private val _locationState = MutableStateFlow<LocationUiState>(LocationUiState.Idle)
    val locationState: StateFlow<LocationUiState> = _locationState.asStateFlow()

    val currentLatitude: StateFlow<Double?> = locationState
        .map { (it as? LocationUiState.Available)?.latitude }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val currentLongitude: StateFlow<Double?> = locationState
        .map { (it as? LocationUiState.Available)?.longitude }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _safetyAdvisory = MutableStateFlow("")
    val safetyAdvisory: StateFlow<String> = _safetyAdvisory.asStateFlow()

    private val _safetySelfCheck = MutableStateFlow<List<String>>(emptyList())
    val safetySelfCheck: StateFlow<List<String>> = _safetySelfCheck.asStateFlow()

    private val _realtimeState = MutableStateFlow<RealtimeState>(RealtimeState.Disconnected)
    val realtimeState: StateFlow<RealtimeState> = _realtimeState.asStateFlow()

    private val _presence = MutableStateFlow<List<FamilyPresence>>(emptyList())
    val presence: StateFlow<List<FamilyPresence>> = _presence.asStateFlow()

    // ---------------------------------------------------------------- the data

    val incidents: StateFlow<List<IncidentEntity>> = repository.getAllIncidents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val contacts: StateFlow<List<ContactEntity>> = repository.getAllContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val checkins: StateFlow<List<CheckinEntity>> = repository.getAllCheckins()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val medicalProfile: StateFlow<MedicalProfileEntity?> = repository.getMedicalProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val auditLogs: StateFlow<List<AuditLogEntity>> = repository.getAllAuditLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val familyGroups: StateFlow<List<FamilyGroupEntity>> = repository.getFamilyGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val familyGroup: StateFlow<FamilyGroupEntity?> = repository.getFamilyGroup()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val familyMembers: StateFlow<List<FamilyMemberEntity>> = repository.getAllFamilyMembers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val safeZones: StateFlow<List<SafeZoneEntity>> = repository.getAllSafeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val appUsage: StateFlow<List<AppUsageEntity>> = repository.getAllAppUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val familyMessages: StateFlow<List<FamilyMessageEntity>> = repository.getAllFamilyMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val evidenceFiles: StateFlow<List<EvidenceFileDto>> = container.artifactRepository.files
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val safetyEvents: StateFlow<List<SafetyEventUi>> = incidents
        .map { list ->
            list.filter { it.status == "Active" }.map { incident ->
                SafetyEventUi(
                    id = incident.remoteId ?: incident.id.toString(),
                    title = incident.title,
                    category = incident.category,
                    severity = incident.severity,
                    description = incident.description,
                    latitude = incident.latitude ?: Double.NaN,
                    longitude = incident.longitude ?: Double.NaN,
                    timestamp = incident.timestamp,
                    upvotes = incident.upvotes,
                    reportedBy = "Guardian community",
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _activeSosEvents = MutableStateFlow<List<SosEventDto>>(emptyList())
    val activeSosEvents: StateFlow<List<SosEventDto>> = _activeSosEvents.asStateFlow()

    private val _pendingSosCount = MutableStateFlow(0)
    val pendingSosCount: StateFlow<Int> = _pendingSosCount.asStateFlow()

    // ------------------------------------------------------------------ SOS

    val sosActive: StateFlow<SosActiveState?> = sosManager.active

    private val _lastSosResult = MutableStateFlow<SosTriggerResult?>(null)
    val lastSosResult: StateFlow<SosTriggerResult?> = _lastSosResult.asStateFlow()

    val smsDeliveries: StateFlow<Map<String, SmsDelivery>> = com.guardian.safety.service.EmergencySmsManager.deliveries

    // ------------------------------------------------------ protection toggles

    private val _isVoiceActivationEnabled = MutableStateFlow(false)
    val isVoiceActivationEnabled: StateFlow<Boolean> = _isVoiceActivationEnabled.asStateFlow()

    private val _isBackgroundMonitoringEnabled = MutableStateFlow(true)
    val isBackgroundMonitoringEnabled: StateFlow<Boolean> = _isBackgroundMonitoringEnabled.asStateFlow()

    private val _isFallGuardEnabled = MutableStateFlow(true)
    val isFallGuardEnabled: StateFlow<Boolean> = _isFallGuardEnabled.asStateFlow()

    private val _isCrashSosEnabled = MutableStateFlow(true)
    val isCrashSosEnabled: StateFlow<Boolean> = _isCrashSosEnabled.asStateFlow()

    private val _isShakeGestureEnabled = MutableStateFlow(true)
    val isShakeGestureEnabled: StateFlow<Boolean> = _isShakeGestureEnabled.asStateFlow()

    private val _isAutoSosSilenceEnabled = MutableStateFlow(false)
    val isAutoSosSilenceEnabled: StateFlow<Boolean> = _isAutoSosSilenceEnabled.asStateFlow()

    private val _isLiveGpsEnabled = MutableStateFlow(true)
    val isLiveGpsEnabled: StateFlow<Boolean> = _isLiveGpsEnabled.asStateFlow()

    private val _isGuardianProximityEnabled = MutableStateFlow(true)
    val isGuardianProximityEnabled: StateFlow<Boolean> = _isGuardianProximityEnabled.asStateFlow()

    private val _isCloudRecordEnabled = MutableStateFlow(false)
    val isCloudRecordEnabled: StateFlow<Boolean> = _isCloudRecordEnabled.asStateFlow()

    private val _isAudioBlackboxEnabled = MutableStateFlow(false)
    val isAudioBlackboxEnabled: StateFlow<Boolean> = _isAudioBlackboxEnabled.asStateFlow()

    private val _isBiometricLockEnabled = MutableStateFlow(false)
    val isBiometricLockEnabled: StateFlow<Boolean> = _isBiometricLockEnabled.asStateFlow()

    private val _isIncognitoModeEnabled = MutableStateFlow(false)
    val isIncognitoModeEnabled: StateFlow<Boolean> = _isIncognitoModeEnabled.asStateFlow()

    /**
     * Device-local parental preferences, persisted in encrypted storage. Study mode
     * blocks non-emergency family messages from this device; quiet hours only
     * suppress non-urgent prompts (safety alerts are never suppressed).
     */
    private val _studyMode = MutableStateFlow(container.securePreferences.isStudyModeEnabled())
    val isStudyModeEnabled: StateFlow<Boolean> = _studyMode.asStateFlow()

    private val _bedtimeSchedule = MutableStateFlow(container.securePreferences.isBedtimeScheduleEnabled())
    val isBedtimeScheduleEnabled: StateFlow<Boolean> = _bedtimeSchedule.asStateFlow()

    // -------------------------------------------------------------- internals

    private var voiceManager: VoiceEmergencyManager? = null
    private var sensorMonitor: com.guardian.safety.service.SensorSafetyMonitor? = null
    private var locationJob: Job? = null
    private var familySyncJob: Job? = null

    private val categoriesList = listOf("Crime", "Hazard", "Weather", "Medical", "SOS", "Community")

    private val _incidentSubscriptions = MutableStateFlow<Map<String, Boolean>>(
        categoriesList.associateWith { true },
    )
    val incidentSubscriptions: StateFlow<Map<String, Boolean>> = _incidentSubscriptions.asStateFlow()

    init {
        observeSession()
        observeLocation()
        observePendingSos()
        observePresence()
        startSensorMonitor()
        refreshSafetyAdvisory()
    }

    // ---------------------------------------------------------------- session

    private fun observeSession() {
        viewModelScope.launch {
            sessionManager.restore()
            sessionManager.state.collect { state ->
                when (state) {
                    is SessionState.SignedIn -> {
                        _authState.value = DataState.Idle
                        refreshAll()
                        startRealtime()
                    }
                    is SessionState.SignedOut -> {
                        realtimeClient.close()
                        _presence.value = emptyList()
                        container.userPresenceService.stopPresenceMonitoring()
                        if (state.storageError != null) {
                            _authState.value = DataState.Failure(
                                state.message ?: "Encrypted storage is unavailable.",
                            )
                        }
                    }
                    SessionState.Restoring -> Unit
                }
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _authState.value = DataState.Failure("Enter your email address and password.")
            return
        }
        _authState.value = DataState.Loading
        viewModelScope.launch {
            when (val result = sessionManager.signIn(email.trim(), password)) {
                is ApiResult.Success -> {
                    _authState.value = DataState.Success("Signed in as ${result.data.email}.")
                    container.userPresenceService.startPresenceMonitoring { null }
                }
                is ApiResult.Failure -> _authState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun signUp(email: String, password: String, displayName: String, phone: String?) {
        if (email.isBlank() || password.length < MIN_PASSWORD_LENGTH) {
            _authState.value = DataState.Failure(
                "Enter a valid email address and a password of at least $MIN_PASSWORD_LENGTH characters.",
            )
            return
        }
        if (displayName.isBlank()) {
            _authState.value = DataState.Failure("Enter the name your guardians should see.")
            return
        }
        _authState.value = DataState.Loading
        viewModelScope.launch {
            when (val result = sessionManager.signUp(email.trim(), password, displayName.trim(), phone)) {
                is ApiResult.Success -> {
                    _authState.value = DataState.Success("Guardian account created.")
                    container.userPresenceService.startPresenceMonitoring { null }
                }
                is ApiResult.Failure -> _authState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _authState.value = DataState.Loading
            when (val result = sessionManager.signOut()) {
                is ApiResult.Success -> {
                    repository.clearFamilyData()
                    _authState.value = DataState.Success("Signed out.")
                }
                is ApiResult.Failure -> _authState.value = DataState.Failure(
                    "Signed out on this device, but the server could not be told: ${result.error.message}",
                    offline = result.error.offline,
                )
            }
        }
    }

    // --------------------------------------------------------------- location

    private fun observeLocation() {
        viewModelScope.launch {
            _locationState.value = LocationUiState.Locating
            if (!locationService.hasLocationPermission()) {
                _locationState.value = LocationUiState.Unavailable(LocationFailure.PermissionDenied)
                return@launch
            }
            if (!locationService.areProvidersEnabled()) {
                _locationState.value = LocationUiState.Unavailable(LocationFailure.ProvidersDisabled)
                return@launch
            }
            val (location, failure) = locationService.currentLocation()
            _locationState.value = if (location != null) {
                LocationUiState.Available(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyM = location.accuracy.takeIf { location.hasAccuracy() },
                    at = location.time,
                )
            } else {
                LocationUiState.Unavailable(failure ?: LocationFailure.Unavailable)
            }
        }
    }

    /** Starts continuous updates after the user grants the permission. */
    fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        if (!locationService.hasLocationPermission()) {
            _locationState.value = LocationUiState.Unavailable(LocationFailure.PermissionDenied)
            return
        }
        locationJob = viewModelScope.launch {
            _locationState.value = LocationUiState.Locating
            if (!locationService.areProvidersEnabled()) {
                _locationState.value = LocationUiState.Unavailable(LocationFailure.ProvidersDisabled)
                return@launch
            }
            runCatching {
                locationService.locationFlow().collect { location -> onNewLocation(location) }
            }.onFailure { error ->
                Log.i(TAG, "Location updates stopped: ${error.message}")
                _locationState.value = LocationUiState.Unavailable(
                    if (error is SecurityException) LocationFailure.PermissionDenied else LocationFailure.Unavailable,
                )
            }
        }
    }

    private suspend fun onNewLocation(location: Location) {
        _locationState.value = LocationUiState.Available(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = location.accuracy.takeIf { location.hasAccuracy() },
            at = location.time,
        )
        if (_isLiveGpsEnabled.value) {
            container.locationSyncCoordinator.enqueue(location, batteryLevel = null)
        }
        repository.cacheLocation(location, batteryLevel = null)
    }

    fun refreshLocation() = observeLocation()

    fun locationFailureMessage(): String = when (val state = _locationState.value) {
        is LocationUiState.Unavailable -> when (state.failure) {
            LocationFailure.PermissionDenied -> "Location permission is not granted."
            LocationFailure.ProvidersDisabled -> "Location services are turned off in system settings."
            LocationFailure.Timeout -> "No location fix yet. Move somewhere with a clearer view of the sky."
            LocationFailure.Unavailable -> "This device cannot provide a location right now."
        }
        LocationUiState.Idle, LocationUiState.Locating -> "Getting your location…"
        is LocationUiState.Available -> "Location available."
    }

    // ------------------------------------------------------------------ SOS

    /**
     * Fires the emergency: immediately on the device, then to the backend.
     *
     * [onResult] receives the structured outcome so the UI can state exactly which
     * parts of the emergency worked (call placed, SMS queued, cloud synced) and
     * which did not.
     */
    fun triggerSos(
        triggerSource: String = "BUTTON",
        userId: String? = currentUserId.value,
        onResult: (SosTriggerResult) -> Unit = {},
    ) {
        val context = getApplication<Application>().applicationContext
        com.guardian.safety.util.HapticUtils.triggerHaptic(context, isHeavy = true)
        viewModelScope.launch {
            val location = (locationService.currentLocation().first) ?: latestCachedLocation()
            val result = sosManager.trigger(
                triggerSource = triggerSource,
                location = location,
                userId = userId,
                displayName = (sessionState.value as? SessionState.SignedIn)?.session?.displayName,
            )
            _lastSosResult.value = result
            if (result.problems.isEmpty()) {
                _dataState.value = DataState.Success("Emergency alert sent.")
            } else {
                _dataState.value = DataState.Failure(result.problems.joinToString(" "))
            }
            refreshSafetyAdvisory()
            onResult(result)
        }
    }

    private suspend fun latestCachedLocation(): Location? {
        val cached = repository.latestLocation() ?: return null
        return Location(LocationManagerCompatLike.UNKNOWN_PROVIDER).apply {
            latitude = cached.latitude
            longitude = cached.longitude
            cached.accuracy?.let { accuracy = it }
            time = cached.timestamp
        }
    }

    fun cancelSos() {
        val active = sosActive.value ?: return
        viewModelScope.launch {
            when (val result = sosManager.resolve(active.clientEventId)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Emergency resolved.")
                is ApiResult.Failure -> {
                    sosManager.cancelLocally(active.clientEventId)
                    _dataState.value = DataState.Failure(result.error.message, offline = result.error.offline)
                }
            }
            refreshSafetyAdvisory()
        }
    }

    private fun observePendingSos() {
        viewModelScope.launch {
            repository.getPendingSosCount().collect { count ->
                _pendingSosCount.value = count
            }
        }
        viewModelScope.launch { sosManager.restoreActive() }
    }

    /** Resends queued emergencies now; used by the "retry" action in the UI. */
    fun retryPendingSos() {
        viewModelScope.launch {
            val synced = sosManager.trySyncQueue()
            _dataState.value = if (synced > 0) {
                DataState.Success("$synced queued emergency alert(s) delivered.")
            } else {
                DataState.Failure("Nothing could be delivered yet. Guardian will keep retrying in the background.")
            }
        }
    }

    /** True when this build has a real Guardian backend configured. */
    val isBackendConfigured: Boolean get() = container.isCloudConfigured

    val backendConfigurationError: String? get() = container.configurationError

    /** Called when the OS reports the result of the notification-permission prompt. */
    fun onNotificationPermissionResult(granted: Boolean) {
        if (!granted) {
            _dataState.value = DataState.Failure(
                "Notifications are blocked, so Guardian cannot show emergency alerts. You can enable them in system settings.",
            )
        }
    }

    /** Re-selects an emergency that survived process death. */
    fun restoreActiveEmergency() {
        viewModelScope.launch { sosManager.restoreActive() }
    }

    fun resolveSos(remoteId: String, note: String?) {
        viewModelScope.launch {
            when (val result = repository.resolveSos(remoteId, note)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Emergency marked resolved.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun acknowledgeSos(remoteId: String) {
        viewModelScope.launch {
            when (val result = repository.acknowledgeSos(remoteId)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Emergency acknowledged.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    // -------------------------------------------------------------- realtime

    private fun startRealtime() {
        realtimeClient.listener = { update ->
            when (update) {
                is RealtimeUpdate.Connected -> {
                    _realtimeState.value = RealtimeState.Connected(update.scope)
                    _presence.value = update.presence.map { it.toPresence() }
                    _activeSosEvents.value = update.activeSos
                }
                is RealtimeUpdate.SyncGap -> {
                    _presence.value = update.presence.map { it.toPresence() }
                    _activeSosEvents.value = update.activeSos
                    // A gap means frames were missed: reload durable state from the API.
                    viewModelScope.launch { refreshAll(silent = true) }
                }
                is RealtimeUpdate.Event -> handleRealtimeEvent(update)
                is RealtimeUpdate.Error -> Log.i(TAG, "Realtime error: ${update.code}")
            }
        }
        viewModelScope.launch { realtimeClient.state.collect { _realtimeState.value = it } }
        familySyncJob?.cancel()
        familySyncJob = viewModelScope.launch {
            repository.getFamilyGroup().collect { group ->
                if (group != null) {
                    realtimeClient.subscribe("/v1/realtime/family/${group.remoteId}")
                } else {
                    realtimeClient.close()
                }
            }
        }
    }

    private fun observePresence() {
        viewModelScope.launch {
            realtimeClient.presence.collect { list ->
                _presence.value = list.map { it.toPresence() }
            }
        }
        viewModelScope.launch {
            realtimeClient.activeSos.collect { _activeSosEvents.value = it }
        }
    }

    private fun handleRealtimeEvent(update: RealtimeUpdate.Event) {
        when (update.event.kind) {
            "FAMILY_MESSAGE" -> viewModelScope.launch {
                familyGroup.value?.let { repository.syncFamilyMessages(it.remoteId) }
            }
            "LOCATION_SHARE" -> viewModelScope.launch { refreshFamily() }
            "SOS_CREATED", "SOS_UPDATED" -> viewModelScope.launch { refreshActiveSos() }
            else -> Unit
        }
    }

    private fun com.guardian.safety.remote.model.RealtimePresenceDto.toPresence() = FamilyPresence(
        userId = userId,
        status = status,
        batteryLevel = batteryLevel,
        lastSeenAt = lastSeenAt,
    )

    // ---------------------------------------------------------- data refresh

    /** Reloads everything the user can see. Individual failures are reported. */
    fun refreshAll(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _dataState.value = DataState.Loading
            val problems = mutableListOf<String>()

            collectFailure(repository.syncContacts())?.let {
                problems += "Contacts: $it"
            }
            collectFailure(repository.syncFamily())?.let {
                problems += "Family: $it"
            }
            familyGroup.value?.let { group ->
                collectFailure(repository.syncFamilyMessages(group.remoteId))?.let {
                    problems += "Messages: $it"
                }
            }
            nearbySafetyEventsProblem()?.let { problems += it }
            refreshActiveSos()
            runCatching { container.artifactRepository.refresh() }

            _dataState.value = if (problems.isEmpty()) {
                if (silent) DataState.Idle else DataState.Success("Up to date.")
            } else {
                DataState.Failure(problems.joinToString(" "))
            }
            refreshSafetyAdvisory()
        }
    }

    /** Refreshes community safety events; returns a message when it could not. */
    private suspend fun nearbySafetyEventsProblem(): String? {
        val location = (locationState.value as? LocationUiState.Available)?.let { available ->
            Location(LocationManagerCompatLike.UNKNOWN_PROVIDER).apply {
                latitude = available.latitude
                longitude = available.longitude
                time = available.at
            }
        }
        if (location == null) {
            // No fix yet: an empty hazard list is the honest state, not an error to shout about.
            return null
        }
        return when (val result = repository.refreshNearbySafetyEvents(location)) {
            is ApiResult.Success -> null
            is ApiResult.Failure -> if (result.error.offline) null else "Safety events: ${result.error.message}"
        }
    }

    private suspend fun refreshActiveSos() {
        val groupId = familyGroup.value?.remoteId ?: return
        when (val result = repository.activeFamilySos(groupId)) {
            is ApiResult.Success -> _activeSosEvents.value = result.data
            is ApiResult.Failure -> Unit
        }
    }

    fun refreshFamily() {
        viewModelScope.launch { refreshFamilyMembers() }
    }

    private suspend fun refreshFamilyMembers() {
        when (val result = repository.syncFamily()) {
            is ApiResult.Success -> _dataState.value = DataState.Idle
            is ApiResult.Failure -> _dataState.value = DataState.Failure(
                result.error.message,
                offline = result.error.offline,
            )
        }
    }

    /** Surfaces a device-level problem (permission, telephony) to the user. */
    fun reportActionProblem(message: String) {
        _dataState.value = DataState.Failure(message)
    }

    fun clearDataMessage() {
        _dataState.value = DataState.Idle
        _authState.value = DataState.Idle
    }

    private fun <T> collectFailure(result: ApiResult<T>): String? =
        (result as? ApiResult.Failure)?.error?.takeIf { !it.offline }?.message

    // ----------------------------------------------------------- safety events

    fun reportSafetyEvent(
        title: String,
        category: String,
        severity: String,
        description: String,
    ) {
        viewModelScope.launch {
            val state = locationState.value
            val location = when (state) {
                is LocationUiState.Available -> Location(LocationManagerCompatLike.UNKNOWN_PROVIDER).apply {
                    latitude = state.latitude
                    longitude = state.longitude
                    time = state.at
                }
                else -> latestCachedLocation()
            }
            if (location == null) {
                _dataState.value = DataState.Failure(
                    "A safety event needs a real location. ${locationFailureMessage()}",
                )
                return@launch
            }
            _dataState.value = DataState.Loading
            when (
                val result = repository.reportSafetyEvent(
                    title = title,
                    category = category,
                    severity = severity,
                    description = description,
                    location = location,
                )
            ) {
                is ApiResult.Success -> {
                    _dataState.value = DataState.Success("Safety event reported.")
                    refreshAll(silent = true)
                }
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun confirmSafetyEvent(eventId: String) {
        viewModelScope.launch {
            val incident = incidents.value.firstOrNull { (it.remoteId ?: it.id.toString()) == eventId }
            if (incident == null) {
                _dataState.value = DataState.Failure("That safety event is no longer in your list.")
                return@launch
            }
            when (val result = repository.confirmIncident(incident.id)) {
                is ApiResult.Success -> refreshAll(silent = true)
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    /** Closes a safety event the user reported. Requires server confirmation. */
    fun resolveSafetyEvent(eventId: String) {
        viewModelScope.launch {
            val incident = incidents.value.firstOrNull { (it.remoteId ?: it.id.toString()) == eventId }
            if (incident == null) {
                _dataState.value = DataState.Failure("That safety event is no longer in your list.")
                return@launch
            }
            when (val result = repository.resolveSafetyEvent(incident)) {
                is ApiResult.Success -> {
                    _dataState.value = DataState.Success("Safety event marked resolved.")
                    refreshAll(silent = true)
                }
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    // -------------------------------------------------------------- contacts

    fun addContact(name: String, phone: String, relationship: String, isVerified: Boolean = false) {
        viewModelScope.launch {
            when (val result = repository.addContact(name, phone, relationship, isVerified)) {
                is ApiResult.Success -> {
                    _dataState.value = DataState.Success("$name added to your emergency contacts.")
                    logAudit("CONTACT", "Added emergency contact $name", "SUCCESS")
                }
                is ApiResult.Failure -> {
                    _dataState.value = DataState.Failure(result.error.message, offline = result.error.offline)
                    logAudit("CONTACT", "Could not sync emergency contact $name: ${result.error.message}", "QUEUED")
                }
            }
        }
    }

    fun updateContact(contact: ContactEntity) {
        viewModelScope.launch {
            when (val result = repository.updateContact(contact)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Contact updated.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch {
            when (val result = repository.deleteContact(contact)) {
                is ApiResult.Success -> {
                    _dataState.value = DataState.Success("${contact.name} removed.")
                    logAudit("CONTACT", "Removed emergency contact ${contact.name}", "SUCCESS")
                }
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun syncContactsNow() {
        viewModelScope.launch {
            when (val result = repository.syncContacts()) {
                is ApiResult.Success -> _dataState.value = DataState.Success("${result.data} contact(s) synced.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    // --------------------------------------------------------------- check-ins

    fun startCheckin(durationMinutes: Int, note: String) {
        viewModelScope.launch {
            when (val result = repository.startCheckin(durationMinutes, note)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Check-in started.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun updateCheckinStatus(checkin: CheckinEntity, newStatus: String) {
        viewModelScope.launch {
            when (val result = repository.completeCheckin(checkin.id, newStatus)) {
                is ApiResult.Success -> _dataState.value = DataState.Success("Check-in updated.")
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    // ---------------------------------------------------------------- medical

    fun updateMedicalProfile(
        name: String,
        bloodGroup: String,
        allergies: String,
        medicalConditions: String,
        medications: String,
        emergencyNotes: String,
        doctorContact: String,
        insuranceInfo: String,
    ) {
        viewModelScope.launch {
            repository.saveMedicalProfile(
                MedicalProfileEntity(
                    id = 1L,
                    name = name,
                    bloodGroup = bloodGroup,
                    allergies = allergies,
                    medicalConditions = medicalConditions,
                    medications = medications,
                    emergencyNotes = emergencyNotes,
                    doctorContact = doctorContact,
                    insuranceInfo = insuranceInfo,
                ),
            )
            _dataState.value = DataState.Success("Medical profile saved on this device.")
        }
    }

    // ------------------------------------------------------------------ family

    fun createFamilyGroup(groupName: String, ownerName: String) {
        if (groupName.isBlank()) {
            _dataState.value = DataState.Failure("Give the family group a name.")
            return
        }
        viewModelScope.launch {
            _dataState.value = DataState.Loading
            when (val result = repository.createFamilyGroup(groupName.trim())) {
                is ApiResult.Success -> {
                    _dataState.value = DataState.Success("Family group created.")
                    refreshFamily()
                    realtimeClient.subscribe("/v1/realtime/family/${result.data.remoteId}")
                }
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun createInvite(role: String, expiresInMinutes: Int = 60, onCode: (String) -> Unit = {}) {
        val group = familyGroup.value
        if (group == null) {
            _dataState.value = DataState.Failure("Create a family group before inviting anyone.")
            return
        }
        viewModelScope.launch {
            when (val result = repository.createInvite(group.remoteId, role, expiresInMinutes)) {
                is ApiResult.Success -> {
                    _dataState.value = DataState.Success("Invite code ready. It is valid for $expiresInMinutes minutes.")
                    onCode(result.data)
                }
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message)
            }
        }
    }

    fun joinFamilyGroup(inviteCode: String, memberName: String, role: String) {
        if (inviteCode.isBlank()) {
            _dataState.value = DataState.Failure("Enter the invite code your family member shared.")
            return
        }
        viewModelScope.launch {
            _dataState.value = DataState.Loading
            when (val result = repository.joinFamilyGroup(inviteCode.trim())) {
                is ApiResult.Success -> {
                    _dataState.value = DataState.Success("Joined ${result.data.groupName}.")
                    refreshFamily()
                    realtimeClient.subscribe("/v1/realtime/family/${result.data.remoteId}")
                }
                is ApiResult.Failure -> _dataState.value = DataState.Failure(
                    result.error.message,
                    offline = result.error.offline,
                )
            }
        }
    }

    fun removeFamilyMember(member: FamilyMemberEntity) {
        val groupId = member.groupRemoteId
        if (groupId.isBlank()) {
            _dataState.value = DataState.Failure("That member is not linked to a synced family group.")
            return
        }
        viewModelScope.launch {
            when (val result = repository.removeFamilyMember(groupId, member.remoteMemberId)) {
                is ApiResult.Success -> {
                    _dataState.value = DataState.Success("${member.name} removed from the group.")
                    refreshFamily()
                }
                is ApiResult.Failure -> _dataState.value = DataState.Failure(result.error.message)
            }
        }
    }

    fun sendFamilyMessage(text: String, isEmergency: Boolean, senderName: String) {
        val group = familyGroup.value
        if (group == null) {
            _dataState.value = DataState.Failure("Join a family group before sending messages.")
            return
        }
        if (text.isBlank()) return
        if (_studyMode.value && !isEmergency) {
            _dataState.value = DataState.Failure(
                "Study mode is on, so non-emergency messages are blocked. Emergency broadcasts still send.",
            )
            return
        }
        viewModelScope.launch {
            when (val result = repository.sendFamilyMessage(group.remoteId, text, isEmergency)) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure -> {
                    _dataState.value = DataState.Failure(result.error.message, offline = result.error.offline)
                    if (isEmergency) {
                        logAudit("SOS", "Family emergency broadcast could not be delivered", "QUEUED")
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- safe zones

    fun addSafeZone(name: String, latitude: Double, longitude: Double, radius: Float, type: String) {
        viewModelScope.launch {
            repository.addSafeZone(
                SafeZoneEntity(
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = radius,
                    zoneType = type,
                ),
            )
            _dataState.value = DataState.Success("Safe zone '$name' saved on this device.")
            logAudit("GEOFENCE", "Created safe zone $name", "SUCCESS")
        }
    }

    fun deleteSafeZone(zone: SafeZoneEntity) {
        viewModelScope.launch { repository.deleteSafeZone(zone) }
    }

    // -------------------------------------------------------------- app limits

    fun updateAppLimit(appId: Long, limitMinutes: Int, isRestricted: Boolean) {
        viewModelScope.launch {
            appUsage.value.find { it.appId == appId }?.let { usage ->
                repository.updateAppUsage(
                    usage.copy(dailyLimitMinutes = limitMinutes, isRestricted = isRestricted),
                )
                _dataState.value = DataState.Success("${usage.appName} limit updated on this device.")
            }
        }
    }

    fun toggleStudyMode(enabled: Boolean) {
        _studyMode.value = enabled
        container.securePreferences.setStudyModeEnabled(enabled)
        logAudit("PROTECTION", "Study mode set to $enabled", "SUCCESS")
    }

    fun toggleBedtimeSchedule(enabled: Boolean) {
        _bedtimeSchedule.value = enabled
        container.securePreferences.setBedtimeScheduleEnabled(enabled)
        logAudit("PROTECTION", "Quiet hours set to $enabled", "SUCCESS")
    }

    fun toggleIncidentSubscription(category: String, subscribed: Boolean) {
        _incidentSubscriptions.value = _incidentSubscriptions.value + (category to subscribed)
        container.securePreferences.setIncidentSubscribed(category, subscribed)
    }

    // ------------------------------------------------------------- protection

    fun toggleVoiceActivation(enabled: Boolean, context: Context) {
        _isVoiceActivationEnabled.value = enabled
        if (enabled) {
            if (!hasAudioPermission(context)) {
                _isVoiceActivationEnabled.value = false
                _dataState.value = DataState.Failure(
                    "Voice activation needs microphone access for on-device phrase detection.",
                )
                return
            }
            if (voiceManager == null) {
                voiceManager = VoiceEmergencyManager(context) { phrase, classification, advice ->
                    triggerSos("VOICE")
                    viewModelScope.launch {
                        _dataState.value = DataState.Success("Voice trigger detected: $phrase")
                        repository.logAudit(
                            triggerType = "VOICE",
                            actionDetails = "Voice phrase detected and classified as $classification",
                            status = "TRIGGERED",
                        )
                        _safetyAdvisory.value = advice
                    }
                }
            }
            voiceManager?.startListening {
                _isVoiceActivationEnabled.value = false
                _dataState.value = DataState.Failure("Voice detection stopped: microphone could not be used.")
            }
        } else {
            voiceManager?.stopListening()
            voiceManager = null
        }
        logAudit("PROTECTION", "Voice activation set to $enabled", "SUCCESS")
    }

    private fun hasAudioPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun toggleBackgroundMonitoring(enabled: Boolean) {
        _isBackgroundMonitoringEnabled.value = enabled
        if (enabled) {
            container.scheduleBackgroundWork()
        } else {
            androidx.work.WorkManager.getInstance(getApplication())
                .cancelUniqueWork(com.guardian.safety.worker.RiskAreaGeofenceWorker.UNIQUE_WORK_NAME)
        }
        logAudit("PROTECTION", "Background safety monitoring set to $enabled", "SUCCESS")
    }

    fun toggleFallGuard(enabled: Boolean) {
        _isFallGuardEnabled.value = enabled
        sensorMonitor?.updateFlags(enabled, _isCrashSosEnabled.value, _isShakeGestureEnabled.value)
        logAudit("PROTECTION", "Fall guard set to $enabled", "SUCCESS")
    }

    fun toggleCrashSos(enabled: Boolean) {
        _isCrashSosEnabled.value = enabled
        sensorMonitor?.updateFlags(_isFallGuardEnabled.value, enabled, _isShakeGestureEnabled.value)
        logAudit("PROTECTION", "Crash detection set to $enabled", "SUCCESS")
    }

    fun toggleShakeGesture(enabled: Boolean) {
        _isShakeGestureEnabled.value = enabled
        sensorMonitor?.updateFlags(_isFallGuardEnabled.value, _isCrashSosEnabled.value, enabled)
        logAudit("PROTECTION", "Shake gesture set to $enabled", "SUCCESS")
    }

    fun toggleAutoSosSilence(enabled: Boolean) {
        _isAutoSosSilenceEnabled.value = enabled
        logAudit("PROTECTION", "Automatic SOS on silence set to $enabled", "SUCCESS")
    }

    fun toggleLiveGps(enabled: Boolean) {
        _isLiveGpsEnabled.value = enabled
        if (enabled) startLocationUpdates() else locationJob?.cancel()
        logAudit("PROTECTION", "Live location sharing set to $enabled", "SUCCESS")
    }

    fun toggleGuardianProximity(enabled: Boolean) {
        _isGuardianProximityEnabled.value = enabled
        logAudit("PROTECTION", "Guardian proximity alerts set to $enabled", "SUCCESS")
    }

    fun toggleCloudRecord(enabled: Boolean) {
        _isCloudRecordEnabled.value = enabled
        if (!container.isCloudConfigured && enabled) {
            _dataState.value = DataState.Failure(
                "Evidence storage needs a configured Guardian backend. ${container.configurationError ?: ""}".trim(),
            )
        }
        logAudit("PROTECTION", "Cloud evidence vault set to $enabled", "SUCCESS")
    }

    fun toggleAudioBlackbox(enabled: Boolean) {
        if (enabled && !hasAudioPermission(getApplication())) {
            _isAudioBlackboxEnabled.value = false
            _dataState.value = DataState.Failure("Audio recording needs microphone access.")
            return
        }
        _isAudioBlackboxEnabled.value = enabled
        logAudit("PROTECTION", "Emergency audio capture set to $enabled", "SUCCESS")
    }

    fun toggleBiometricLock(enabled: Boolean) {
        _isBiometricLockEnabled.value = enabled
        logAudit("PROTECTION", "Biometric lock set to $enabled", "SUCCESS")
    }

    fun toggleIncognitoMode(enabled: Boolean) {
        _isIncognitoModeEnabled.value = enabled
        logAudit("PROTECTION", "Incognito mode set to $enabled", "SUCCESS")
    }

    // ------------------------------------------------------- sensors & voice

    fun triggerVoiceSos(phrase: String = "voice command") {
        triggerSos("VOICE") { result ->
            viewModelScope.launch {
                repository.logAudit(
                    triggerType = "VOICE",
                    actionDetails = "Voice SOS triggered by \"$phrase\"",
                    status = if (result.cloudSynced) "SUCCESS" else "QUEUED",
                )
            }
        }
    }

    private fun startSensorMonitor() {
        sensorMonitor = com.guardian.safety.service.SensorSafetyMonitor(
            context = getApplication(),
            onFallDetected = {
                triggerSos("FALL")
                viewModelScope.launch {
                    repository.logAudit("FALL", "Fall impact detected by device sensors", "TRIGGERED")
                }
            },
            onCrashDetected = {
                triggerSos("CRASH")
                viewModelScope.launch {
                    repository.logAudit("CRASH", "Collision impact detected by device sensors", "TRIGGERED")
                }
            },
            onShakeDetected = {
                triggerSos("SHAKE")
                viewModelScope.launch {
                    repository.logAudit("SHAKE", "Shake gesture detected", "TRIGGERED")
                }
            },
        ).apply {
            startMonitoring(_isFallGuardEnabled.value, _isCrashSosEnabled.value, _isShakeGestureEnabled.value)
        }
    }

    // -------------------------------------------------- advisory & self-check

    /**
     * Recomputes the safety advisory from actual device and account state: no
     * canned text, no random tips, no pretence of an AI service.
     */
    fun refreshSafetyAdvisory() {
        viewModelScope.launch {
            val notes = mutableListOf<String>()
            val context = getApplication<Application>()
            val signedIn = sessionState.value is SessionState.SignedIn

            if (!container.isCloudConfigured) {
                notes += "This build has no Guardian backend configured, so family sharing and cloud alerts are off."
            } else if (!signedIn) {
                notes += "Sign in to sync emergencies with your guardians."
            }
            when (val state = locationState.value) {
                is LocationUiState.Unavailable -> notes += locationFailureMessage()
                LocationUiState.Idle, LocationUiState.Locating -> notes += "Getting your location…"
                is LocationUiState.Available -> Unit
            }
            if (contacts.value.isEmpty()) {
                notes += "Add at least one emergency contact so SOS can reach someone."
            }
            if (!hasAudioPermission(context) && _isVoiceActivationEnabled.value) {
                notes += "Voice activation is on but microphone access is not granted."
            }
            if (pendingSosCount.value > 0) {
                notes += "${pendingSosCount.value} emergency alert(s) are still waiting to reach the Guardian service."
            }
            _safetyAdvisory.value = if (notes.isEmpty()) {
                "Everything Guardian needs is in place: location, contacts and secure cloud sync are ready."
            } else {
                notes.joinToString(" ")
            }
        }
    }

    /**
     * Real readiness check used by the "Run safety self-check" action: it inspects
     * the device instead of pretending to simulate an emergency.
     */
    fun runSafetySelfCheck() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val results = mutableListOf<String>()
            results += if (locationService.hasLocationPermission()) {
                "Location permission: granted"
            } else {
                "Location permission: missing — tap to grant"
            }
            results += if (locationService.areProvidersEnabled()) {
                "Location services: on"
            } else {
                "Location services: off in system settings"
            }
            results += when (val state = locationState.value) {
                is LocationUiState.Available -> "Location fix: ${state.latitude}, ${state.longitude}"
                else -> "Location fix: ${locationFailureMessage()}"
            }
            results += if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                "Call permission: granted"
            } else {
                "Call permission: not granted — the dialer will be used instead"
            }
            results += if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                "SMS permission: granted"
            } else {
                "SMS permission: not granted — messages open in your app instead"
            }
            val contactCount = contacts.value.count { it.phone.isNotBlank() }
            results += if (contactCount > 0) {
                "Emergency contacts: $contactCount with phone numbers"
            } else {
                "Emergency contacts: none saved"
            }
            results += when (val result = repository.syncContacts()) {
                is ApiResult.Success -> "Cloud sync: reachable"
                is ApiResult.Failure -> if (result.error.offline) {
                    "Cloud sync: offline — alerts are stored and sent later"
                } else {
                    "Cloud sync: ${result.error.message}"
                }
            }
            _safetySelfCheck.value = results
            refreshSafetyAdvisory()
        }
    }

    private fun logAudit(type: String, details: String, status: String) {
        viewModelScope.launch {
            repository.logAudit(type, details, status, currentUserId.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        realtimeClient.close()
        voiceManager?.stopListening()
        sensorMonitor?.stopMonitoring()
        container.userPresenceService.stopPresenceMonitoring()
    }

    companion object {
        private const val TAG = "GuardianViewModel"
        const val MIN_PASSWORD_LENGTH = 10
    }
}

/** Small helper so a [Location] can be constructed around a cached fix. */
private object LocationManagerCompatLike {
    const val UNKNOWN_PROVIDER = "guardian-cache"
}
