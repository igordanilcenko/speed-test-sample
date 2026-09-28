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

    override suspend fun getCurrentCoordinates(): Coordinates = try {
        log("[Location] Requesting approximate location")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            log("[Location] Failed: ACCESS_COARSE_LOCATION permission not granted")
            throw LocationException(LocationFailure.PermissionDenied)
        }
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            log("[Location] Failed: device location services are disabled")
            throw LocationException(LocationFailure.Disabled)
        }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        val provider = providers.firstOrNull {
            val available = LocationManagerCompat.hasProvider(manager, it)
            val enabled = available && manager.isProviderEnabled(it)
            log("[Location] Provider=$it, available=$available, enabled=$enabled")
            enabled
        } ?: run {
            log("[Location] Failed: no enabled approximate-location provider")
            throw LocationException(LocationFailure.Unavailable)
        }

        log("[Location] Waiting for provider=$provider, timeout=15 seconds")
        val coordinates = withTimeoutOrNull(15_000) {
            val result = suspendCancellableCoroutine<Coordinates?> { continuation ->
                val cancellation = CancellationSignal()
                continuation.invokeOnCancellation { cancellation.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, cancellation, ContextCompat.getMainExecutor(context),
                ) { location ->
                    continuation.resume(location?.let { Coordinates(it.latitude, it.longitude) })
                }
            }
            result ?: run {
                log("[Location] Failed: provider=$provider returned no location")
                throw LocationException(LocationFailure.Unavailable)
            }
        } ?: run {
            log("[Location] Failed: timed out after 15 seconds, provider=$provider")
            throw LocationException(LocationFailure.Unavailable)
        }
        log("[Location] Coordinates received successfully, provider=$provider")
        coordinates
    } catch (error: CancellationException) {
        log("[Location] Request cancelled")
        throw error
    } catch (_: SecurityException) {
        log("[Location] Failed: Android denied location access (SecurityException)")
        throw LocationException(LocationFailure.PermissionDenied)
    }
}
