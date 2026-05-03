package com.axonwatch.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.axonwatch.databinding.ActivityMainBinding
import com.axonwatch.service.BluetoothScanService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startScanService()
        } else {
            Toast.makeText(this, "Required permissions denied — cannot scan", Toast.LENGTH_LONG)
                .show()
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isBluetoothEnabled()) requestPermissionsAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartStop.setOnClickListener {
            if (viewModel.isScanning.value == true) stopScanService()
            else checkBluetoothAndStart()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        viewModel.isScanning.observe(this) { scanning ->
            binding.btnStartStop.text = if (scanning) "Stop Scanning" else "Start Scanning"
            binding.tvStatus.text = if (scanning) "Scanning…" else "Idle"
        }

        viewModel.matchCount.observe(this) { count ->
            binding.tvMatchCount.text = "Matches (last 5 min): $count"
        }
    }

    override fun onResume() {
        super.onResume()
        // Keep button state in sync if user navigated away while service changed
        viewModel.setScanning(BluetoothScanService.isRunning)
    }

    private fun checkBluetoothAndStart() {
        if (!isBluetoothEnabled()) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        requestPermissionsAndStart()
    }

    private fun requestPermissionsAndStart() {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(required.toTypedArray())
    }

    private fun startScanService() {
        val intent = Intent(this, BluetoothScanService::class.java)
            .setAction(BluetoothScanService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        viewModel.setScanning(true)
        promptBatteryOptimisation()
    }

    private fun stopScanService() {
        startService(
            Intent(this, BluetoothScanService::class.java)
                .setAction(BluetoothScanService.ACTION_STOP)
        )
        viewModel.setScanning(false)
    }

    /** Asks the user to exempt the app from battery optimisation so scans survive Doze. */
    private fun promptBatteryOptimisation() {
        AlertDialog.Builder(this)
            .setTitle("Battery Optimisation")
            .setMessage(
                "For reliable background scanning, please disable battery optimisation for AxonWatch."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun isBluetoothEnabled(): Boolean =
        (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.isEnabled == true
}
