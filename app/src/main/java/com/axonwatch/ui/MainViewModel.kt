package com.axonwatch.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.axonwatch.service.BluetoothScanService

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _isScanning = MutableLiveData(BluetoothScanService.isRunning)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _matchCount = MutableLiveData(0)
    val matchCount: LiveData<Int> = _matchCount

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothScanService.ACTION_UPDATE) return
            _matchCount.postValue(
                intent.getIntExtra(BluetoothScanService.EXTRA_MATCH_COUNT, 0)
            )
            _isScanning.postValue(BluetoothScanService.isRunning)
        }
    }

    init {
        application.registerReceiver(
            updateReceiver,
            IntentFilter(BluetoothScanService.ACTION_UPDATE)
        )
    }

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(updateReceiver) }
        super.onCleared()
    }
}
