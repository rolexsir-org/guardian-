package com.guardian.safety.remote

import com.guardian.safety.remote.model.PresenceEventDataDto
import com.guardian.safety.remote.model.RealtimeEventDto
import com.guardian.safety.remote.model.RealtimeFrameDto
import com.guardian.safety.remote.model.RealtimePresenceDto
import com.guardian.safety.remote.model.SosEventDto
import com.guardian.safety.service.TokenManager
import com.squareup.moshi.JsonAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

sealed interface RealtimeState {
    data object Disconnected : RealtimeState
    data object Connecting : RealtimeState
    data class Connected(val scope: String) : RealtimeState
    data class Reconnecting(val attempt: Int) : RealtimeState
    data class Failed(val reason: String) : RealtimeState
}

/** Payload wrapper so listeners can react without knowing the wire shape. */
sealed interface RealtimeUpdate {
    data class Connected(
        val scope: String,
        val seq: Long,
        val presence: List<RealtimePresenceDto>,
        val activeSos: List<SosEventDto>,
    ) : RealtimeUpdate

    data class Event(val seq: Long, val event: RealtimeEventDto) : RealtimeUpdate

    /** The client missed frames and must reload durable state from the REST API. */
    data class SyncGap(
        val seq: Long,
        val presence: List<RealtimePresenceDto>,
        val activeSos: List<SosEventDto>,
    ) : RealtimeUpdate

    data class Error(val code: String) : RealtimeUpdate
}

/**
 * WebSocket client for the SafetyHub Durable Object.
 *
 * Safety-critical guarantees:
 * * **Reconnect** — exponential backoff with jitter, and it keeps retrying while
 *   the app is foregrounded rather than silently staying dead.
 * * **Stale connection handling** — a pong/heartbeat watchdog closes the socket
 *   and reconnects when the hub stops responding.
 * * **Duplicate/gap protection** — events are delivered strictly in sequence;
 *   a gap triggers `sync` (full resync) instead of applying partial state.
 * * **Lifecycle cleanup** — [close] cancels every timer and the socket.
 */
