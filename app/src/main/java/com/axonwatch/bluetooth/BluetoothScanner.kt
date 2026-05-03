package com.axonwatch.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class ScannedDevice(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val type: ScanType
)

enum class ScanType { BLE, CLASSIC }

/**
 * Wraps both BLE and Classic Bluetooth scanning. Emits [ScannedDevice] events for any device
 * whose MAC address starts with one of the configured prefixes.
 *
 * Classic discovery finishes after ~12 s; [restartClassicDiscovery] should be called once
 * ACTION_DISCOVERY_FINISHED is received (handled by [BluetoothScanService]).
 */
class BluetoothScanner(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _detections = MutableSharedFlow<ScannedDevice>(extraBufferCapacity = 128)
    val detections: SharedFlow<ScannedDevice> = _detections

    @Volatile private var macPrefixes: List<String> = listOf("00:25:DF")
    @Volatile private var scanning = false
    private var classicReceiverRegistered = false

    // ── BLE ──────────────────────────────────────────────────────────────────

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            if (matchesPrefixes(mac)) {
                _detections.tryEmit(
                    ScannedDevice(
                        mac = mac,
                        name = safeDeviceName(result.device),
                        rssi = result.rssi,
                        type = ScanType.BLE
                    )
                )
            }
        }
    }

    // ── Classic ───────────────────────────────────────────────────────────────

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
            device?.address?.let { mac ->
                if (matchesPrefixes(mac)) {
                    _detections.tryEmit(
                        ScannedDevice(
                            mac = mac,
                            name = safeDeviceName(device),
                            rssi = rssi,
                            type = ScanType.CLASSIC
                        )
                    )
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun updatePrefixes(prefixes: List<String>) {
        macPrefixes = prefixes.map { it.uppercase() }
    }

    fun startScanning() {
        if (scanning) return
        scanning = true
        startBleScan()
        startClassicDiscovery()
    }

    fun stopScanning() {
        scanning = false
        stopBleScan()
        stopClassicDiscovery()
    }

    /** Call this after receiving BluetoothAdapter.ACTION_DISCOVERY_FINISHED. */
    fun restartClassicDiscovery() {
        if (!scanning) return
        startClassicDiscovery()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startBleScan() {
        if (!hasBluetoothScanPermission()) return
        val leScanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0)
            .build()
        try {
            leScanner.startScan(null, settings, bleScanCallback)
        } catch (_: SecurityException) {}
    }

    private fun stopBleScan() {
        if (!hasBluetoothScanPermission()) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        } catch (_: SecurityException) {}
    }

    private fun startClassicDiscovery() {
        if (!hasBluetoothScanPermission()) return
        if (!classicReceiverRegistered) {
            context.registerReceiver(classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
            classicReceiverRegistered = true
        }
        try {
            bluetoothAdapter?.startDiscovery()
        } catch (_: SecurityException) {}
    }

    private fun stopClassicDiscovery() {
        if (classicReceiverRegistered) {
            try { context.unregisterReceiver(classicReceiver) } catch (_: Exception) {}
            classicReceiverRegistered = false
        }
        if (!hasBluetoothScanPermission()) return
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: SecurityException) {}
    }

    private fun matchesPrefixes(mac: String): Boolean =
        macPrefixes.any { mac.uppercase().startsWith(it) }

    private fun safeDeviceName(device: BluetoothDevice): String? = try {
        if (hasBluetoothConnectPermission()) device.name else null
    } catch (_: SecurityException) { null }

    private fun hasBluetoothScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasBluetoothConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
}
