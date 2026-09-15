package com.guardian.safety.service

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.guardian.safety.data.GuardianRepository
import com.guardian.safety.data.SosQueueEntity
import com.guardian.safety.remote.ApiError
import com.guardian.safety.remote.ApiResult
import com.guardian.safety.worker.SosSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Where an emergency currently stands. */
enum class SosStage {
    /** Recorded locally and the device actions have been dispatched. */
    TRIGGERED,

    /** The emergency call was handed to the telephony stack. */
    CALL_STARTED,

    /** Emergency SMS messages were handed to the telephony stack. */
    SMS_STARTED,

    /** The server accepted the event and the queue row is marked SYNCED. */
    CLOUD_SYNCED,

    /** Nothing could be dispatched, or the emergency was cancelled. */
    FAILED,

    /** The user cancelled the alert. */
    CANCELLED,
}

/** Everything the UI needs to describe an emergency honestly. */
data class SosStatus(
    val clientEventId: String,
    val stage: SosStage,
    val remoteId: String? = null,
    val triggerSource: String,
    val startedAt: Long,
    val callTarget: String? = null,
    val callOutcome: EmergencyActionResult? = null,
    val smsDeliveries: List<SmsDelivery> = emptyList(),
    val locationShared: Boolean = false,
    val locationAvailable: Boolean = false,
    val cloudQueued: Boolean = false,
    val cloudSynced: Boolean = false,
    val cloudError: String? = null,
    val problems: List<String> = emptyList(),
    val cancelledAt: Long? = null,
) {
    val isActive: Boolean
        get() = stage != SosStage.CANCELLED && stage != SosStage.FAILED

    /** Short, user-facing summary used by the emergency screen. */
    fun summary(): String = when (stage) {
        SosStage.TRIGGERED -> "Emergency recorded on this device."
        SosStage.CALL_STARTED -> "Emergency call handed to the phone app."
        SosStage.SMS_STARTED -> "Alert messages handed to the messaging stack."
        SosStage.CLOUD_SYNCED -> "Alert received by Guardian and your family."
        SosStage.CANCELLED -> "Alert cancelled on this device."
        SosStage.FAILED -> "Emergency could not be completed. Check permissions and try again."
    }
}

/**
 * Orchestrates the safety-critical emergency path.
 *
 * Order of operations, and the reason for it:
 *
 * 1. The emergency is written to the local queue *first*. If the process dies
 *    mid-emergency, the record and its `clientEventId` survive.
 * 2. The device actions run immediately — an emergency call and SMS messages go
 *    through Android's telephony stack. These never wait for, and never depend on,
 *    the cloud. When Android refuses an action (permission missing, no telephony)
 *    the outcome is recorded as a problem and surfaced in the UI; it is never
 *    reported as success.
 * 3. Cloud sync is queued as background work and attempted right away. A row only
 *    becomes `SYNCED` when the server accepted it, and the `clientEventId` makes
 *    that upload idempotent, so a retry can never create a duplicate emergency.
 */
