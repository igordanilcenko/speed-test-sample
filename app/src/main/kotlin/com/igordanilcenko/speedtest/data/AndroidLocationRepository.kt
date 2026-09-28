package com.igordanilcenko.speedtest.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.igordanilcenko.speedtest.domain.LocationException
import com.igordanilcenko.speedtest.domain.LocationFailure
import com.igordanilcenko.speedtest.domain.LocationRepository
import com.igordanilcenko.speedtest.domain.model.Coordinates
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.resume

class AndroidLocationRepository(context: Context, private val log: (String) -> Unit = {}) : LocationRepository {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun getCurrentCoordinates(): Coordinates {
        try {
            log("[Location] Starting discovery (Approximate)")
            
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                log("[Location] Failed: ACCESS_COARSE_LOCATION not granted")
                throw LocationException(LocationFailure.PermissionDenied)
            }
            
            if (!LocationManagerCompat.isLocationEnabled(manager)) {
                log("[Location] Failed: location services disabled")
                throw LocationException(LocationFailure.Disabled)
            }

            val providers = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                add(LocationManager.NETWORK_PROVIDER)
            }.filter { provider ->
                val available = LocationManagerCompat.hasProvider(manager, provider)
                val enabled = available && manager.isProviderEnabled(provider)
                log("[Location] Provider=$provider, available=$available, enabled=$enabled")
                enabled
            }

            if (providers.isEmpty()) {
                log("[Location] Failed: no active providers")
                throw LocationException(LocationFailure.Unavailable)
            }

            for (provider in providers) {
                val lastLocation = try {
                    manager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
                if (lastLocation != null) {
                    log("[Location] Success: using lastKnownLocation from $provider")
                    return Coordinates(lastLocation.latitude, lastLocation.longitude)
                }
            }

            for (provider in providers) {
                log("[Location] Requesting fresh location from $provider (timeout=10s)")
                val freshLocation = withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<Coordinates?> { continuation ->
                        val cancellation = CancellationSignal()
                        continuation.invokeOnCancellation { cancellation.cancel() }
                        try {
                            LocationManagerCompat.getCurrentLocation(
                                manager, provider, cancellation, ContextCompat.getMainExecutor(context)
                            ) { location ->
                                continuation.resume(location?.let { Coordinates(it.latitude, it.longitude) })
                            }
                        } catch (e: SecurityException) {
                            continuation.resume(null)
                        }
                    }
                }

                if (freshLocation != null) {
                    log("[Location] Success: received location from $provider")
                    return freshLocation
                }
            }

            log("[Location] Failed: All providers failed or timed out")
            throw LocationException(LocationFailure.Unavailable)
        } catch (error: CancellationException) {
            log("[Location] Request cancelled")
            throw error
        } catch (error: LocationException) {
            throw error
        } catch (e: SecurityException) {
            log("[Location] Failed: SecurityException")
            throw LocationException(LocationFailure.PermissionDenied)
        } catch (e: Exception) {
            log("[Location] Unexpected error: ${e.message}")
            throw LocationException(LocationFailure.Unavailable)
        }
    }
}
