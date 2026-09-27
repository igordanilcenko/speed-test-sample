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
import kotlin.coroutines.resume

class AndroidLocationRepository(context: Context) : LocationRepository {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun getCurrentCoordinates(): Coordinates {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            throw LocationException(LocationFailure.PermissionDenied)
        }
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            throw LocationException(LocationFailure.Disabled)
        }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        val provider = providers.firstOrNull {
            LocationManagerCompat.hasProvider(manager, it) && manager.isProviderEnabled(it)
        } ?: throw LocationException(LocationFailure.Unavailable)

        return try {
            withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Coordinates?> { continuation ->
                    val cancellation = CancellationSignal()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        manager, provider, cancellation, ContextCompat.getMainExecutor(context),
                    ) { location ->
                        continuation.resume(location?.let { Coordinates(it.latitude, it.longitude) })
                    }
                }
            } ?: throw LocationException(LocationFailure.Unavailable)
        } catch (_: SecurityException) {
            throw LocationException(LocationFailure.PermissionDenied)
        }
    }
}
