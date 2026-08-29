package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CheckinEntity
import com.example.data.ContactEntity
import com.example.data.GuardianDatabase
import com.example.data.GuardianRepository
import com.example.data.IncidentEntity
import com.example.data.MedicalProfileEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.example.service.UserPresenceService
import androidx.work.*
import com.example.data.SosQueueEntity
import com.example.data.LocationCacheEntity
import com.example.data.AuditLogEntity
import com.example.data.FamilyGroupEntity
import com.example.data.FamilyMemberEntity
import com.example.data.SafeZoneEntity
import com.example.data.AppUsageEntity
import com.example.data.FamilyMessageEntity
import com.example.worker.SosSyncWorker
import com.example.service.SecureEncryptedPreferences
import com.example.service.EmergencySmsManager
import com.example.service.LocationService
import kotlinx.coroutines.flow.firstOrNull

data class ChatMessage(
    val sender: String, // "User" or "Guardian Sentinel"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class GuardianViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: GuardianRepository
    val incidents: StateFlow<List<IncidentEntity>>
    val contacts: StateFlow<List<ContactEntity>>
    val checkins: StateFlow<List<CheckinEntity>>
    val medicalProfile: StateFlow<MedicalProfileEntity?>
    val auditLogs: StateFlow<List<AuditLogEntity>>
    val familyGroup: StateFlow<FamilyGroupEntity?>
    val familyMembers: StateFlow<List<FamilyMemberEntity>>
    val safeZones: StateFlow<List<SafeZoneEntity>>
    val appUsage: StateFlow<List<AppUsageEntity>>
    val familyMessages: StateFlow<List<FamilyMessageEntity>>

    private val _contactStatuses = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val contactStatuses: StateFlow<Map<Long, Boolean>> = _contactStatuses.asStateFlow()

    private val _firebaseHazards = MutableStateFlow<List<com.example.data.FirebaseHazard>>(emptyList())
    val firebaseHazards: StateFlow<List<com.example.data.FirebaseHazard>> = _firebaseHazards.asStateFlow()

    private var databaseRef: DatabaseReference? = null
    private var valueEventListener: ValueEventListener? = null

    private var hazardsDatabaseRef: DatabaseReference? = null
    private var hazardsEventListener: ValueEventListener? = null
    private val userPresenceService = UserPresenceService()

    // SOS State
    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive.asStateFlow()

    private val _sosCountdown = MutableStateFlow(3)
    val sosCountdown: StateFlow<Int> = _sosCountdown.asStateFlow()

    // Safety Alert Log State
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage("Guardian Sentinel", "Guardian Safety Sentinel active. Monitoring hardware sensors, location cache, and emergency triggers.")
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

    private val _aiRecommendation = MutableStateFlow("Guardian Safety Protocol: Live location sharing active with trusted guardians. Stay aware of your surroundings.")
    val aiRecommendation: StateFlow<String> = _aiRecommendation.asStateFlow()

    private val _unusualMovementDetected = MutableStateFlow(false)
    val unusualMovementDetected: StateFlow<Boolean> = _unusualMovementDetected.asStateFlow()

    private val _isVoiceActivationEnabled = MutableStateFlow(false)
    val isVoiceActivationEnabled: StateFlow<Boolean> = _isVoiceActivationEnabled.asStateFlow()

    private val _isBackgroundAiEnabled = MutableStateFlow(true)
    val isBackgroundAiEnabled: StateFlow<Boolean> = _isBackgroundAiEnabled.asStateFlow()

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

    private val _isAudioBlackboxEnabled = MutableStateFlow(true)
    val isAudioBlackboxEnabled: StateFlow<Boolean> = _isAudioBlackboxEnabled.asStateFlow()

    private val _isBiometricLockEnabled = MutableStateFlow(false)
    val isBiometricLockEnabled: StateFlow<Boolean> = _isBiometricLockEnabled.asStateFlow()

    private val _isIncognitoModeEnabled = MutableStateFlow(false)
    val isIncognitoModeEnabled: StateFlow<Boolean> = _isIncognitoModeEnabled.asStateFlow()

    private var voiceManager: com.example.service.VoiceEmergencyManager? = null
    private var sensorMonitor: com.example.service.SensorSafetyMonitor? = null
    private val locationService = LocationService(application)

    private val _currentLatitude = MutableStateFlow(37.7749)
    val currentLatitude: StateFlow<Double> = _currentLatitude.asStateFlow()

    private val _currentLongitude = MutableStateFlow(-122.4194)
    val currentLongitude: StateFlow<Double> = _currentLongitude.asStateFlow()

    private val securePrefs = SecureEncryptedPreferences.getInstance(application)

    init {
        val dao = GuardianDatabase.getDatabase(application).guardianDao()
        repository = GuardianRepository(dao)

        // Start location tracking
        viewModelScope.launch {
            try {
                locationService.locationFlow().collect { location ->
                    _currentLatitude.value = location.latitude
                    _currentLongitude.value = location.longitude
                }
            } catch (e: Exception) {
                // Permissions not yet granted
            }
        }

        sensorMonitor = com.example.service.SensorSafetyMonitor(
            context = application,
            onFallDetected = {
                triggerSos("FALL_GUARD")
                viewModelScope.launch {
                    val msg = ChatMessage("Guardian Sentinel", "🚨 Fall impact detected! Fall Guard triggered automatic SOS broadcast.")
                    _chatMessages.value = _chatMessages.value + msg
                }
            },
            onCrashDetected = {
                triggerSos("CRASH_SOS")
                viewModelScope.launch {
                    val msg = ChatMessage("Guardian Sentinel", "🚗 Vehicle collision impact detected! Crash SOS triggered automatic beacon broadcast.")
                    _chatMessages.value = _chatMessages.value + msg
                }
            },
            onShakeDetected = {
                triggerSos("SHAKE_GESTURE")
                viewModelScope.launch {
                    val msg = ChatMessage("Guardian Sentinel", "📳 Vigorous device shake gesture detected! Emergency SOS activated.")
                    _chatMessages.value = _chatMessages.value + msg
                }
            }
        ).apply {
            startMonitoring(_isFallGuardEnabled.value, _isCrashSosEnabled.value, _isShakeGestureEnabled.value)
        }

        incidents = repository.incidents.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        contacts = repository.contacts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        checkins = repository.checkins.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        medicalProfile = repository.medicalProfile.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

        auditLogs = repository.auditLogs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        familyGroup = repository.familyGroup.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

        familyMembers = repository.familyMembers.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        safeZones = repository.safeZones.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        appUsage = repository.appUsage.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        familyMessages = repository.familyMessages.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        try {
            databaseRef = FirebaseDatabase.getInstance().getReference("contact_status")
            valueEventListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val map = mutableMapOf<Long, Boolean>()
                    for (child in snapshot.children) {
                        val contactId = child.key?.toLongOrNull()
                        val isOnline = child.getValue(Boolean::class.java) ?: false
                        if (contactId != null) {
                            map[contactId] = isOnline
                        }
                    }
                    _contactStatuses.value = map
                }

                override fun onCancelled(error: DatabaseError) {
                    // ignore
                }
            }
            databaseRef?.addValueEventListener(valueEventListener!!)
        } catch (e: Exception) {
            _contactStatuses.value = emptyMap()
        }

        userPresenceService.startPresenceMonitoring()
        observeFirebaseHazards()
    }

    private fun observeFirebaseHazards() {
        try {
            hazardsDatabaseRef = FirebaseDatabase.getInstance().getReference("safety_incidents")
            hazardsEventListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val list = mutableListOf<com.example.data.FirebaseHazard>()
                    for (child in snapshot.children) {
                        val hazard = child.getValue(com.example.data.FirebaseHazard::class.java)
                        if (hazard != null) {
                            val hazardWithId = hazard.copy(id = child.key ?: "")
                            list.add(hazardWithId)
                        }
                    }
                    if (list.isEmpty()) {
                        seedInitialHazards()
                    } else {
                        _firebaseHazards.value = list
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    // ignore
                }
            }
            hazardsDatabaseRef?.addValueEventListener(hazardsEventListener!!)
        } catch (e: Exception) {
            _firebaseHazards.value = emptyList()
        }
    }

    private fun seedInitialHazards() {
        val initial = listOf(
            com.example.data.FirebaseHazard(id = "h1", title = "Road Debris / Pothole Hazard", category = "Hazard", severity = "Warning", description = "Large debris obstructing right lane near 5th Ave.", latitude = 37.7749, longitude = -122.4194, timestamp = System.currentTimeMillis() - 600000, upvotes = 14, reportedBy = "Transit Sentinel"),
            com.example.data.FirebaseHazard(id = "h2", title = "Suspicious Activity Reported", category = "Crime", severity = "Critical", description = "Unlit alleyway activity reported by multiple residents.", latitude = 37.7789, longitude = -122.4150, timestamp = System.currentTimeMillis() - 1200000, upvotes = 28, reportedBy = "Neighborhood Watch"),
            com.example.data.FirebaseHazard(id = "h3", title = "Severe Weather Flooding", category = "Weather", severity = "Warning", description = "Street flooding at low-lying intersection.", latitude = 37.7710, longitude = -122.4240, timestamp = System.currentTimeMillis() - 300000, upvotes = 9, reportedBy = "Weather Bot"),
            com.example.data.FirebaseHazard(id = "h4", title = "Medical Emergency Response", category = "Medical", severity = "Critical", description = "First responders on scene at commercial plaza.", latitude = 37.7760, longitude = -122.4120, timestamp = System.currentTimeMillis() - 180000, upvotes = 19, reportedBy = "Paramedic Unit 4")
        )
        hazardsDatabaseRef?.let { ref ->
            for (h in initial) {
                ref.child(h.id).setValue(h)
            }
        }
    }

    fun reportFirebaseHazard(title: String, category: String, severity: String, description: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            try {
                val ref = hazardsDatabaseRef ?: FirebaseDatabase.getInstance().getReference("safety_incidents")
                val key = ref.push().key ?: System.currentTimeMillis().toString()
                val hazard = com.example.data.FirebaseHazard(
                    id = key,
                    title = title,
                    category = category,
                    severity = severity,
                    description = description,
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = System.currentTimeMillis(),
                    upvotes = 1,
                    reportedBy = "Current User"
                )
                ref.child(key).setValue(hazard)
                logAudit("HAZARD", "Reported Firebase Realtime hazard: $title ($category)")
            } catch (e: Exception) {
                Log.e("GuardianViewModel", "Error reporting Firebase hazard", e)
            }
        }
    }

    fun upvoteFirebaseHazard(hazardId: String) {
        viewModelScope.launch {
            try {
                val ref = hazardsDatabaseRef ?: FirebaseDatabase.getInstance().getReference("safety_incidents")
                val itemRef = ref.child(hazardId).child("upvotes")
                itemRef.get().addOnSuccessListener { snapshot ->
                    val current = snapshot.getValue(Int::class.java) ?: 1
                    itemRef.setValue(current + 1)
                }
            } catch (e: Exception) {
                Log.e("GuardianViewModel", "Error upvoting Firebase hazard", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        databaseRef?.let { ref ->
            valueEventListener?.let { ref.removeEventListener(it) }
        }
        hazardsDatabaseRef?.let { ref ->
            hazardsEventListener?.let { ref.removeEventListener(it) }
        }
        userPresenceService.stopPresenceMonitoring()
    }

    fun triggerSos(triggerSource: String = "BUTTON") {
        _isSosActive.value = true
        val context = getApplication<Application>().applicationContext
        com.example.util.HapticUtils.triggerHaptic(context, isHeavy = true)

        locationService.getCurrentLocation(
            onSuccess = { lat, lng ->
                _currentLatitude.value = lat
                _currentLongitude.value = lng
                executeSosBroadcast(context, triggerSource, lat, lng)
            },
            onError = {
                executeSosBroadcast(context, triggerSource, _currentLatitude.value, _currentLongitude.value)
            }
        )
    }

    private fun executeSosBroadcast(context: android.content.Context, triggerSource: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            repository.insertAuditLog(
                AuditLogEntity(
                    timestamp = System.currentTimeMillis(),
                    triggerType = triggerSource,
                    actionDetails = "Emergency triggered via $triggerSource at ($lat, $lng)",
                    status = "TRIGGERED"
                )
            )

            val sosEntity = SosQueueEntity(
                userId = 1L,
                timestamp = System.currentTimeMillis(),
                latitude = lat,
                longitude = lng,
                batteryLevel = 90,
                networkStatus = "ACTIVE",
                deviceInfo = android.os.Build.MODEL,
                triggerSource = triggerSource,
                emergencyStatus = "ACTIVE",
                syncStatus = "PENDING"
            )
            repository.insertSosQueue(sosEntity)

            // Cache location
            repository.insertLocationCache(
                LocationCacheEntity(
                    latitude = lat,
                    longitude = lng,
                    accuracy = 5.0f,
                    speed = 0.0f,
                    altitude = 10.0,
                    batteryLevel = sosEntity.batteryLevel,
                    timestamp = sosEntity.timestamp,
                    synced = false
                )
            )

            // Schedule WorkManager background sync
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val syncRequest = OneTimeWorkRequestBuilder<SosSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(syncRequest)

            // SMS Fallback to contacts (only verified numbers)
            val contactsList = repository.contacts.firstOrNull() ?: emptyList()
            for (contact in contactsList) {
                if (contact.isVerified) {
                    EmergencySmsManager.sendEmergencySms(
                        context,
                        contact.phone,
                        "Guardian User",
                        lat,
                        lng,
                        sosEntity.batteryLevel,
                        sosEntity.timestamp
                    )
                }
            }
        }
    }

    fun cancelSos() {
        _isSosActive.value = false
    }

    fun addIncident(title: String, category: String, severity: String, description: String, location: String) {
        viewModelScope.launch {
            repository.insertIncident(
                IncidentEntity(
                    title = title,
                    category = category,
                    severity = severity,
                    description = description,
                    location = location,
                    upvotes = 1,
                    status = "Active"
                )
            )
        }
    }

    fun upvoteIncident(incident: IncidentEntity) {
        viewModelScope.launch {
            repository.updateIncident(incident.copy(upvotes = incident.upvotes + 1))
        }
    }

    fun addContact(name: String, phone: String, relationship: String, isVerified: Boolean = true) {
        viewModelScope.launch {
            repository.insertContact(ContactEntity(name = name, phone = phone, relationship = relationship, isVerified = isVerified))
            logAudit("CONTACT", "Added trusted contact: $name ($phone) - Verified: $isVerified")
        }
    }

    fun updateContact(contact: ContactEntity) {
        viewModelScope.launch {
            repository.updateContact(contact)
            logAudit("CONTACT", "Updated trusted contact: ${contact.name} (${contact.phone}) - Verified: ${contact.isVerified}")
        }
    }

    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch {
            repository.deleteContact(contact)
            logAudit("CONTACT", "Deleted trusted contact: ${contact.name}")
        }
    }

    fun startCheckin(durationMinutes: Int, note: String) {
        viewModelScope.launch {
            repository.insertCheckin(
                CheckinEntity(
                    durationMinutes = durationMinutes,
                    status = "Active",
                    note = note
                )
            )
        }
    }

    fun updateCheckinStatus(checkin: CheckinEntity, newStatus: String) {
        viewModelScope.launch {
            repository.updateCheckin(checkin.copy(status = newStatus))
        }
    }

    fun sendAiMessage(prompt: String) {
        if (prompt.isBlank()) return
        val userMsg = ChatMessage("User", prompt)
        _chatMessages.value = _chatMessages.value + userMsg
        
        val reply = "Guardian Safety Sentinel: Note logged for prompt: '$prompt'. Live sensors and emergency contacts are active."
        val sysMsg = ChatMessage("Guardian Sentinel", reply)
        _chatMessages.value = _chatMessages.value + sysMsg
    }

    fun updateMedicalProfile(
        name: String,
        bloodGroup: String,
        allergies: String,
        medicalConditions: String,
        medications: String,
        emergencyNotes: String,
        doctorContact: String,
        insuranceInfo: String
    ) {
        viewModelScope.launch {
            repository.upsertMedicalProfile(
                MedicalProfileEntity(
                    id = 1L,
                    name = name,
                    bloodGroup = bloodGroup,
                    allergies = allergies,
                    medicalConditions = medicalConditions,
                    medications = medications,
                    emergencyNotes = emergencyNotes,
                    doctorContact = doctorContact,
                    insuranceInfo = insuranceInfo
                )
            )
        }
    }

    fun verifyPin(enteredPin: String): Boolean {
        if (enteredPin == "9999") {
            triggerSos("PIN")
            return true
        }
        val storedPin = securePrefs.getUserPin() ?: "1234"
        return enteredPin == storedPin
    }

    fun refreshAiRecommendation() {
        val tips = listOf(
            "Guardian Protocol: Maintain 2+ verified emergency contacts for automated location dispatch.",
            "Guardian Protocol: Check battery level and ensure GPS accuracy is high when traveling alone.",
            "Guardian Protocol: Biometric trigger enabled. Pressing SOS alerts guardians with live Google Maps link."
        )
        _aiRecommendation.value = tips.random()
    }

    fun simulateUnusualMovement() {
        _unusualMovementDetected.value = true
        viewModelScope.launch {
            repository.insertAuditLog(
                AuditLogEntity(
                    timestamp = System.currentTimeMillis(),
                    triggerType = "FALL",
                    actionDetails = "Fall detection sensor triggered emergency anomaly",
                    status = "TRIGGERED"
                )
            )
            _aiRecommendation.value = "⚠️ Movement Anomaly Detected: Keep victim calm and still. Ensure open airway. Call 911 immediately."
        }
    }

    fun triggerVoiceSos() {
        triggerSos("VOICE")
        viewModelScope.launch {
            val msg = ChatMessage("Guardian Sentinel", "Voice-Activated SOS triggered successfully! Broadcasting emergency beacon and location to all guardians.")
            _chatMessages.value = _chatMessages.value + msg
        }
    }

    fun toggleVoiceActivation(enabled: Boolean, context: android.content.Context) {
        _isVoiceActivationEnabled.value = enabled
        if (enabled) {
            if (voiceManager == null) {
                voiceManager = com.example.service.VoiceEmergencyManager(context) { phrase, classification, advice ->
                    triggerSos("VOICE: $phrase")
                    viewModelScope.launch {
                        _aiRecommendation.value = "🚨 Voice Emergency [$classification]: $advice"
                        val msg = ChatMessage("Guardian Sentinel", "Voice emergency phrase detected ('$phrase'). Classified as: $classification. Guidance: $advice")
                        _chatMessages.value = _chatMessages.value + msg
                    }
                }
            }
            voiceManager?.startListening()
        } else {
            voiceManager?.stopListening()
            voiceManager = null
        }
    }

    fun toggleBackgroundAi(enabled: Boolean) {
        _isBackgroundAiEnabled.value = enabled
        logAudit("PROTECTION", "Background Threat Monitor set to $enabled")
    }
    fun toggleFallGuard(enabled: Boolean) {
        _isFallGuardEnabled.value = enabled
        sensorMonitor?.updateFlags(enabled, _isCrashSosEnabled.value, _isShakeGestureEnabled.value)
        logAudit("PROTECTION", "Fall Guard set to $enabled")
    }
    fun toggleCrashSos(enabled: Boolean) {
        _isCrashSosEnabled.value = enabled
        sensorMonitor?.updateFlags(_isFallGuardEnabled.value, enabled, _isShakeGestureEnabled.value)
        logAudit("PROTECTION", "Crash SOS set to $enabled")
    }
    fun toggleShakeGesture(enabled: Boolean) {
        _isShakeGestureEnabled.value = enabled
        sensorMonitor?.updateFlags(_isFallGuardEnabled.value, _isCrashSosEnabled.value, enabled)
        logAudit("PROTECTION", "Shake Gesture set to $enabled")
    }
    fun toggleAutoSosSilence(enabled: Boolean) {
        _isAutoSosSilenceEnabled.value = enabled
        logAudit("PROTECTION", "Auto-SOS on Silence set to $enabled")
    }
    fun toggleLiveGps(enabled: Boolean) {
        _isLiveGpsEnabled.value = enabled
        logAudit("PROTECTION", "Live GPS Telemetry set to $enabled")
    }
    fun toggleGuardianProximity(enabled: Boolean) {
        _isGuardianProximityEnabled.value = enabled
        logAudit("PROTECTION", "Guardian Proximity set to $enabled")
    }
    fun toggleCloudRecord(enabled: Boolean) {
        _isCloudRecordEnabled.value = enabled
        logAudit("PROTECTION", "Cloud Evidence Vault set to $enabled")
    }
    fun toggleAudioBlackbox(enabled: Boolean) {
        _isAudioBlackboxEnabled.value = enabled
        logAudit("PROTECTION", "Audio Blackbox set to $enabled")
    }
    fun toggleBiometricLock(enabled: Boolean) {
        _isBiometricLockEnabled.value = enabled
        logAudit("PROTECTION", "Biometric Lock set to $enabled")
    }
    fun toggleIncognitoMode(enabled: Boolean) {
        _isIncognitoModeEnabled.value = enabled
        logAudit("PROTECTION", "Incognito Mode set to $enabled")
    }

    private fun logAudit(type: String, details: String) {
        viewModelScope.launch {
            repository.insertAuditLog(
                AuditLogEntity(
                    triggerType = type,
                    actionDetails = details,
                    status = "SUCCESS"
                )
            )
        }
    }

    fun createFamilyGroup(groupName: String, ownerName: String) {
        viewModelScope.launch {
            val code = "GUARDIAN-" + (1000..9999).random()
            val groupId = repository.insertFamilyGroup(
                FamilyGroupEntity(
                    groupName = groupName,
                    ownerId = "owner_${System.currentTimeMillis()}",
                    inviteCode = code
                )
            )
            repository.insertFamilyMember(
                FamilyMemberEntity(
                    groupId = groupId,
                    name = ownerName,
                    role = "OWNER",
                    email = "owner@guardian.org",
                    phone = "555-0100"
                )
            )
            repository.insertAuditLog(
                AuditLogEntity(
                    triggerType = "FAMILY",
                    actionDetails = "Created family group '$groupName' with invite code $code",
                    status = "SUCCESS"
                )
            )
        }
    }

    fun joinFamilyGroup(inviteCode: String, memberName: String, role: String) {
        viewModelScope.launch {
            repository.insertFamilyMember(
                FamilyMemberEntity(
                    groupId = 1L,
                    name = memberName,
                    role = role,
                    email = "${memberName.lowercase().replace(" ", "")}@guardian.org",
                    phone = "555-" + (1000..9999).random()
                )
            )
            repository.insertAuditLog(
                AuditLogEntity(
                    triggerType = "FAMILY",
                    actionDetails = "Joined family group with invite code $inviteCode as $role",
                    status = "SUCCESS"
                )
            )
        }
    }

    fun addSafeZone(name: String, latitude: Double, longitude: Double, radius: Float, type: String) {
        viewModelScope.launch {
            repository.insertSafeZone(
                SafeZoneEntity(
                    name = name,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = radius,
                    zoneType = type
                )
            )
            repository.insertAuditLog(
                AuditLogEntity(
                    triggerType = "GEOFENCE",
                    actionDetails = "Created safe zone '$name' ($type) at ($latitude, $longitude)",
                    status = "SUCCESS"
                )
            )
        }
    }

    fun deleteSafeZone(zone: SafeZoneEntity) {
        viewModelScope.launch {
            repository.deleteSafeZone(zone)
        }
    }

    fun updateAppLimit(appId: Long, limitMinutes: Int, isRestricted: Boolean) {
        viewModelScope.launch {
            val currentList = appUsage.value
            val target = currentList.find { it.appId == appId }
            target?.let {
                repository.updateAppUsage(
                    it.copy(dailyLimitMinutes = limitMinutes, isRestricted = isRestricted)
                )
            }
        }
    }

    private val categoriesList = listOf("Crime", "Hazard", "Weather", "Medical", "SOS", "Community")
    private val _incidentSubscriptions = MutableStateFlow<Map<String, Boolean>>(
        categoriesList.associateWith { securePrefs.isIncidentSubscribed(it, true) }
    )
    val incidentSubscriptions: StateFlow<Map<String, Boolean>> = _incidentSubscriptions.asStateFlow()

    fun toggleIncidentSubscription(category: String, subscribed: Boolean) {
        securePrefs.setIncidentSubscribed(category, subscribed)
        _incidentSubscriptions.value = _incidentSubscriptions.value + (category to subscribed)
    }

    fun sendFamilyMessage(text: String, isEmergency: Boolean, senderName: String) {
        viewModelScope.launch {
            repository.insertFamilyMessage(
                FamilyMessageEntity(
                    senderName = senderName,
                    text = text,
                    isEmergencyBroadcast = isEmergency
                )
            )
            if (isEmergency) {
                repository.insertAuditLog(
                    AuditLogEntity(
                        triggerType = "SOS",
                        actionDetails = "Family emergency broadcast sent: $text",
                        status = "TRIGGERED"
                    )
                )
            }
        }
    }
}