class SosEmergencyManager(
    private val context: Context,
    private val repository: GuardianRepository,
    private val sessionManager: SessionManager,
) {

    private val _status = MutableStateFlow<SosStatus?>(null)
    val status: StateFlow<SosStatus?> = _status.asStateFlow()

    /**
     * Fires an emergency. [location] is the real device fix when one is available;
     * when it is null the alert still goes out and the UI says the location was
     * unavailable instead of inventing coordinates.
     */
    suspend fun trigger(
        triggerSource: String,
        location: Location?,
        batteryLevel: Int?,
        networkStatus: String?,
        deviceInfo: String?,
        contactPhoneNumbers: List<String>,
        shareLocationWithFamily: Boolean = true,
    ): SosStatus {
        val clientEventId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val problems = mutableListOf<String>()

        val queued = SosQueueEntity(
            clientEventId = clientEventId,
            userId = sessionManager.currentSession()?.userId,
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyM = location?.accuracy?.toDouble(),
            batteryLevel = batteryLevel,
            networkStatus = networkStatus ?: "UNKNOWN",
            deviceInfo = deviceInfo ?: "",
            triggerSource = triggerSource,
            syncStatus = UNVERIFIED_SYNC_STATUS,
            timestamp = startedAt,
            lastAttemptAt = 0L,
            lastError = null,
        )

        // 1. Local record first: the emergency exists on the device even if
        //    everything after this line fails.
        val rowId = try {
            repository.insertSosQueue(queued)
        } catch (error: Exception) {
            Log.e(TAG, "Emergency queue write failed", error)
            val failed = SosStatus(
                clientEventId = clientEventId,
                stage = SosStage.FAILED,
                triggerSource = triggerSource,
                startedAt = startedAt,
                locationAvailable = location != null,
                problems = listOf(
                    "This device could not save the emergency locally: ${error.message ?: "storage error"}.",
                ),
            )
            _status.value = failed
            return failed
        }

        repository.logAudit(
            triggerType = triggerSource,
            actionDetails = "SOS_TRIGGERED queuedId=$rowId locationAvailable=${location != null}",
            status = "TRIGGERED",
            userId = queued.userId,
        )

        var status = SosStatus(
            clientEventId = clientEventId,
            stage = SosStage.TRIGGERED,
            triggerSource = triggerSource,
            startedAt = startedAt,
            locationAvailable = location != null,
        )
        _status.value = status

        // 2. Device actions: emergency call and SMS, independent of the network.
        val callTarget = contactPhoneNumbers.firstOrNull { it.isNotBlank() }
            ?: EmergencyNumbers.primary(context)
        val callOutcome = try {
            EmergencyCallManager.callNumber(context, callTarget)
        } catch (error: SecurityException) {
            EmergencyActionResult.Failed(
                "Android blocked the emergency call.",
                error.message ?: "CALL_PHONE was not granted.",
            )
        } catch (error: Exception) {
            EmergencyActionResult.Failed(
                "The emergency call could not be started.",
                error.message ?: error.javaClass.simpleName,
            )
        }

        when (callOutcome) {
            is EmergencyActionResult.Dispatched -> {
                status = status.copy(
                    stage = SosStage.CALL_STARTED,
                    callOutcome = callOutcome,
                    callTarget = callTarget,
                )
            }
            is EmergencyActionResult.UserActionRequired -> {
                problems += callOutcome.detail
                status = status.copy(callOutcome = callOutcome, callTarget = callTarget)
            }
            is EmergencyActionResult.Failed -> {
                problems += callOutcome.message
                status = status.copy(callOutcome = callOutcome, callTarget = callTarget)
            }
        }
        status = status.copy(problems = problems.toList())
        _status.value = status

        var deliveries: List<SmsDelivery> = emptyList()
        val recipients = contactPhoneNumbers.map { it.trim() }.filter { it.isNotEmpty() }
        if (recipients.isEmpty()) {
            problems += "No emergency contacts are saved, so no alert messages were sent. Add contacts in the app."
        } else {
            val identity = sessionManager.currentSession()?.displayName?.takeIf { it.isNotBlank() }
                ?: "A Guardian user"
            deliveries = try {
                recipients.map { phoneNumber ->
                    EmergencySmsManager.sendEmergencySms(
                        context = context,
                        phoneNumber = phoneNumber,
                        userName = identity,
                        latitude = location?.latitude,
                        longitude = location?.longitude,
                        accuracyM = location?.accuracy,
                        batteryLevel = batteryLevel,
                        timestamp = startedAt,
                        locationAvailable = location != null,
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Emergency SMS dispatch failed", error)
                problems += "Alert messages could not be handed to Android: ${error.message ?: error.javaClass.simpleName}."
                emptyList()
            }

            val failedDeliveries = deliveries.filter {
                it.status == SmsStatus.FAILED || it.status == SmsStatus.PERMISSION_REQUIRED
            }
            if (failedDeliveries.isNotEmpty()) {
                problems += "${failedDeliveries.size} alert message(s) were not sent: ${failedDeliveries.first().detail}"
            }
            if (deliveries.any { it.status == SmsStatus.COMPOSE_OPENED }) {
                problems += "Android requires you to confirm the alert message in your messaging app (SEND_SMS is not granted)."
            }
            val handedToNetwork = deliveries.any {
                it.status == SmsStatus.QUEUED || it.status == SmsStatus.SENT || it.status == SmsStatus.DELIVERED
            }
            if (handedToNetwork) {
                status = status.copy(stage = SosStage.SMS_STARTED)
            }
        }
        status = status.copy(smsDeliveries = deliveries, problems = problems.toList())
        _status.value = status

        // 3. Family share, still on-device first and only reported once the server
        //    confirms it.
        if (shareLocationWithFamily) {
            when {
                location == null -> problems +=
                    "Location was unavailable, so the alert was sent without coordinates."

                else -> {
                    val group = runCatching { repository.getFamilyGroupOnce() }.getOrNull()
                    if (group == null) {
                        problems += "No family group is joined, so nobody was notified with your location."
                    } else {
                        val shared = try {
                            repository.shareLocation(
                                groupRemoteId = group.remoteId,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracy = location.accuracy.takeIf { location.hasAccuracy() },
                                batteryLevel = batteryLevel,
                                source = "SOS",
                            )
                        } catch (error: Exception) {
                            ApiResult.Failure(
                                ApiError(
                                    code = "share_failed",
                                    message = error.message ?: "Location share failed.",
                                ),
                            )
                        }
                        when (shared) {
                            is ApiResult.Success -> status = status.copy(locationShared = true)
                            is ApiResult.Failure -> problems +=
                                "Your family could not be notified with your location yet: ${shared.error.message} " +
                                "It will be sent when the connection returns."
                        }
                    }
                }
            }
        }
        status = status.copy(problems = problems.toList())
        _status.value = status

        // 4. Cloud upload, queued now and idempotent thanks to clientEventId.
        val queuedWork = enqueueCloudSync()
        status = status.copy(
            cloudQueued = queuedWork,
            cloudError = if (queuedWork) {
                null
            } else {
                "Background sync could not be scheduled; the alert stays on this device."
            },
        )
        _status.value = status

        val synced = trySyncQueue()
        val row = repository.getSosByClientEventId(clientEventId)
        val serverAccepted = row?.syncStatus == SYNCED_SYNC_STATUS
        status = status.copy(
            stage = when {
                serverAccepted -> SosStage.CLOUD_SYNCED
                else -> status.stage
            },
            remoteId = row?.remoteId,
            cloudSynced = serverAccepted,
            cloudError = row?.lastError ?: status.cloudError,
            problems = (status.problems + synced.problems).distinct(),
        )
        _status.value = status
        return status
    }

    /** Uploads every queued emergency. Returns counts, not optimism. */
    suspend fun trySyncQueue(): SyncOutcome {
        var uploaded = 0
        val problems = mutableListOf<String>()
        val pending = repository.getUnsentSosQueue()
        for (item in pending) {
            when (val result = repository.syncSosEvent(item)) {
                is ApiResult.Success -> uploaded++
                is ApiResult.Failure -> {
                    // Connectivity problems are expected offline; they stay queued
                    // and are retried, so they are not reported as errors here.
                    if (!result.error.isNetworkIssue) {
                        problems += result.error.message
                    }
                }
            }
        }
        return SyncOutcome(uploaded = uploaded, attempted = pending.size, problems = problems)
    }

    /**
     * Cancels an alert the user triggered by mistake.
     *
     * The local record is always marked cancelled. The server copy is only resolved
     * when it exists *and* the request succeeded — a failed resolve is reported so
     * the user knows their family was already alerted.
     */
    suspend fun cancel(
        clientEventId: String,
        note: String? = "Cancelled by the user on their device.",
    ): ApiResult<Unit> {
        val row = repository.getSosByClientEventId(clientEventId)
            ?: return ApiResult.Failure(
                ApiError(
                    code = "not_found",
                    message = "That alert is no longer on this device.",
                ),
            )

        repository.markSosCancelled(row, note)

        val remoteId = row.remoteId
        val resolve: ApiResult<Unit> = if (remoteId != null) {
            repository.resolveSos(remoteId, note)
        } else {
            // Nothing reached the server, so there is nothing to resolve there.
            ApiResult.Success(Unit, HTTP_NO_REMOTE_COPY)
        }

        _status.value = (_status.value ?: SosStatus(
            clientEventId = clientEventId,
            stage = SosStage.CANCELLED,
            triggerSource = row.triggerSource,
            startedAt = row.timestamp,
        )).copy(
            stage = SosStage.CANCELLED,
            cancelledAt = System.currentTimeMillis(),
            cloudError = (resolve as? ApiResult.Failure)?.error?.message,
            problems = if (resolve is ApiResult.Failure) {
                listOf("Your family may already have seen this alert: ${resolve.error.message}")
            } else {
                emptyList()
            },
        )

        repository.logAudit(
            triggerType = row.triggerSource,
            actionDetails = "SOS_CANCELLED clientEventId=$clientEventId remoteResolved=" +
                "${resolve is ApiResult.Success && remoteId != null}",
            status = "CANCELLED",
            userId = row.userId,
        )
        return resolve
    }

    /** Restores the emergency banner after a process restart. */
    suspend fun restoreActive() {
        val active = repository.getUnsentSosQueue().firstOrNull()
            ?: repository.getPendingSosQueue().firstOrNull()
        if (active != null && active.syncStatus != CANCELLED_SYNC_STATUS) {
            _status.value = SosStatus(
                clientEventId = active.clientEventId,
                stage = if (active.syncStatus == SYNCED_SYNC_STATUS) SosStage.CLOUD_SYNCED else SosStage.TRIGGERED,
                remoteId = active.remoteId,
                triggerSource = active.triggerSource,
                startedAt = active.timestamp,
                locationAvailable = active.latitude != null && active.longitude != null,
                cloudSynced = active.syncStatus == SYNCED_SYNC_STATUS,
                cloudError = active.lastError,
            )
        }
    }

    fun clearStatus() {
        _status.value = null
    }

    private fun enqueueCloudSync(): Boolean = try {
        val request = OneTimeWorkRequestBuilder<SosSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SosSyncWorker.URGENT_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        true
    } catch (error: Exception) {
        Log.e(TAG, "Could not schedule emergency sync", error)
        false
    }

    data class SyncOutcome(
        val uploaded: Int,
        val attempted: Int,
        val problems: List<String>,
    )

    companion object {
        private const val TAG = "SosEmergencyManager"

        /** Returned when a cancellation had no server-side copy to resolve. */
        private const val HTTP_NO_REMOTE_COPY = 200

        /**
         * Status used while an emergency is waiting for the server. It is
         * deliberately distinct from `SYNCED`: nothing may claim server acceptance
         * before the server answered.
         */
        const val UNVERIFIED_SYNC_STATUS = "PENDING"
        const val SYNCED_SYNC_STATUS = "SYNCED"
        const val CANCELLED_SYNC_STATUS = "CANCELLED"
    }
}
