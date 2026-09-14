package com.guardian.safety.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.guardian.safety.data.GuardianRepository
import com.guardian.safety.data.SosQueueEntity
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.worker.SosSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** Stage of the emergency, mirroring the states the UI and audit log report. */
enum class SosStage { TRIGGERED, CALL_STARTED, SMS_STARTED, CLOUD_SYNCED, FAILED, CANCELLED }

/** What the safety-critical trigger actually managed to do. */
data class SosTriggerResult(
    val clientEventId: String,
    val stage: SosStage,
    val callTarget: String? = null,
    val callOutcome: EmergencyActionResult? = null,
    val smsDeliveries: List<SmsDelivery> = emptyList(),
    val locationAvailable: Boolean = false,
    val cloudQueued: Boolean = false,
    val cloudSynced: Boolean = false,
    /** Everything that did not work, stated plainly for the user. */
    val problems: List<String> = emptyList(),
)

/** Live emergency state observed by the UI. */
data class SosActiveState(
    val clientEventId: String,
    val triggerSource: String,
    val startedAt: Long,
    val stage: SosStage,
    val syncStatus: String,
    val remoteId: String? = null,
    val acknowledgedAt: Long? = null,
    val lastError: String? = null,
)

/**
 * Orchestrates a Guardian emergency.
 *
 * Order of operations is deliberate:
 * 1. The emergency is recorded locally first (Room), so nothing is lost if the
 *    device dies or the network is down.
 * 2. The local actions Android can perform — call, SMS — fire immediately and
 *    independently of any network. Their real outcomes are captured.
 * 3. Cloud synchronisation is queued through WorkManager with the same
 *    `clientEventId`, so retries are idempotent and can never create a second
 *    emergency. `CLOUD_SYNCED` is reached only when the Worker accepted the event.
 */