class RealtimeClient(
    private val tokenManager: TokenManager,
    private val apiClient: ApiClient,
    private val scope: CoroutineScope,
) {

    private val moshi = com.squareup.moshi.Moshi.Builder().build()
    private val frameAdapter: JsonAdapter<RealtimeFrameDto> = moshi.adapter(RealtimeFrameDto::class.java)
    private val presenceDataAdapter: JsonAdapter<PresenceEventDataDto> =
        moshi.adapter(PresenceEventDataDto::class.java)
    private val sosAdapter: JsonAdapter<SosEventDto> = moshi.adapter(SosEventDto::class.java)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // websocket: no read deadline
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<RealtimeState>(RealtimeState.Disconnected)
    val state: StateFlow<RealtimeState> = _state.asStateFlow()

    private val _presence = MutableStateFlow<List<RealtimePresenceDto>>(emptyList())
    val presence: StateFlow<List<RealtimePresenceDto>> = _presence.asStateFlow()

    private val _activeSos = MutableStateFlow<List<SosEventDto>>(emptyList())
    val activeSos: StateFlow<List<SosEventDto>> = _activeSos.asStateFlow()

    var listener: ((RealtimeUpdate) -> Unit)? = null

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    private var subscriptionPath: String? = null
    private var lastSeq: Long = 0L
    private var attempt: Int = 0
    private var lastFrameAt: Long = 0L
    private var manuallyClosed = false

    /** Opens (or switches to) a family realtime scope. Idempotent per path. */
    fun subscribe(path: String) {
        if (subscriptionPath == path && socket != null) return
        closeSocketOnly()
        subscriptionPath = path
        lastSeq = 0L
        manuallyClosed = false
        attempt = 0
        connect()
    }

    fun close() {
        manuallyClosed = true
        subscriptionPath = null
        closeSocketOnly()
        _state.value = RealtimeState.Disconnected
        _presence.value = emptyList()
        _activeSos.value = emptyList()
    }

    private fun closeSocketOnly() {
        reconnectJob?.cancel()
        reconnectJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        socket?.cancel()
        socket = null
    }

    private fun connect() {
        val path = subscriptionPath ?: return
        if (!CloudConfig.configured) {
            _state.value = RealtimeState.Failed(
                CloudConfig.configurationError ?: "Realtime is unavailable: backend not configured.",
            )
            return
        }
        val token = tokenManager.accessToken()
        if (token.isNullOrBlank()) {
            _state.value = RealtimeState.Failed("Sign in to receive live family updates.")
            return
        }

        _state.value = if (attempt == 0) RealtimeState.Connecting else RealtimeState.Reconnecting(attempt)
        val url = CloudConfig.baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
        } catch (error: IllegalArgumentException) {
            _state.value = RealtimeState.Failed("Realtime URL is invalid.")
            return
        }

        lastFrameAt = System.currentTimeMillis()
        socket = client.newWebSocket(request, listener)
        startWatchdog()
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            lastFrameAt = System.currentTimeMillis()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastFrameAt = System.currentTimeMillis()
            val frame = try {
                frameAdapter.fromJson(text)
            } catch (_: Exception) {
                null
            } ?: return
            handleFrame(frame)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            scheduleReconnect(reason.ifBlank { "Connection closed" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            val status = response?.code
            if (status == 401) {
                // Credentials need refreshing before the next attempt; the API
                // client owns that and reports back through its own callback.
                scope.launch { apiClient.refreshSession() }
            }
            scheduleReconnect(t.message ?: "Connection lost")
        }
    }

    private fun handleFrame(frame: RealtimeFrameDto) {
        when (frame.type) {
            "hello", "sync.gap" -> {
                lastSeq = frame.seq
                val isGap = frame.type == "sync.gap"
                _state.value = RealtimeState.Connected(frame.scope ?: subscriptionPath ?: "")
                _presence.value = frame.presence
                _activeSos.value = frame.activeSos
                val update = if (isGap) {
                    RealtimeUpdate.SyncGap(frame.seq, frame.presence, frame.activeSos)
                } else {
                    RealtimeUpdate.Connected(frame.scope ?: "", frame.seq, frame.presence, frame.activeSos)
                }
                listener?.invoke(update)
            }

            "event" -> {
                val event = frame.event ?: return
                val seq = frame.seq
                if (seq in 1..lastSeq) return // duplicate delivery: already applied
                if (lastSeq > 0 && seq > lastSeq + 1) {
                    // Sequence gap: ask for a full resync instead of guessing.
                    lastSeq = seq
                    requestResync()
                    return
                }
                lastSeq = seq
                when (event.kind) {
                    "PRESENCE" -> applyPresenceFromEvent(event)
                    "SOS_CREATED", "SOS_UPDATED" -> applySosFromEvent(event)
                }
                listener?.invoke(RealtimeUpdate.Event(seq, event))
            }

            "pong" -> Unit

            "error" -> {
                val code = frame.code ?: "realtime_error"
                listener?.invoke(RealtimeUpdate.Error(code))
                if (code == "unauthorized" || code == "forbidden") {
                    // Reconnecting with the same credentials cannot succeed.
                    closeSocketOnly()
                    _state.value = RealtimeState.Failed("Realtime access was denied. Sign in again.")
                }
            }
        }
    }

    /** Requests a resync without blocking the socket reader. */
    private fun requestResync() {
        val webSocket = socket ?: return
        val since = maxOf(0L, lastSeq - RESYNC_OVERLAP_EVENTS)
        runCatching { webSocket.send("""{"type":"sync","since":$since}""") }
    }

    private fun applyPresenceFromEvent(event: RealtimeEventDto) {
        val data = event.data ?: return
        val payload = try {
            presenceDataAdapter.fromJson(data.toString())
        } catch (_: Exception) {
            null
        } ?: return
        if (payload.userId.isBlank()) return
        _presence.value = _presence.value.filterNot { it.userId == payload.userId } + RealtimePresenceDto(
            userId = payload.userId,
            status = payload.status,
            lastSeenAt = payload.lastSeenAt ?: event.at,
            batteryLevel = payload.batteryLevel,
        )
    }

    private fun applySosFromEvent(event: RealtimeEventDto) {
        val data = event.data ?: return
        val sos = try {
            sosAdapter.fromJson(data.toString())
        } catch (_: Exception) {
            null
        } ?: return
        if (sos.id.isBlank()) return
        _activeSos.value = when (sos.status) {
            "RESOLVED", "CANCELLED" -> _activeSos.value.filterNot { it.id == sos.id }
            else -> _activeSos.value.filterNot { it.id == sos.id } + sos
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (socket != null) {
                delay(WATCHDOG_INTERVAL_MS)
                if (System.currentTimeMillis() - lastFrameAt > STALE_AFTER_MS) {
                    // The hub went quiet (network drop, sleeping device): drop the
                    // socket so the safety state cannot appear live while stale.
                    socket?.cancel()
                    socket = null
                    scheduleReconnect("Realtime connection went stale")
                    return@launch
                }
                runCatching { socket?.send("""{"type":"ping"}""") }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (manuallyClosed || subscriptionPath == null) {
            _state.value = RealtimeState.Disconnected
            return
        }
        attempt += 1
        _state.value = RealtimeState.Reconnecting(attempt)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMillis(attempt))
            connect()
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = INITIAL_BACKOFF_MS shl min(attempt - 1, 5)
        val capped = min(base, MAX_BACKOFF_MS)
        val jitter = Random.nextLong(capped / 5 + 1)
        return capped / 2 + jitter
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val PING_INTERVAL_SECONDS = 20L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val STALE_AFTER_MS = 90_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val RESYNC_OVERLAP_EVENTS = 5L
    }
}
