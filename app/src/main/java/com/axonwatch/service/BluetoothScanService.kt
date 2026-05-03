package com.axonwatch.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import com.axonwatch.bluetooth.BluetoothScanner
import com.axonwatch.data.model.DetectionEvent
import com.axonwatch.location.LocationProvider
import com.axonwatch.network.ReportingClient
import com.axonwatch.notification.NotificationHelper
import com.axonwatch.repository.ScanRepository
import com.axonwatch.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that orchestrates Bluetooth scanning, location capture, alert sounds,
 * notification updates, and server reporting.
 *
 * Lifecycle: start with ACTION_START (from MainActivity), self-stop with ACTION_STOP.
 * The service is START_STICKY so Android restarts it after process death.
 */
class BluetoothScanService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var settings: SettingsManager
    private lateinit var scanner: BluetoothScanner
    private lateinit var locationProvider: LocationProvider
    private lateinit var reportingClient: ReportingClient
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var repository: ScanRepository

    private var notificationJob: Job? = null

    // Restart classic BT discovery after each discovery cycle finishes (~12 s)
    private val discoveryFinishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                serviceScope.launch {
                    delay(DISCOVERY_RESTART_DELAY_MS)
                    scanner.restartClassicDiscovery()
                }
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        scanner = BluetoothScanner(this)
        locationProvider = LocationProvider(this)
        reportingClient = ReportingClient(settings, serviceScope)
        notificationHelper = NotificationHelper(this)
        repository = ScanRepository()

        scanner.updatePrefixes(settings.macPrefixes)
        registerReceiver(
            discoveryFinishedReceiver,
            IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startScanning()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scanner.stopScanning()
        reportingClient.disconnect()
        notificationJob?.cancel()
        serviceScope.launch { /* allow coroutines to wind down */ }
        try { unregisterReceiver(discoveryFinishedReceiver) } catch (_: Exception) {}
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Scanning ──────────────────────────────────────────────────────────────

    private fun startScanning() {
        isRunning = true
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            notificationHelper.buildScanningNotification(0)
        )

        scanner.detections
            .onEach { device ->
                val location = locationProvider.getCurrentLocation()
                val event = DetectionEvent(
                    mac = device.mac,
                    name = device.name,
                    rssi = device.rssi,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    locationAccuracy = location?.accuracy,
                    reporterId = settings.reporterId
                )
                val isNew = repository.recordDetection(event)
                if (isNew) {
                    if (settings.soundEnabled) playAlert()
                    reportingClient.report(event)
                    broadcastUpdate()
                }
            }
            .launchIn(serviceScope)

        scanner.startScanning()
        reportingClient.connect()
        startPeriodicNotificationUpdates()
    }

    private fun startPeriodicNotificationUpdates() {
        notificationJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val count = repository.getUniqueMatchesInWindow(FIVE_MINUTES_MS)
        notificationHelper.updateNotification(count)
    }

    private fun broadcastUpdate() {
        val count = repository.getUniqueMatchesInWindow(FIVE_MINUTES_MS)
        sendBroadcast(
            Intent(ACTION_UPDATE).putExtra(EXTRA_MATCH_COUNT, count)
        )
    }

    private fun playAlert() {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
                delay(500)
                toneGen.release()
            }
        }
    }

    companion object {
        const val ACTION_START = "com.axonwatch.action.START_SCAN"
        const val ACTION_STOP = "com.axonwatch.action.STOP_SCAN"
        const val ACTION_UPDATE = "com.axonwatch.action.UPDATE"
        const val EXTRA_MATCH_COUNT = "extra_match_count"

        /** True while the service is running; read by the UI to restore button state. */
        @Volatile var isRunning = false
            private set

        private const val FIVE_MINUTES_MS = 5 * 60 * 1000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 30_000L
        private const val DISCOVERY_RESTART_DELAY_MS = 5_000L
    }
}
