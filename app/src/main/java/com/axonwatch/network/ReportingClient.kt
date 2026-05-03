package com.axonwatch.network

import com.axonwatch.data.model.DetectionEvent
import com.axonwatch.settings.SettingsManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// ── Wire types (serialised to JSON) ──────────────────────────────────────────

/** Envelope for every client→server WebSocket message. */
data class WsMessage(
    val type: String,
    val data: Any? = null,
    val timestamp: String = Instant.now().toString()
)

/** Payload carried in a "detection" WsMessage and in the REST body. */
data class DetectionPayload(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val lat: Double?,
    val lng: Double?,
    @SerializedName("location_accuracy") val locationAccuracy: Float?,
    val timestamp: String,
    @SerializedName("reporter_id") val reporterId: String
)

// ── Client ────────────────────────────────────────────────────────────────────

/**
 * Reports detection events to the configured server via WebSocket with a REST fallback.
 *
 * Flow:
 *  1. On [connect] a WebSocket to `{serverUrl}/ws/detections` is opened.
 *  2. Every [report] call serialises the event and sends it over the socket.
 *  3. If the socket is not yet open the event is queued (up to [MAX_QUEUE_SIZE] entries).
 *  4. When the socket opens the queue is drained.
 *  5. On socket failure, individual events are retried via REST POST, and reconnection
 *     is scheduled with exponential back-off.
 */
class ReportingClient(
    private val settings: SettingsManager,
    private val scope: CoroutineScope
) {
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // no timeout for WebSocket reads
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val pendingQueue = LinkedList<DetectionEvent>()
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = RECONNECT_INITIAL_MS

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect() {
        val url = settings.serverUrl
        if (url.isBlank()) return
        val wsUrl = url.trimEnd('/').replace("https://", "wss://").replace("http://", "ws://") +
                "/ws/detections"
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer ${settings.apiToken}")
            .build()
        webSocket = httpClient.newWebSocket(request, socketListener)
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnecting")
        connected.set(false)
    }

    fun report(event: DetectionEvent) {
        val json = gson.toJson(WsMessage("detection", toPayload(event)))
        if (connected.get() && webSocket?.send(json) == true) return
        enqueue(event)
        sendViaRest(event)
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected.set(true)
            reconnectDelayMs = RECONNECT_INITIAL_MS
            drainQueue()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected.set(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected.set(false)
            if (code != 1000) scheduleReconnect()
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun drainQueue() {
        scope.launch(Dispatchers.IO) {
            synchronized(pendingQueue) {
                while (pendingQueue.isNotEmpty() && connected.get()) {
                    val event = pendingQueue.poll() ?: break
                    val json = gson.toJson(WsMessage("detection", toPayload(event)))
                    if (webSocket?.send(json) == false) {
                        pendingQueue.addFirst(event)  // put back, socket closed mid-drain
                        break
                    }
                }
            }
        }
    }

    private fun sendViaRest(event: DetectionEvent) {
        val url = settings.serverUrl.trimEnd('/')
        if (url.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val body = gson.toJson(toPayload(event))
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$url/api/v1/detections")
                    .header("Authorization", "Bearer ${settings.apiToken}")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().close()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        reconnectJob = scope.launch {
            delay(delay)
            connect()
        }
    }

    private fun enqueue(event: DetectionEvent) {
        synchronized(pendingQueue) {
            if (pendingQueue.size < MAX_QUEUE_SIZE) pendingQueue.add(event)
        }
    }

    /** Stub for handling server-pushed messages (nearby detections, acks, config). */
    private fun handleServerMessage(text: String) {
        // Intentionally minimal for now; extend when server pushes nearby_detections
    }

    private fun toPayload(event: DetectionEvent) = DetectionPayload(
        mac = event.mac,
        name = event.name,
        rssi = event.rssi,
        lat = event.latitude,
        lng = event.longitude,
        locationAccuracy = event.locationAccuracy,
        timestamp = Instant.ofEpochMilli(event.timestamp).toString(),
        reporterId = event.reporterId
    )

    companion object {
        private const val RECONNECT_INITIAL_MS = 3_000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val MAX_QUEUE_SIZE = 500
    }
}