class SosEmergencyManager(
    private val context: Context,
    private val repository: GuardianRepository,
    private val sessionManager: SessionManager,
) {

    private val _active = MutableStateFlow<SosActiveState?>(null)
    val active: StateFlow<SosActiveState?> = _active.asStateFlow()

    /**
     * Triggers an emergency.
     *
     * @param location the device's real fix, or null when location is unavailable.
     *   No coordinates are ever invented.
     */
    suspend fun trigger(
        triggerSource: String,
        location: Location?,
        userId: String? = sessionManager.currentSession()?.userId,
        displayName: String? = sessionManager.currentSession()?.displayName,
    ): SosTriggerResult {
        val clientEventId = repository.newClientEventId()
        val now = System.currentTimeMillis()
        val problems = mutableListOf<String>()

        val batteryLevel = batteryLevel()
        val networkStatus = networkStatus()
        val deviceInfo = deviceInfo()

        // 1. Durable local record first: the emergency exists even offline.
        val queueId = repository.insertSosQueue(
            SosQueueEntity(
                clientEventId = clientEventId,
                userId = userId,
                timestamp = now,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracyM = location?.accuracy?.toDouble(),
                batteryLevel = batteryLevel,
                networkStatus = networkStatus,
                deviceInfo = deviceInfo,
                triggerSource = triggerSource,
                emergencyStatus = "ACTIVE",
                syncStatus = "PENDING",
            ),
        )
        repository.logAudit(
            triggerType = triggerSource,
            actionDetails = "Emergency triggered (device event $clientEventId)",
            status = "TRIGGERED",
            userId = userId,
        )
        _active.value = SosActiveState(
            clientEventId = clientEventId,
            triggerSource = triggerSource,
            startedAt = now,
            stage = SosStage.TRIGGERED,
            syncStatus = "PENDING",
        )

        if (location == null) {
            problems += "Location is not available, so your contacts and responders will not see your position."
        }

        // 2. Local emergency actions. These never depend on the network.
        val contacts = repository.getAllContactsOnce().sortedByDescending { it.priority }
        val primaryContact = contacts.firstOrNull { it.phone.isNotBlank() }

        val callOutcome: EmergencyActionResult?
        val callTarget: String?
        if (primaryContact != null) {
            callTarget = primaryContact.phone
            callOutcome = EmergencyCallManager.callNumber(context, primaryContact.phone)
        } else {
            callTarget = null
            callOutcome = EmergencyActionResult.Failed(
                "No emergency contact with a phone number is saved, so no call was placed.",
            )
            problems += "No emergency contact with a phone number is saved."
        }
        when (callOutcome) {
            is EmergencyActionResult.Dispatched -> repository.logAudit(
                triggerType = "CALL",
                actionDetails = callOutcome.detail,
                status = "SUCCESS",
                userId = userId,
            )
            is EmergencyActionResult.UserActionRequired -> {
                problems += callOutcome.detail
                repository.logAudit("CALL", callOutcome.detail, "FAILED", userId)
            }
            is EmergencyActionResult.Failed -> {
                problems += callOutcome.message
                repository.logAudit("CALL", callOutcome.message, "FAILED", userId)
            }
        }

        val deliveries = contacts
            .filter { it.phone.isNotBlank() }
            .map { contact ->
                EmergencySmsManager.sendEmergencySms(
                    context = context,
                    phoneNumber = contact.phone,
                    userName = displayName?.takeIf { it.isNotBlank() } ?: "A Guardian user",
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    accuracyM = location?.accuracy?.takeIf { location.hasAccuracy() },
                    batteryLevel = batteryLevel,
                    timestamp = now,
                )
            }
        deliveries.filter { it.status == SmsStatus.FAILED || it.status == SmsStatus.COMPOSE_OPENED }
            .forEach { delivery ->
                problems += delivery.detail
                repository.logAudit("SMS", delivery.detail, "FAILED", userId)
            }
        deliveries.filter { it.status == SmsStatus.QUEUED || it.status == SmsStatus.SENT }
            .forEach { delivery ->
                repository.logAudit("SMS", delivery.detail, "SUCCESS", userId)
            }
        if (contacts.isEmpty()) {
            problems += "No emergency contacts are saved, so no alert text could be sent."
        }

        val stage = when {
            deliveries.any { it.status == SmsStatus.QUEUED || it.status == SmsStatus.SENT } -> SosStage.SMS_STARTED
            callOutcome is EmergencyActionResult.Dispatched -> SosStage.CALL_STARTED
            else -> SosStage.TRIGGERED
        }
        _active.value = _active.value?.copy(stage = stage)

        // 3. Cloud synchronisation: queued now, executed by the Worker. It is
        //    explicitly NOT required for the local emergency to have happened.
        scheduleCloudSync()
        val synced = runCatching { trySyncQueue() }.getOrElse { error ->
            Log.w(TAG, "Immediate emergency sync attempt failed", error)
            0
        }
        if (synced > 0) {
            val record = repository.getSosByClientEventId(clientEventId)
            _active.value = _active.value?.copy(
                stage = SosStage.CLOUD_SYNCED,
                syncStatus = record?.syncStatus ?: "SYNCED",
                remoteId = record?.remoteId,
            )
        } else {
            problems += "Your emergency is saved on this device and will sync to your family as soon as there is a connection."
        }

        // Share the live location with the family group when one exists.
        if (location != null) {
            runCatching { shareLocationWithFamily(location) }
                .onFailure { Log.w(TAG, "Family location share failed", it) }
        }

        val finalRecord = repository.getSosByClientEventId(clientEventId)
        val result = SosTriggerResult(
            clientEventId = clientEventId,
            stage = if (finalRecord?.syncStatus == "SYNCED") SosStage.CLOUD_SYNCED else stage,
            callTarget = callTarget,
            callOutcome = callOutcome,
            smsDeliveries = deliveries,
            locationAvailable = location != null,
            cloudQueued = true,
            cloudSynced = finalRecord?.syncStatus == "SYNCED",
            problems = problems.toList(),
        )

        if (result.cloudSynced) {
            repository.logAudit(
                triggerType = "CLOUD_SYNC",
                actionDetails = "Emergency acknowledged by the Guardian service (event ${finalRecord?.remoteId ?: queueId})",
                status = "SUCCESS",
                userId = userId,
            )
        }
        return result
    }

    /** Marks the emergency resolved. Only reports success when the server agreed. */
    suspend fun resolve(clientEventId: String, note: String? = null): ApiResult<Unit> {
        val record = repository.getSosByClientEventId(clientEventId)
            ?: return ApiResult.Failure(
                com.guardian.safety.remote.ApiError("not_found", "That emergency is no longer on this device."),
            )
        val remoteId = record.remoteId
        if (remoteId == null) {
            repository.updateSosQueue(record.copy(emergencyStatus = "CANCELLED", syncStatus = "PENDING"))
            repository.logAudit("SYSTEM", "Emergency cancelled on the device; the server has not been told yet.", "CANCELLED")
            _active.value = null
            return ApiResult.Failure(
                com.guardian.safety.remote.ApiError(
                    "not_synced",
                    "The emergency was cancelled on this device but never reached the Guardian service, so your family could not be notified.",
                ),
            )
        }
        val result = repository.resolveSos(remoteId, note)
        return when (result) {
            is ApiResult.Success -> {
                repository.updateSosQueue(record.copy(emergencyStatus = "RESOLVED"))
                repository.logAudit("SYSTEM", "Emergency resolved on the server.", "SUCCESS")
                _active.value = null
                ApiResult.Success(Unit, result.statusCode)
            }
            is ApiResult.Failure -> result
        }
    }

    /** Marks the local emergency as cancelled without contacting the server. */
    suspend fun cancelLocally(clientEventId: String) {
        val record = repository.getSosByClientEventId(clientEventId) ?: return
        repository.updateSosQueue(record.copy(emergencyStatus = "CANCELLED"))
        repository.logAudit("SYSTEM", "Emergency cancelled by the user on this device.", "CANCELLED")
        _active.value = null
    }

    /**
     * Sends everything still queued. Idempotent: each row keeps its original
     * `clientEventId`, and rows are marked SYNCED only on server acceptance.
     */
    suspend fun trySyncQueue(): Int {
        val pending = repository.getPendingSosQueue()
        var synced = 0
        pending.forEach { item ->
            if (item.retryCount >= MAX_SYNC_ATTEMPTS) {
                repository.updateSosQueue(
                    item.copy(
                        syncStatus = "FAILED",
                        lastError = item.lastError
                            ?: "Guardian could not reach the emergency service after $MAX_SYNC_ATTEMPTS attempts.",
                    ),
                )
                return@forEach
            }
            when (val result = repository.syncSosEvent(item)) {
                is ApiResult.Success -> {
                    synced++
                    val active = _active.value
                    if (active?.clientEventId == item.clientEventId) {
                        _active.value = active.copy(
                            stage = SosStage.CLOUD_SYNCED,
                            syncStatus = "SYNCED",
                            remoteId = result.data.remoteId,
                            acknowledgedAt = result.data.acknowledgedAt,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    if (result.error.offline || result.error.retryable) {
                        scheduleCloudSync(item.retryCount + 1)
                    }
                }
            }
        }
        return synced
    }

    /** Restores the active emergency banner after process death. */
    suspend fun restoreActive(): SosActiveState? {
        val active = repository.getPendingSosQueue()
            .lastOrNull { it.emergencyStatus == "ACTIVE" || it.emergencyStatus == "TRIGGERED" }
            ?: return null
        val state = SosActiveState(
            clientEventId = active.clientEventId,
            triggerSource = active.triggerSource,
            startedAt = active.timestamp,
            stage = when (active.syncStatus) {
                "SYNCED" -> SosStage.CLOUD_SYNCED
                else -> SosStage.TRIGGERED
            },
            syncStatus = active.syncStatus,
            remoteId = active.remoteId,
            acknowledgedAt = active.acknowledgedAt,
            lastError = active.lastError,
        )
        _active.value = state
        return state
    }

    fun scheduleCloudSync(attempt: Int = 0) {
        val request = OneTimeWorkRequestBuilder<SosSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                (SosSyncWorker.BASE_BACKOFF_SECONDS * (attempt + 1)).coerceAtMost(MAX_BACKOFF_SECONDS),
                TimeUnit.SECONDS,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SosSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private suspend fun shareLocationWithFamily(location: Location) {
        val group = repository.getFamilyGroupOnce() ?: return
        repository.shareLocation(
            groupRemoteId = group.remoteId,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy.takeIf { location.hasAccuracy() },
            batteryLevel = batteryLevel(),
            source = "MANUAL",
        )
    }

    private fun batteryLevel(): Int? = try {
        val manager = context.getSystemService(BatteryManager::class.java)
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (_: Exception) {
        null
    }

    private fun networkStatus(): String {
        val connectivityManager =
            context.getSystemService(android.net.ConnectivityManager::class.java) ?: return "UNKNOWN"
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return "OFFLINE"
        return when {
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "UNKNOWN"
        }
    }

    private fun deviceInfo(): String {
        val hasCallPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val hasSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return buildString {
            append("Android ${Build.VERSION.RELEASE}; ")
            append("${Build.MANUFACTURER} ${Build.MODEL}; ")
            append("call_permission=${if (hasCallPermission) "granted" else "denied"}; ")
            append("sms_permission=${if (hasSmsPermission) "granted" else "denied"}; ")
            append("sim=${if ((telephony?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN) == TelephonyManager.SIM_STATE_READY) "ready" else "unavailable"}")
        }
    }

    companion object {
        private const val TAG = "SosEmergencyManager"
        private const val MAX_SYNC_ATTEMPTS = 8
        private const val MAX_BACKOFF_SECONDS = 30L
    }
}

/** The emergency number to dial for the device's network, never a US default. */
object EmergencyNumbers {

    fun primary(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        if (telephony != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val numbers = telephony.emergencyNumberList
            if (numbers != null) {
                numbers.values.firstOrNull { it.number.isNotBlank() }?.let { return it.number }
            }
        }
        // 112 is the GSM emergency number reachable in the EU, India and most of
        // the world, including on devices with no SIM.
        return "112"
    }
}
