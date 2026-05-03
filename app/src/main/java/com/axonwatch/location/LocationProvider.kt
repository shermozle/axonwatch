package com.axonwatch.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationProvider(private val context: Context) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    /** Returns the best available current location, or null if permission is denied. */
    suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null
        return suspendCancellableCoroutine { cont ->
            try {
                val request = CurrentLocationRequest.Builder()
                    .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
                    .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setMaxUpdateAgeMillis(MAX_AGE_MS)
                    .build()
                fusedLocationClient.getCurrentLocation(request, null)
                    .addOnSuccessListener { loc -> cont.resume(loc) }
                    .addOnFailureListener { cont.resume(null) }
            } catch (_: SecurityException) {
                cont.resume(null)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Accept a cached fix up to 30 s old to avoid GPS cold-start delays on every detection */
        private const val MAX_AGE_MS = 30_000L
    }
}
