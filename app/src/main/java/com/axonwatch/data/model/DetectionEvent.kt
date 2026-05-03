package com.axonwatch.data.model

data class DetectionEvent(
    val mac: String,
    val name: String?,
    val rssi: Int,
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracy: Float?,
    val timestamp: Long = System.currentTimeMillis(),
    val reporterId: String
)
